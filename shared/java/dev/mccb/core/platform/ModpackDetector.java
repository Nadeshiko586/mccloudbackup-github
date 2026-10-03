package dev.mccb.core.platform;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mccb.core.util.Log;
import dev.mccb.core.util.NameSanitizer;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 识别当前整合包（游戏实例）的名字，用来在云端分文件夹。
 *
 * <p>为什么需要这个：不同整合包的存档目录经常重名（新建世界默认就叫 {@code New World}），
 * 混在同一个云端目录里会互相覆盖。而且整合包的 mod 列表不同，把 A 整合包的存档
 * 还原进 B 整合包基本等于毁档。按整合包分一层，两个问题一起解决。
 *
 * <p>识别方式是读各家启动器写在实例目录里的元数据文件。<b>识别不出来也不算失败</b>——
 * 会依次退回到「用文件夹名」和 {@code default}，玩家还可以在设置里直接手填。
 */
public final class ModpackDetector {

	/** 什么线索都没有时的兜底名字。 */
	public static final String DEFAULT_NAME = "default";

	/** 从游戏目录往上找几层。 */
	private static final int MAX_ANCESTOR_DEPTH = 3;

	/** 单个元数据文件超过这个大小就不读了，正常都是几 KB。 */
	private static final long MAX_METADATA_BYTES = 1024L * 1024L;

	private static final Gson GSON = new Gson();

	/**
	 * 这些目录名没有任何区分度，如果往上找只找到这类名字，说明没找到真正的实例根目录。
	 */
	private static final Set<String> GENERIC_FOLDERS = new HashSet<>(Arrays.asList(
			".minecraft", "minecraft", "roaming", "appdata", "local", "locallow",
			"application data", "users", "program files", "program files (x86)",
			"documents", "desktop", "downloads", "home", "instances", "profiles",
			"multimc", "prismlauncher", "poly mc", "curseforge", "modrinthapp",
			"gdlauncher", "atlauncher", "technic", "twitch", "default"));

	private ModpackDetector() {
	}

	/** 识别整合包名。永远返回一个非空的、已净化的名字。 */
	public static String detect(Path gameDirectory) {
		Path candidate = gameDirectory;

		for (int depth = 0; depth <= MAX_ANCESTOR_DEPTH && candidate != null; depth++) {
			String found = fromMetadata(candidate);
			String sanitized = NameSanitizer.sanitize(found);

			if (!sanitized.isEmpty()) {
				Log.info("识别到整合包名称「" + sanitized + "」（来自 " + candidate + "）");
				return sanitized;
			}

			candidate = candidate.getParent();
		}

		String fallback = NameSanitizer.sanitize(fallbackName(gameDirectory));

		if (fallback.isEmpty()) {
			fallback = DEFAULT_NAME;
		}

		Log.info("没能从启动器元数据识别出整合包，改用「" + fallback
				+ "」作为云端分组名。如果不对，可以在设置里手动指定。");

		return fallback;
	}

	/**
	 * 依次尝试各家启动器的元数据文件。
	 *
	 * <p>顺序无所谓，因为正常情况下只会命中一个：一个实例不会同时是 Prism 和 CurseForge 实例。
	 */
	private static String fromMetadata(Path directory) {
		// Prism Launcher / MultiMC / PolyMC
		String prism = readIniValue(directory.resolve("instance.cfg"), "name");

		if (prism != null) {
			return prism;
		}

		// Modrinth App
		String modrinth = readJsonString(directory.resolve("profile.json"), "name");

		if (modrinth != null) {
			return modrinth;
		}

		// CurseForge
		return readJsonString(directory.resolve("minecraftinstance.json"), "name");
	}

	/** 读 key=value 形式的配置文件。Prism 系的 instance.cfg 就是这种格式。 */
	private static String readIniValue(Path file, String key) {
		if (!isReadable(file)) {
			return null;
		}

		try {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				String trimmed = line.trim();

				if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
					continue;
				}

				int separator = trimmed.indexOf('=');

				if (separator <= 0) {
					continue;
				}

				if (trimmed.substring(0, separator).trim().equalsIgnoreCase(key)) {
					return trimmed.substring(separator + 1).trim();
				}
			}
		} catch (IOException e) {
			Log.debug("读取 " + file + " 失败：" + e.getMessage());
		}

		return null;
	}

	/** 从 JSON 文件里取一个字符串字段（只取顶层）。 */
	private static String readJsonString(Path file, String key) {
		if (!isReadable(file)) {
			return null;
		}

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject object = GSON.fromJson(reader, JsonObject.class);

			if (object == null) {
				return null;
			}

			JsonElement value = object.get(key);

			if (value == null || !value.isJsonPrimitive()) {
				return null;
			}

			return value.getAsString();
		} catch (Exception e) {
			// 文件存在但不是我们期待的 JSON，属于正常情况（比如 profile.json 是别的含义）。
			Log.debug("解析 " + file + " 失败：" + e.getMessage());
			return null;
		}
	}

	private static boolean isReadable(Path file) {
		try {
			return Files.isRegularFile(file) && Files.size(file) <= MAX_METADATA_BYTES;
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * 元数据都没找到时的兜底：拿文件夹名凑一个。
	 *
	 * <p>游戏目录本身通常就叫 {@code .minecraft}，毫无区分度，所以再往上看一层。
	 * 如果上一层也是 {@code Roaming} 这种通用目录名，就放弃。
	 */
	private static String fallbackName(Path gameDirectory) {
		Path fileName = gameDirectory.getFileName();

		if (fileName != null && !isGeneric(fileName.toString())) {
			return fileName.toString();
		}

		Path parent = gameDirectory.getParent();

		if (parent != null && parent.getFileName() != null) {
			String name = parent.getFileName().toString();

			if (!isGeneric(name)) {
				return name;
			}
		}

		return DEFAULT_NAME;
	}

	private static boolean isGeneric(String name) {
		return GENERIC_FOLDERS.contains(name.toLowerCase(Locale.ROOT));
	}
}
