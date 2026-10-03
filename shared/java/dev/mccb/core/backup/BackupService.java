package dev.mccb.core.backup;

import dev.mccb.core.archive.WorldArchiver;
import dev.mccb.core.config.BackupConfig;
import dev.mccb.core.config.ConfigStore;
import dev.mccb.core.platform.WorldHandle;
import dev.mccb.core.snapshot.SnapshotIndex;
import dev.mccb.core.snapshot.SnapshotMeta;
import dev.mccb.core.util.HumanSize;
import dev.mccb.core.util.Log;
import dev.mccb.core.util.ProgressListener;
import dev.mccb.core.util.Timestamps;
import dev.mccb.core.webdav.WebDavClient;
import dev.mccb.core.webdav.WebDavException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class BackupService {

	public enum Status {
		UPLOADED, SKIPPED_UNCHANGED, FAILED, DISABLED, NOT_CONFIGURED
	}

	public record Outcome(Status status, String message, SnapshotMeta snapshot) {
		public boolean ok() {
			return status == Status.UPLOADED || status == Status.SKIPPED_UNCHANGED;
		}
	}

	/** 列出云端世界的结果。用字符串传错误而不是抛异常，方便界面代码直接显示。 */
	public record WorldsResult(List<String> worlds, String error) {
	}

	public record SnapshotsResult(List<SnapshotMeta> snapshots, String error) {
	}

	private final ConfigStore configStore;
	private final String detectedModpack;
	private final Path workDir;

	/**
	 * @param detectedModpack 启动时识别出的整合包名，用于在云端分组。
	 *                        会话期间不会变，所以在构造时算一次就够了。
	 */
	public BackupService(ConfigStore configStore, String detectedModpack, Path workDir) {
		this.configStore = configStore;
		this.detectedModpack = detectedModpack;
		this.workDir = workDir;
	}

	/**
	 * 按配置构造云端仓库。
	 *
	 * <p>整合包那一层是拼接在 remoteRoot 后面实现的，{@link CloudStore} 自己
	 * 并不需要知道有整合包这回事——它看到的"根目录"已经是整合包目录了。
	 */
	private CloudStore cloudStore(BackupConfig config) {
		return new CloudStore(
				createClient(config),
				config.effectiveRemoteRoot(detectedModpack),
				workDir);
	}

	/** 按当前配置构造一个 WebDAV 客户端。 */
	public static WebDavClient createClient(BackupConfig config) {
		return new WebDavClient(
				config.webdav.url,
				config.webdav.username,
				config.webdav.password,
				Duration.ofSeconds(config.advanced.timeoutSeconds),
				config.webdav.allowInsecureTls);
	}

	/**
	 * 执行一次备份。这个方法会阻塞，必须由 {@link BackupScheduler} 在后台线程调用。
	 */
	public Outcome backup(WorldHandle world, ProgressListener progress) {
		// 每次都重新读配置，这样玩家在界面上改完设置能立刻生效，不用重启游戏。
		BackupConfig config = configStore.load();

		if (!config.enabled) {
			return new Outcome(Status.DISABLED, "云存档功能已关闭", null);
		}

		if (!config.isConfigured()) {
			return new Outcome(Status.NOT_CONFIGURED, "尚未配置 WebDAV 地址", null);
		}

		Path tempZip = null;

		try {
			Files.createDirectories(workDir);

			String stamp = Timestamps.fileStamp();
			tempZip = workDir.resolve(world.id() + "-" + stamp + ".zip");

			WorldArchiver.Result result = WorldArchiver.archive(
					world.directory(),
					tempZip,
					config.advanced.excludePatterns,
					config.advanced.compressionLevel,
					progress);

			if (result.fileCount() == 0) {
				return new Outcome(Status.FAILED, "世界目录里没有可打包的文件：" + world.directory(), null);
			}

			CloudStore cloud = cloudStore(config);
			cloud.ensureWorldDir(world.id());

			SnapshotIndex index = cloud.loadIndex(world.id());
			SnapshotMeta latest = index.latest();

			if (config.advanced.skipUnchanged && latest != null && result.contentHash().equals(latest.contentHash)) {
				return new Outcome(Status.SKIPPED_UNCHANGED, "世界内容与上次备份相同，已跳过上传", latest);
			}

			String fileName = uniqueFileName(index, stamp, result.contentHash());

			// 阶段文字由 WebDavClient 自己设（它才知道真实字节数）。
			cloud.upload(world.id(), fileName, tempZip, progress);

			SnapshotMeta meta = new SnapshotMeta(
					fileName,
					result.zipSizeBytes(),
					result.contentHash(),
					System.currentTimeMillis());

			index.snapshots.add(0, meta);

			applyRetention(cloud, world.id(), index, config.retention.maxSnapshotsPerWorld);

			// 清单最后写，保证它引用的每个快照都已经在云端了。
			progress.onStage("正在写入快照清单");
			cloud.saveIndex(world.id(), index);

			Log.info("备份完成：" + world.id() + " -> " + fileName + "（" + HumanSize.format(result.zipSizeBytes()) + "）");

			return new Outcome(Status.UPLOADED,
					"已上传 " + fileName + "（" + HumanSize.format(result.zipSizeBytes()) + "）", meta);
		} catch (WebDavException e) {
			Log.error("备份失败：" + world.id(), e);
			return new Outcome(Status.FAILED, describe(e), null);
		} catch (IOException e) {
			Log.error("打包世界失败：" + world.id(), e);
			return new Outcome(Status.FAILED, "打包世界失败：" + e.getMessage(), null);
		} catch (Throwable t) {
			// 兜底：后台任务抛出的任何意外都不该让调度器停下来。
			Log.error("备份过程中出现未预期的错误：" + world.id(), t);
			return new Outcome(Status.FAILED, "未预期的错误：" + t, null);
		} finally {
			deleteQuietly(tempZip);
		}
	}

	/** 列出云端已有的世界。供界面使用，不抛异常。 */
	public WorldsResult listCloudWorlds() {
		BackupConfig config = configStore.load();

		if (!config.isConfigured()) {
			return new WorldsResult(new ArrayList<>(), "尚未配置 WebDAV 地址");
		}

		try {
			CloudStore cloud = cloudStore(config);
			return new WorldsResult(cloud.listWorlds(), null);
		} catch (WebDavException e) {
			Log.warn("列出云端世界失败", e);
			return new WorldsResult(new ArrayList<>(), describe(e));
		}
	}

	/** 列出某个世界在云端的快照。供界面使用，不抛异常。 */
	public SnapshotsResult listSnapshots(String worldId) {
		BackupConfig config = configStore.load();

		if (!config.isConfigured()) {
			return new SnapshotsResult(new ArrayList<>(), "尚未配置 WebDAV 地址");
		}

		try {
			CloudStore cloud = cloudStore(config);
			return new SnapshotsResult(cloud.loadIndex(worldId).snapshots, null);
		} catch (WebDavException e) {
			Log.warn("列出云端快照失败：" + worldId, e);
			return new SnapshotsResult(new ArrayList<>(), describe(e));
		}
	}

	/**
	 * 生成一个在该世界下不会重名的快照文件名。
	 *
	 * <p>时间戳只精确到秒，而哈希前 8 位在内容相同时必然一样。所以
	 * 「同一秒内 + 相同内容 + 但和最新快照不同」这个组合会撞名，
	 * 直接 PUT 上去就会把已有快照覆盖掉。
	 *
	 * <p>这不是理论问题：还原一份旧快照后立刻再备份一次，就会正好落进这个组合。
	 */
	private static String uniqueFileName(SnapshotIndex index, String stamp, String contentHash) {
		int hashLength = Math.min(8, contentHash.length());
		String base = stamp + "-" + contentHash.substring(0, hashLength);

		String candidate = base + ".zip";
		int suffix = 1;

		while (containsFileName(index, candidate)) {
			candidate = base + "-" + suffix + ".zip";
			suffix++;
		}

		return candidate;
	}

	private static boolean containsFileName(SnapshotIndex index, String fileName) {
		for (SnapshotMeta meta : index.snapshots) {
			if (fileName.equals(meta.fileName)) {
				return true;
			}
		}

		return false;
	}

	private void applyRetention(CloudStore cloud, String worldId, SnapshotIndex index, int maxSnapshots) {
		List<SnapshotMeta> doomed = RetentionPolicy.selectForDeletion(index, maxSnapshots);

		for (SnapshotMeta meta : doomed) {
			try {
				cloud.delete(worldId, meta.fileName);
				index.snapshots.remove(meta);
			} catch (WebDavException e) {
				// 删不掉就留着。清单里继续保留它，免得云端有文件但清单没记录。
				Log.warn("删除旧快照失败，它会被保留在云端：" + meta.fileName, e);
			}
		}
	}

	private static String describe(WebDavException e) {
		int code = e.statusCode();

		if (code == 401 || code == 403) {
			return "认证失败（HTTP " + code + "），请检查用户名和密码";
		}

		if (code == 404) {
			return "云端路径不存在（HTTP 404），请检查 WebDAV 地址";
		}

		if (code < 0) {
			return "网络错误：" + e.getMessage();
		}

		return e.getMessage();
	}

	private static void deleteQuietly(Path path) {
		if (path == null) {
			return;
		}

		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// 临时文件留着也无妨，下次备份会覆盖。
		}
	}
}
