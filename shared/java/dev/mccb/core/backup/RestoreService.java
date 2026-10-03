package dev.mccb.core.backup;

import dev.mccb.core.archive.ZipExtractor;
import dev.mccb.core.config.BackupConfig;
import dev.mccb.core.config.ConfigStore;
import dev.mccb.core.platform.GamePlatform;
import dev.mccb.core.platform.WorldHandle;
import dev.mccb.core.util.Log;
import dev.mccb.core.util.ProgressListener;
import dev.mccb.core.util.Timestamps;
import dev.mccb.core.webdav.WebDavException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;

/**
 * 把云端快照还原回本地世界。
 *
 * <p>这是整个 mod 里唯一会<b>删玩家东西</b>的操作，所以流程按最保守的方式设计：
 * 下载并校验 → 把现有世界整个改名挪走 → 解压 → 失败就挪回来。
 * 任何一步出错，玩家原来的世界都还在，只是目录名多了一个后缀。
 */
public final class RestoreService {

	public enum Status {
		RESTORED, FAILED
	}

	/** 还原前自动另存的世界放在这个子目录下（相对 mod 的临时目录）。 */
	public static final String BACKUP_DIRECTORY = "backups";

	/**
	 * @param fallbackBackup 还原前挪走的原世界目录，出问题时可以从这里手工找回
	 */
	public record Outcome(Status status, String message, Path fallbackBackup) {
	}

	private final GamePlatform platform;
	private final ConfigStore configStore;
	private final String detectedModpack;
	private final Path workDir;

	public RestoreService(GamePlatform platform, ConfigStore configStore,
						  String detectedModpack, Path workDir) {
		this.detectedModpack = detectedModpack;
		this.platform = platform;
		this.configStore = configStore;
		this.workDir = workDir;
	}

	public Outcome restore(WorldHandle world, String snapshotFileName, ProgressListener progress) {
		BackupConfig config = configStore.load();

		if (!config.isConfigured()) {
			return new Outcome(Status.FAILED, "尚未配置 WebDAV 地址", null);
		}

		// 拒绝覆盖正在游玩的世界：目录里的文件正被游戏占着，
		// 这时候替换会得到半个旧世界加半个新世界。
		Optional<WorldHandle> current = platform.currentWorld();

		if (current.isPresent() && current.get().directory().equals(world.directory())) {
			return new Outcome(Status.FAILED, "这个正在游玩中，请先退出世界再还原", null);
		}

		Path tempZip = null;
		Path fallback = null;

		try {
			Files.createDirectories(workDir);
			tempZip = workDir.resolve(world.id() + "-restore-" + Timestamps.fileStamp() + ".zip");

			// 还原和备份必须用同一个云端根目录，否则会出现「备份传上去了但还原找不到」。
			CloudStore cloud = new CloudStore(
					BackupService.createClient(config),
					config.effectiveRemoteRoot(detectedModpack),
					workDir);

			cloud.download(world.id(), snapshotFileName, tempZip, progress);

			// 下下来先验一下再动玩家的东西。空包或者半截包在这里就拦掉。
			if (ZipExtractor.countEntries(tempZip) == 0) {
				return new Outcome(Status.FAILED, "下载到的快照是空的，已中止还原（本地世界未改动）", null);
			}

			progress.onStage("正在备份当前世界");
			fallback = moveAside(world.directory());

			try {
				ZipExtractor.extract(tempZip, world.directory(), progress);
			} catch (IOException extractError) {
				// 解压失败：把原来的世界挪回来，让玩家至少回到动手之前的状态。
				Log.error("解压快照失败，正在回滚", extractError);

				if (fallback == null) {
					// 本地本来就没有这个世界，没有东西可以回滚，把半成品清掉就行。
					try {
						deleteRecursively(world.directory());
					} catch (IOException cleanupError) {
						Log.warn("清理未完成的还原结果失败：" + world.directory(), cleanupError);
					}

					return new Outcome(Status.FAILED,
							"解压失败，已清理未完成的结果：" + extractError.getMessage(), null);
				}

				try {
					deleteRecursively(world.directory());
					Files.move(fallback, world.directory(), StandardCopyOption.REPLACE_EXISTING);

					return new Outcome(Status.FAILED,
							"解压失败，已回滚到还原前的状态：" + extractError.getMessage(), null);
				} catch (IOException rollbackError) {
					return new Outcome(Status.FAILED,
							"解压失败，且自动回滚也失败了。原世界保留在 " + fallback
									+ "，请手动改回目录名。", fallback);
				}
			}

			Log.info("还原完成：" + world.id() + " <- " + snapshotFileName);

			// 现在备份放在 mod 自己的目录下了，不在存档列表里，所以必须给出完整路径，
			// 否则玩家根本找不到它。
			String note = fallback == null
					? "（本地原本没有这个世界，未生成回退备份）"
					: "。还原前的世界已另存到 " + fallback;

			return new Outcome(Status.RESTORED, "已还原到 " + snapshotFileName + note, fallback);
		} catch (WebDavException e) {
			Log.error("还原失败：" + world.id(), e);
			return new Outcome(Status.FAILED, "下载快照失败：" + e.getMessage(), null);
		} catch (IOException e) {
			Log.error("还原失败：" + world.id(), e);
			return new Outcome(Status.FAILED, "还原失败：" + e.getMessage(), fallback);
		} catch (Throwable t) {
			Log.error("还原过程中出现未预期的错误：" + world.id(), t);
			return new Outcome(Status.FAILED, "未预期的错误：" + t, fallback);
		} finally {
			deleteQuietly(tempZip);
		}
	}

	/**
	 * 把现有世界整个挪到别处，返回新位置。
	 *
	 * <p><b>必须挪出 saves/ 目录。</b> 一开始的实现是就地改名成
	 * {@code <世界名>.mccb-backup-<时间>}，实测下来有两个后果：
	 * <ul>
	 *   <li>Minecraft 会把它当成一个可玩的世界列在单人游戏列表里，玩家很容易误进去；
	 *   <li>更糟的是，mod 自己也会把它当成一个新世界，再备份一份到云端去。
	 * </ul>
	 * 实测中这两件事都真的发生了。放到 mod 自己的目录下就都没了，
	 * 存档依然是完整的、随时可以手工挪回去。
	 */
	private Path moveAside(Path worldDir) throws IOException {
		if (!Files.exists(worldDir)) {
			// 本地根本没有这个世界，那就不需要备份，直接让解压去创建它。
			return null;
		}

		Path backupRoot = workDir.resolve(BACKUP_DIRECTORY);
		Files.createDirectories(backupRoot);

		String name = worldDir.getFileName().toString();
		String base = name + ".mccb-backup-" + Timestamps.fileStamp();
		Path target = backupRoot.resolve(base);

		// 同一秒内连续还原两次会撞名，加个后缀避开。
		int suffix = 1;

		while (Files.exists(target)) {
			target = backupRoot.resolve(base + "-" + suffix);
			suffix++;
		}

		return Files.move(worldDir, target);
	}

	private static void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}

		try (var stream = Files.walk(root)) {
			stream.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException e) {
					Log.warn("清理失败：" + path, e);
				}
			});
		}
	}

	private static void deleteQuietly(Path path) {
		if (path == null) {
			return;
		}

		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// 忽略
		}
	}
}
