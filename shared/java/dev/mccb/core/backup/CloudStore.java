package dev.mccb.core.backup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.mccb.core.snapshot.SnapshotIndex;
import dev.mccb.core.snapshot.SnapshotMeta;
import dev.mccb.core.util.Log;
import dev.mccb.core.util.ProgressListener;
import dev.mccb.core.webdav.RemoteEntry;
import dev.mccb.core.webdav.WebDavClient;
import dev.mccb.core.webdav.WebDavException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 云端存储的布局和读写。
 *
 * <pre>
 * &lt;remoteRoot&gt;/
 *   &lt;世界目录名&gt;/
 *     20261001-143012-a1b2c3d4.zip
 *     manifest.json
 * </pre>
 *
 * <p>写入顺序是先传 zip、最后传 manifest，这样 manifest 里出现的每一条快照
 * 都保证已经在云上了。反过来的话，中途失败会留下一个"清单说有、实际没有"的幽灵条目。
 */
public final class CloudStore {

	public static final String MANIFEST_NAME = "manifest.json";

	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();

	private final WebDavClient client;
	private final String remoteRoot;
	private final Path workDir;

	public CloudStore(WebDavClient client, String remoteRoot, Path workDir) {
		this.client = client;
		this.remoteRoot = remoteRoot;
		this.workDir = workDir;
	}

	public String remoteDir(String worldId) {
		return remoteRoot + "/" + worldId;
	}

	/** 列出云端已有的世界目录。根目录不存在时返回空列表而不是报错。 */
	public List<String> listWorlds() throws WebDavException {
		List<RemoteEntry> entries;

		try {
			entries = client.list(remoteRoot);
		} catch (WebDavException e) {
			if (e.statusCode() == 404) {
				return new ArrayList<>();
			}

			throw e;
		}

		List<String> worlds = new ArrayList<>();
		int looseFiles = 0;

		for (RemoteEntry entry : entries) {
			if (entry.collection()) {
				worlds.add(entry.name());
			} else {
				looseFiles++;
			}
		}

		// 「云端明明有文件，列表却是空的」是极难排查的一类问题——界面不会报错，
		// 只是什么都不显示。所以宁可多打一行日志，把线索直接摆出来。
		if (worlds.isEmpty() && looseFiles > 0) {
			Log.warn("云端目录 " + remoteRoot + " 下有 " + looseFiles + " 个文件，但一个子目录都没有。"
					+ "这通常意味着存档是按旧布局传上去的（少了「世界名」那一层），"
					+ "或者上传时没能解析出正确的世界名。");
		}

		worlds.sort(Comparator.naturalOrder());
		return worlds;
	}

	public void ensureWorldDir(String worldId) throws WebDavException {
		client.ensureDirectory(remoteDir(worldId));
	}

	/** 读取世界的快照清单。不存在或损坏时返回一份空清单。 */
	public SnapshotIndex loadIndex(String worldId) throws WebDavException {
		Path temp = manifestTempFile(worldId);

		try {
			// BodyHandlers.ofFile 要求父目录已经存在，否则会直接抛 IOException。
			// 界面路径（不经过 backup()）也会调到这里，所以必须自己保证。
			Files.createDirectories(workDir);
		} catch (IOException e) {
			throw new WebDavException("无法创建临时目录：" + workDir, e);
		}

		try {
			client.get(remoteDir(worldId) + "/" + MANIFEST_NAME, temp, ProgressListener.NOOP);
		} catch (WebDavException e) {
			if (e.statusCode() == 404) {
				// 首次备份，还没有清单，属于正常情况。
				return emptyIndex(worldId);
			}

			throw e;
		}

		try (Reader reader = Files.newBufferedReader(temp, StandardCharsets.UTF_8)) {
			SnapshotIndex index = GSON.fromJson(reader, SnapshotIndex.class);

			if (index == null) {
				return emptyIndex(worldId);
			}

			index.world = worldId;

			if (index.snapshots == null) {
				index.snapshots = new ArrayList<>();
			}

			sortNewestFirst(index);
			return index;
		} catch (IOException e) {
			Log.warn("云端清单无法解析，将当作空清单处理：" + worldId, e);
			return emptyIndex(worldId);
		} finally {
			deleteQuietly(temp);
		}
	}

	public void saveIndex(String worldId, SnapshotIndex index) throws WebDavException {
		Path temp = manifestTempFile(worldId);

		try {
			Files.createDirectories(workDir);

			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(index, writer);
			}

			// 清单文件只有几 KB，给它做进度条没有意义，直接静默上传。
			client.put(remoteDir(worldId) + "/" + MANIFEST_NAME, temp, ProgressListener.NOOP);
		} catch (IOException e) {
			throw new WebDavException("写入云端清单失败", e);
		} finally {
			deleteQuietly(temp);
		}
	}

	public void upload(String worldId, String fileName, Path localFile, ProgressListener progress)
			throws WebDavException {
		client.put(remoteDir(worldId) + "/" + fileName, localFile, progress);
	}

	public void download(String worldId, String fileName, Path destination, ProgressListener progress)
			throws WebDavException {
		client.get(remoteDir(worldId) + "/" + fileName, destination, progress);
	}

	public void delete(String worldId, String fileName) throws WebDavException {
		client.delete(remoteDir(worldId) + "/" + fileName);
	}

	private SnapshotIndex emptyIndex(String worldId) {
		SnapshotIndex index = new SnapshotIndex();
		index.world = worldId;
		return index;
	}

	/**
	 * 按时间从新到旧排序。
	 *
	 * <p>不信任清单里的顺序：文件可能被别的设备或人工改动过，
	 * 而"保留最近 N 份"这个逻辑完全依赖顺序正确。
	 */
	private static void sortNewestFirst(SnapshotIndex index) {
		index.snapshots.sort(Comparator.comparingLong((SnapshotMeta meta) -> meta.createdAtEpochMillis).reversed());
	}

	private Path manifestTempFile(String worldId) {
		return workDir.resolve("manifest-" + worldId + ".json");
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// 临时文件清理失败无所谓，下次会覆盖。
		}
	}
}
