package dev.mccb.core.archive;

import dev.mccb.core.util.Hashing;
import dev.mccb.core.util.Log;
import dev.mccb.core.util.ProgressListener;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把世界目录打包成 zip，同时算出内容哈希。
 *
 * <p>打包和哈希是同一次遍历里完成的：反正文件内容都要读一遍才能压缩，
 * 顺手喂给 SHA-256 几乎不花额外时间，省掉了"为了算哈希再读一遍整个世界"。
 */
public final class WorldArchiver {

	/**
	 * @param contentHash       只反映"相对路径 + 文件内容"的哈希，与时间戳无关
	 * @param zipSizeBytes      打包后的 zip 大小
	 * @param fileCount         实际写入的文件数
	 * @param uncompressedBytes 原始总字节数
	 */
	public record Result(String contentHash, long zipSizeBytes, int fileCount, long uncompressedBytes) {
	}

	private record Exclude(String raw, Pattern regex, boolean pathScoped) {
	}

	private static final int BUFFER_SIZE = 16 * 1024;

	private WorldArchiver() {
	}

	public static Result archive(Path worldDir, Path outputZip, List<String> excludePatterns,
								 int compressionLevel, ProgressListener progress) throws IOException {
		List<Exclude> excludes = compileExcludes(excludePatterns);
		List<Path> files = collectFiles(worldDir, excludes);

		long totalBytes = 0;

		for (Path file : files) {
			try {
				totalBytes += Files.size(file);
			} catch (IOException e) {
				// 大小读不到不影响打包，进度条会略微不准而已。
			}
		}

		Path parent = outputZip.getParent();

		if (parent != null) {
			Files.createDirectories(parent);
		}

		MessageDigest digest = Hashing.sha256();
		byte[] buffer = new byte[BUFFER_SIZE];
		long processed = 0;
		int written = 0;

		progress.onStage("正在打包世界");

		try (OutputStream fileOut = Files.newOutputStream(outputZip);
			 ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(fileOut, 64 * 1024))) {

			zip.setLevel(compressionLevel);

			for (Path file : files) {
				String entryName = toEntryName(worldDir, file);

				// 先试着打开文件再往 zip 里写。游戏运行期间有文件会被锁住，
				// 如果先 putNextEntry 再打开失败，就会往压缩包里留下一个半截条目。
				InputStream in;

				try {
					in = Files.newInputStream(file);
				} catch (IOException e) {
					Log.warn("跳过无法打开的文件：" + entryName);
					continue;
				}

				ZipEntry entry = new ZipEntry(entryName);

				try {
					entry.setTime(Files.getLastModifiedTime(file).toMillis());
				} catch (IOException ignored) {
					// 拿不到修改时间就用默认值，不影响正确性。
				}

				zip.putNextEntry(entry);

				// 哈希只吃路径和内容，不吃时间戳，也不吃压缩参数。
				// 这样自动保存只刷新了 level.dat 的修改时间而内容没变时，
				// 算出来的哈希依然相同，"内容没变就跳过上传"才准。
				digest.update(entryName.getBytes(StandardCharsets.UTF_8));
				digest.update((byte) 0);

				try (InputStream stream = in) {
					int read;

					while ((read = stream.read(buffer)) != -1) {
						digest.update(buffer, 0, read);
						zip.write(buffer, 0, read);
						processed += read;
						progress.onProgress(processed, totalBytes);
					}
				} catch (IOException e) {
					// 读到一半失败极罕见，记一笔但不让整次备份失败。
					Log.warn("读取文件时出错，该文件在快照中可能不完整：" + entryName, e);
				}

				digest.update((byte) 0);
				zip.closeEntry();
				written++;
			}
		}

		return new Result(Hashing.toHex(digest.digest()), Files.size(outputZip), written, totalBytes);
	}

	/** 遍历世界目录，按相对路径排序后返回所有要打包的文件。 */
	private static List<Path> collectFiles(Path worldDir, List<Exclude> excludes) throws IOException {
		List<Path> files = new ArrayList<>();

		try (Stream<Path> stream = Files.walk(worldDir)) {
			stream.filter(Files::isRegularFile)
					.filter(path -> !isExcluded(worldDir, path, excludes))
					.forEach(files::add);
		}

		// 排序是为了让每次打包的条目顺序一致，否则哈希会随文件系统返回顺序变化。
		files.sort(Comparator.comparing(path -> toEntryName(worldDir, path)));
		return files;
	}

	private static String toEntryName(Path worldDir, Path file) {
		return worldDir.relativize(file).toString().replace('\\', '/');
	}

	private static boolean isExcluded(Path worldDir, Path file, List<Exclude> excludes) {
		if (excludes.isEmpty()) {
			return false;
		}

		String entryName = toEntryName(worldDir, file);
		Path fileName = file.getFileName();
		String leaf = fileName == null ? entryName : fileName.toString();

		for (Exclude exclude : excludes) {
			// 不含 '/' 的规则（比如 session.lock、*.lock）只看文件名，
			// 这样它对目录树的任意层级都生效，符合直觉；
			// 含 '/' 的规则才按整条相对路径匹配。
			String target = exclude.pathScoped() ? entryName : leaf;

			if (exclude.regex().matcher(target).matches()) {
				return true;
			}
		}

		return false;
	}

	private static List<Exclude> compileExcludes(List<String> patterns) {
		List<Exclude> excludes = new ArrayList<>();

		if (patterns == null) {
			return excludes;
		}

		for (String pattern : patterns) {
			String trimmed = pattern == null ? "" : pattern.trim();

			if (trimmed.isEmpty()) {
				continue;
			}

			// 玩家在 Windows 上很自然会用反斜杠写路径，统一成正斜杠再匹配。
			String normalized = trimmed.replace('\\', '/');
			boolean pathScoped = normalized.indexOf('/') >= 0;

			excludes.add(new Exclude(normalized, Pattern.compile(globToRegex(normalized)), pathScoped));
		}

		return excludes;
	}

	/** 把通配符转成正则。只支持 * 和 ?，其余字符一律当成字面量。 */
	private static String globToRegex(String glob) {
		StringBuilder builder = new StringBuilder();

		for (int i = 0; i < glob.length(); i++) {
			char c = glob.charAt(i);

			if (c == '*') {
				builder.append("[^/]*");
			} else if (c == '?') {
				builder.append("[^/]");
			} else {
				if ("\\[](){}.*+?^$|".indexOf(c) >= 0) {
					builder.append('\\');
				}

				builder.append(c);
			}
		}

		return builder.toString();
	}
}
