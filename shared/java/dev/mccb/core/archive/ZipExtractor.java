package dev.mccb.core.archive;

import dev.mccb.core.util.ProgressListener;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ZipExtractor {

	private static final int BUFFER_SIZE = 16 * 1024;

	private ZipExtractor() {
	}

	public static void extract(Path zipFile, Path targetDir) throws IOException {
		extract(zipFile, targetDir, ProgressListener.NOOP);
	}

	/**
	 * 把 zip 解压到目标目录。
	 *
	 * @throws IOException 压缩包内含越界路径，或 IO 失败
	 */
	public static void extract(Path zipFile, Path targetDir, ProgressListener progress) throws IOException {
		Files.createDirectories(targetDir);

		Path root = targetDir.toAbsolutePath().normalize();
		byte[] buffer = new byte[BUFFER_SIZE];

		// 进度按"已解压的条目数 / 总条目数"来算。
		// 预先数一遍要多读一次压缩包，但存档包一般就几十 MB，这点开销换来
		// 一个能动的进度条很值——不然界面在大存档上会一动不动好几十秒。
		int totalEntries = Math.max(1, countEntries(zipFile));
		int doneEntries = 0;

		progress.onStage("正在解压快照");
		progress.onProgress(0, totalEntries);

		try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFile)))) {
			ZipEntry entry;

			while ((entry = zip.getNextEntry()) != null) {
				Path resolved = root.resolve(entry.getName()).normalize();

				// Zip Slip 防护。压缩包里的条目名可能是 "../../.bashrc" 这类路径，
				// 直接 resolve 会写到目标目录外面去。必须确认解析后仍在目标目录内。
				// 这个压缩包虽然是自家 mod 传上去的，但云端内容有可能被人篡改，
				// 所以在解压这一步做校验，而不是假设它一定干净。
				if (!resolved.startsWith(root)) {
					throw new IOException("压缩包内含有越界路径，已中止解压：" + entry.getName());
				}

				if (entry.isDirectory()) {
					Files.createDirectories(resolved);
					continue;
				}

				Path parent = resolved.getParent();

				if (parent != null) {
					Files.createDirectories(parent);
				}

				try (OutputStream out = Files.newOutputStream(resolved)) {
					int read;

					while ((read = zip.read(buffer)) != -1) {
						out.write(buffer, 0, read);
					}
				}

				doneEntries++;
				progress.onProgress(doneEntries, totalEntries);
			}
		}
	}

	/** 粗略统计压缩包里有多少个文件，用于进度显示和空包检查。 */
	public static int countEntries(Path zipFile) throws IOException {
		int count = 0;

		try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFile)))) {
			ZipEntry entry;

			while ((entry = zip.getNextEntry()) != null) {
				if (!entry.isDirectory()) {
					count++;
				}
			}
		}

		return count;
	}
}
