package dev.mccb.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.mccb.core.util.Log;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置的读写。存放在 {@code <游戏目录>/config/mccloudbackup.json}。
 *
 * <p>注意：密码目前是明文存在这个 JSON 里的，README 里有说明。
 */
public final class ConfigStore {

	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();

	private final Path file;

	public ConfigStore(Path file) {
		this.file = file;
	}

	public Path file() {
		return file;
	}

	/** 读取配置；文件不存在时会顺手写出一份带默认值的，方便玩家改。 */
	public BackupConfig load() {
		if (!Files.isRegularFile(file)) {
			BackupConfig fresh = new BackupConfig();
			save(fresh);
			return fresh;
		}

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			BackupConfig config = GSON.fromJson(reader, BackupConfig.class);

			if (config == null) {
				// 空文件，当作全新配置处理。
				return new BackupConfig();
			}

			config.normalize();
			return config;
		} catch (Exception e) {
			// 配置坏了不能让游戏起不来，退回默认值并把坏文件留在原地供排查。
			Log.error("读取配置失败，本次将使用默认配置：" + file, e);
			return new BackupConfig();
		}
	}

	public void save(BackupConfig config) {
		config.normalize();

		try {
			Path parent = file.getParent();

			if (parent != null) {
				Files.createDirectories(parent);
			}

			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(config, writer);
			}
		} catch (IOException e) {
			Log.error("写入配置失败：" + file, e);
		}
	}
}
