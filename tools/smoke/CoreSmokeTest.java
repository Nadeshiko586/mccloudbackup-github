import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mccb.core.backup.BackupService;
import dev.mccb.core.backup.RestoreService;
import dev.mccb.core.config.BackupConfig;
import dev.mccb.core.config.ConfigStore;
import dev.mccb.core.platform.GamePlatform;
import dev.mccb.core.platform.ModpackDetector;
import dev.mccb.core.platform.WorldHandle;
import dev.mccb.core.util.NameSanitizer;
import dev.mccb.core.util.ProgressListener;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * 核心层的端到端冒烟测试。
 *
 * <p>核心层是纯 Java、零 Minecraft 依赖的，所以可以完全脱离游戏验证。
 * 这里在内存里实现了一个最小的 WebDAV 服务器，然后把
 * 「打包 → 上传 → 去重 → 列出 → 还原」整条链路真跑一遍。
 *
 * <p>编译运行方式见 tools/smoke/README.md。
 */
public final class CoreSmokeTest {

	private static int passed;
	private static int failed;

	private static Path gameDir;
	private static Path workDir;

	public static void main(String[] args) throws Exception {
		Path root = Files.createTempDirectory("mccb-smoke");
		gameDir = root.resolve("instance");
		workDir = root.resolve("work");

		Files.createDirectories(gameDir);

		System.out.println("临时目录：" + root);
		System.out.println();

		testNameSanitizer();
		testModpackDetector();
		testWorldArchiver();
		testEndToEnd();

		System.out.println();
		System.out.println("========================================");
		System.out.println("通过 " + passed + "，失败 " + failed);
		System.out.println("========================================");

		if (failed > 0) {
			System.exit(1);
		}
	}

	// ------------------------------------------------------------------
	// 1. 名字净化
	// ------------------------------------------------------------------

	private static void testNameSanitizer() {
		section("名字净化");

		check("中文保留", "我的整合包".equals(NameSanitizer.sanitize("我的整合包")));
		check("空格转连字符", "My-Pack".equals(NameSanitizer.sanitize("My Pack")));
		check("路径分隔符被替换", !NameSanitizer.sanitize("a/b\\c").contains("/"));
		check("点号被拦下", NameSanitizer.sanitize("..").isEmpty());
		check("单点被拦下", NameSanitizer.sanitize(".").isEmpty());
		check("空串返回空", NameSanitizer.sanitize("   ").isEmpty());
		check("超长被截断", NameSanitizer.sanitize("x".repeat(200)).length() <= 64);
		check("null 安全", NameSanitizer.sanitize(null).isEmpty());
	}

	// ------------------------------------------------------------------
	// 2. 整合包识别
	// ------------------------------------------------------------------

	private static void testModpackDetector() throws Exception {
		section("整合包识别");

		// Prism / MultiMC：实例目录下有 instance.cfg
		Path prism = Files.createTempDirectory("prism");
		Files.createDirectories(prism.resolve(".minecraft"));
		Files.writeString(prism.resolve("instance.cfg"), """
				InstanceType=OneSix
				iconKey=default
				name=暮色森林生存
				""");
		check("识别 Prism instance.cfg",
				"暮色森林生存".equals(ModpackDetector.detect(prism.resolve(".minecraft"))));

		// Modrinth App：profile.json
		Path modrinth = Files.createTempDirectory("modrinth");
		Files.createDirectories(modrinth.resolve("minecraft"));
		Files.writeString(modrinth.resolve("profile.json"), "{\"name\":\"Better MC\",\"game_version\":\"26.3\"}");
		check("识别 Modrinth profile.json",
				"Better-MC".equals(ModpackDetector.detect(modrinth.resolve("minecraft"))));

		// CurseForge：minecraftinstance.json
		Path curse = Files.createTempDirectory("curse");
		Files.writeString(curse.resolve("minecraftinstance.json"), "{\"name\":\"All The Mods\"}");
		check("识别 CurseForge minecraftinstance.json",
				"All-The-Mods".equals(ModpackDetector.detect(curse)));

		// 模拟原版启动器的真实布局：%APPDATA%\.minecraft，父目录叫 Roaming。
		// 这种情况上下都找不到有区分度的名字，必须兜底成 default。
		Path appdata = Files.createTempDirectory("appdata");
		Path mc = Files.createDirectories(appdata.resolve("Roaming").resolve(".minecraft"));
		check("原版布局兜底为 default",
				ModpackDetector.DEFAULT_NAME.equals(ModpackDetector.detect(mc)));

		// 坏掉的 JSON 不能让识别炸掉
		Path broken = Files.createTempDirectory("broken");
		Files.writeString(broken.resolve("profile.json"), "{ 这不是合法 JSON");
		String result = ModpackDetector.detect(broken);
		check("坏 JSON 不抛异常", result != null && !result.isEmpty());
	}

	// ------------------------------------------------------------------
	// 3. 打包与哈希
	// ------------------------------------------------------------------

	private static void testWorldArchiver() throws Exception {
		section("打包与去重哈希");

		Path world = Files.createTempDirectory("world");
		Files.writeString(world.resolve("level.dat"), "level-data");
		Files.createDirectories(world.resolve("region"));
		Files.writeString(world.resolve("region/r.0.0.mca"), "chunk-data");
		Files.writeString(world.resolve("session.lock"), "should-be-excluded");

		Path zip1 = Files.createTempFile("a", ".zip");
		Path zip2 = Files.createTempFile("b", ".zip");

		var r1 = dev.mccb.core.archive.WorldArchiver.archive(
				world, zip1, List.of("session.lock", "*.lock"), 6, ProgressListener.NOOP);

		check("session.lock 被排除", r1.fileCount() == 2);
		check("zip 里确实没有 session.lock", !zipContains(zip1, "session.lock"));

		// 只改文件的修改时间、不改内容，哈希必须不变（这正是"自动保存后内容没变"的场景）
		Files.setLastModifiedTime(world.resolve("level.dat"),
				java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000));

		var r2 = dev.mccb.core.archive.WorldArchiver.archive(
				world, zip2, List.of("session.lock", "*.lock"), 6, ProgressListener.NOOP);

		check("只改时间戳时哈希不变", r1.contentHash().equals(r2.contentHash()));

		// 改了内容，哈希必须变
		Files.writeString(world.resolve("level.dat"), "level-data-CHANGED");

		var r3 = dev.mccb.core.archive.WorldArchiver.archive(
				world, zip2, List.of("session.lock", "*.lock"), 6, ProgressListener.NOOP);

		check("内容变化时哈希改变", !r1.contentHash().equals(r3.contentHash()));

		// Zip Slip 防护
		Path evil = Files.createTempFile("evil", ".zip");
		try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(evil))) {
			zos.putNextEntry(new java.util.zip.ZipEntry("../../escaped.txt"));
			zos.write("pwned".getBytes(StandardCharsets.UTF_8));
			zos.closeEntry();
		}
		Path extractDir = Files.createTempDirectory("extract");
		boolean blocked = false;
		try {
			dev.mccb.core.archive.ZipExtractor.extract(evil, extractDir);
		} catch (IOException e) {
			blocked = true;
		}
		check("Zip Slip 被拦截", blocked);
	}

	// ------------------------------------------------------------------
	// 4. 端到端：备份 → 去重 → 浏览 → 还原 → 保留策略
	// ------------------------------------------------------------------

	private static void testEndToEnd() throws Exception {
		section("端到端：备份 / 去重 / 浏览 / 还原 / 保留");

		StubWebDav server = new StubWebDav();
		server.start();
		System.out.println("  内存 WebDAV 已启动：" + server.baseUrl());

		try {
			// 造一个世界
			Path saves = Files.createDirectories(gameDir.resolve("saves"));
			Path worldDir = Files.createDirectories(saves.resolve("测试世界"));
			Files.writeString(worldDir.resolve("level.dat"), "v1");
			Files.createDirectories(worldDir.resolve("region"));
			Files.writeString(worldDir.resolve("region/r.0.0.mca"), "chunk-v1");
			Files.writeString(worldDir.resolve("session.lock"), "locked");

			WorldHandle world = new WorldHandle("测试世界", worldDir);

			// 配置：按整合包分组，手动指定整合包名（中文）
			ConfigStore configStore = new ConfigStore(workDir.resolve("config.json"));
			BackupConfig config = new BackupConfig();
			config.webdav.url = server.baseUrl();
			config.webdav.remoteRoot = "mccloudbackup";
			config.webdav.groupByModpack = true;
			config.webdav.modpackName = "暮色整合包";
			configStore.save(config);

			BackupService backup = new BackupService(configStore, "AutoDetected", workDir);

			// --- 第一次备份 ---
			BackupService.Outcome first = backup.backup(world, ProgressListener.NOOP);
			check("首次备份成功上传", first.status() == BackupService.Status.UPLOADED);
			System.out.println("    -> " + first.message());

			// 验证整合包分组真的生效了
			Set<String> keys = server.allPaths();
			check("云端按整合包分了文件夹",
					keys.stream().anyMatch(k -> k.startsWith("/mccloudbackup/暮色整合包/测试世界/")));
			check("云端路径里不含自动识别的名字",
					keys.stream().noneMatch(k -> k.contains("AutoDetected")));

			// --- 第二次备份：内容没变，应该跳过 ---
			BackupService.Outcome second = backup.backup(world, ProgressListener.NOOP);
			check("内容未变时跳过上传", second.status() == BackupService.Status.SKIPPED_UNCHANGED);

			// --- 改内容，再备份 ---
			Files.writeString(worldDir.resolve("level.dat"), "v2-with-more-stuff");
			BackupService.Outcome third = backup.backup(world, ProgressListener.NOOP);
			check("内容变化后重新上传", third.status() == BackupService.Status.UPLOADED);

			// --- 浏览 ---
			var worlds = backup.listCloudWorlds();
			check("能列出云端世界", worlds.error() == null && worlds.worlds().contains("测试世界"));

			var snapshots = backup.listSnapshots("测试世界");
			check("能列出快照", snapshots.error() == null && snapshots.snapshots().size() == 2);
			check("快照按时间从新到旧",
					snapshots.snapshots().get(0).createdAtEpochMillis
							>= snapshots.snapshots().get(1).createdAtEpochMillis);

			// --- 还原：先把本地世界删掉，模拟"误删后从云端找回" ---
			String target = snapshots.snapshots().get(1).fileName;   // 最旧那份，内容是 v1
			deleteRecursively(worldDir);
			check("本地世界已删除", !Files.exists(worldDir));

			RestoreService restore = new RestoreService(
					new FakePlatform(), configStore, "AutoDetected", workDir);

			RestoreService.Outcome outcome = restore.restore(world, target, ProgressListener.NOOP);
			check("还原成功", outcome.status() == RestoreService.Status.RESTORED);
			System.out.println("    -> " + outcome.message());

			check("本地世界被恢复", Files.isRegularFile(worldDir.resolve("level.dat")));
			check("恢复的内容正确",
					"v1".equals(Files.readString(worldDir.resolve("level.dat"))));
			check("子目录也恢复了", Files.isRegularFile(worldDir.resolve("region/r.0.0.mca")));
			check("session.lock 没有被打包进来",
					!Files.exists(worldDir.resolve("session.lock")));

			// --- 本地已有世界时的还原：回退备份必须挪出 saves/ ---
			RestoreService.Outcome replaced = restore.restore(world, target, ProgressListener.NOOP);
			check("本地已有世界时仍能还原", replaced.status() == RestoreService.Status.RESTORED);
			check("生成了回退备份", replaced.fallbackBackup() != null);

			if (replaced.fallbackBackup() != null) {
				Path fallback = replaced.fallbackBackup().normalize();

				// 实测踩到的坑：就地把世界改名成 <世界名>.mccb-backup-<时间> 的话，
				// Minecraft 会把它当成一个可玩的世界列在单人游戏列表里，
				// 而 mod 自己也会把它当成新世界再备份一份到云端。
				check("回退备份不在 saves/ 内（否则会被当成可玩世界）",
						!fallback.startsWith(saves.normalize()));
				check("回退备份里的存档是完整的",
						Files.isRegularFile(fallback.resolve("level.dat")));
			}

			// --- 保留策略 ---
			config.retention.maxSnapshotsPerWorld = 1;
			configStore.save(config);

			BackupService.Outcome fourth = backup.backup(world, ProgressListener.NOOP);
			check("保留策略下仍能上传", fourth.ok());

			var after = backup.listSnapshots("测试世界");
			check("旧快照被清理，只剩 1 份",
					after.snapshots().size() == 1);

			long zipCount = server.allPaths().stream()
					.filter(k -> k.endsWith(".zip"))
					.count();
			check("云端 zip 文件也被真删掉了", zipCount == 1);

			// --- 认证失败的错误信息 ---
			server.requireAuth = true;
			var authFail = backup.listCloudWorlds();
			server.requireAuth = false;
			check("401 被翻译成可读提示",
					authFail.error() != null && authFail.error().contains("用户名和密码"));

			// --- 瞬时网络故障应被自动重试 ---
			// 这是实测中真实踩到的：一次 ConnectException 让整次备份直接失败了。
			server.dropCount = 1;
			var afterDrop = backup.listSnapshots("测试世界");
			check("网络抖动会被自动重试掉",
					afterDrop.error() == null && !afterDrop.snapshots().isEmpty());

			server.dropCount = 10;
			var persistent = backup.listSnapshots("测试世界");
			server.dropCount = 0;
			check("持续故障最终会失败并给出错误信息",
					persistent.error() != null);
		} finally {
			server.stop();
		}
	}

	// ------------------------------------------------------------------
	// 测试基础设施
	// ------------------------------------------------------------------

	/** 只实现本 mod 用到的那几个 WebDAV 动词的内存服务器。 */
	private static final class StubWebDav {

		private final HttpServer server;
		private final Map<String, byte[]> files = new LinkedHashMap<>();
		private final Set<String> dirs = new TreeSet<>();

		volatile boolean requireAuth;

		/** 让接下来这么多次请求直接断开连接，用来模拟网络抖动。 */
		volatile int dropCount;

		StubWebDav() throws IOException {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			this.server.createContext("/", this::handle);
			dirs.add("/");
		}

		void start() {
			server.start();
		}

		void stop() {
			server.stop(0);
		}

		String baseUrl() {
			return "http://127.0.0.1:" + server.getAddress().getPort();
		}

		Set<String> allPaths() {
			Set<String> all = new TreeSet<>(dirs);
			all.addAll(files.keySet());
			return all;
		}

		private void handle(HttpExchange exchange) throws IOException {
			String path = normalize(exchange.getRequestURI().getPath());

			try {
				if (dropCount > 0) {
					dropCount--;
					// 一个字节都不回直接断开。客户端会拿到 ConnectException / IOException，
					// 这正是我们想模拟的瞬时网络故障。
					exchange.close();
					return;
				}

				if (requireAuth && exchange.getRequestHeaders().getFirst("Authorization") == null) {
					send(exchange, 401, "text/plain", "unauthorized".getBytes(StandardCharsets.UTF_8));
					return;
				}

				switch (exchange.getRequestMethod()) {
					case "MKCOL" -> {
						dirs.add(path.endsWith("/") ? path : path + "/");
						send(exchange, 201, "text/plain", new byte[0]);
					}
					case "PROPFIND" -> propfind(exchange, path);
					case "PUT" -> {
						byte[] body = exchange.getRequestBody().readAllBytes();
						files.put(path, body);
						send(exchange, 201, "text/plain", new byte[0]);
					}
					case "GET" -> {
						byte[] body = files.get(path);
						if (body == null) {
							send(exchange, 404, "text/plain", new byte[0]);
						} else {
							send(exchange, 200, "application/octet-stream", body);
						}
					}
					case "DELETE" -> {
						if (files.remove(path) != null) {
							send(exchange, 204, "text/plain", new byte[0]);
						} else {
							send(exchange, 404, "text/plain", new byte[0]);
						}
					}
					default -> send(exchange, 405, "text/plain", new byte[0]);
				}
			} finally {
				exchange.close();
			}
		}

		private void propfind(HttpExchange exchange, String path) throws IOException {
			String asDir = path.endsWith("/") ? path : path + "/";
			boolean targetIsDir = dirs.contains(asDir);
			byte[] fileBody = files.get(path);

			if (!targetIsDir && fileBody == null) {
				send(exchange, 404, "text/plain", new byte[0]);
				return;
			}

			StringBuilder xml = new StringBuilder();
			xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
			xml.append("<D:multistatus xmlns:D=\"DAV:\">");

			// 目标自身
			appendResponse(xml, asDir, true, 0);

			// Depth: 1 → 附带直接子项
			if (targetIsDir) {
				for (String dir : dirs) {
					if (isDirectChild(asDir, dir)) {
						appendResponse(xml, dir, true, 0);
					}
				}

				for (Map.Entry<String, byte[]> entry : files.entrySet()) {
					if (isDirectChild(asDir, entry.getKey())) {
						appendResponse(xml, entry.getKey(), false, entry.getValue().length);
					}
				}
			}

			xml.append("</D:multistatus>");

			send(exchange, 207, "application/xml; charset=utf-8",
					xml.toString().getBytes(StandardCharsets.UTF_8));
		}

		private static void appendResponse(StringBuilder xml, String href, boolean collection, long size) {
			xml.append("<D:response><D:href>").append(encodePath(href)).append("</D:href>");
			xml.append("<D:propstat><D:prop>");

			if (collection) {
				xml.append("<D:resourcetype><D:collection/></D:resourcetype>");
			} else {
				xml.append("<D:resourcetype/>");
				xml.append("<D:getcontentlength>").append(size).append("</D:getcontentlength>");
			}

			xml.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
		}

		private static boolean isDirectChild(String parentDir, String candidate) {
			if (!candidate.startsWith(parentDir) || candidate.equals(parentDir)) {
				return false;
			}

			String rest = candidate.substring(parentDir.length());

			// 目录项以 / 结尾，去掉后再判断
			if (rest.endsWith("/")) {
				rest = rest.substring(0, rest.length() - 1);
			}

			// 「直接子项」的定义就是剩余部分里不再含 /
			return !rest.isEmpty() && rest.indexOf('/') < 0;
		}

		private static String encodePath(String path) {
			StringBuilder builder = new StringBuilder();

			for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
				char c = (char) (b & 0xFF);

				if (c == '/' || c == '-' || c == '_' || c == '.' || c == '~'
						|| (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
					builder.append(c);
				} else {
					builder.append('%').append(String.format("%02X", b & 0xFF));
				}
			}

			return builder.toString();
		}

		private static String normalize(String raw) {
			String decoded = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);

			while (decoded.length() > 1 && decoded.endsWith("/")) {
				decoded = decoded.substring(0, decoded.length() - 1);
			}

			return decoded;
		}

		private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
				throws IOException {
			if (body.length > 0) {
				exchange.getResponseHeaders().add("Content-Type", contentType);
			}

			if (body.length == 0) {
				exchange.sendResponseHeaders(status, -1);
				return;
			}

			exchange.sendResponseHeaders(status, body.length);

			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		}
	}

	private static final class FakePlatform implements GamePlatform {

		@Override
		public Optional<WorldHandle> currentWorld() {
			// 返回 empty 表示"当前不在任何世界里"，还原时就不会被拒绝。
			return Optional.empty();
		}

		@Override
		public Path gameDirectory() {
			return gameDir;
		}

		@Override
		public void notifyPlayer(String message) {
			System.out.println("    [玩家提示] " + message);
		}
	}

	// ------------------------------------------------------------------
	// 断言辅助
	// ------------------------------------------------------------------

	private static void section(String name) {
		System.out.println("── " + name + " ──");
	}

	private static void check(String description, boolean condition) {
		if (condition) {
			passed++;
			System.out.println("  [通过] " + description);
		} else {
			failed++;
			System.out.println("  [失败] " + description);
		}
	}

	private static boolean zipContains(Path zip, String entryName) throws IOException {
		try (var zis = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
			java.util.zip.ZipEntry entry;

			while ((entry = zis.getNextEntry()) != null) {
				if (entry.getName().equals(entryName)) {
					return true;
				}
			}
		}

		return false;
	}

	private static void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}

		try (Stream<Path> stream = Files.walk(root)) {
			List<Path> paths = new ArrayList<>(stream.sorted(Comparator.reverseOrder()).toList());
			for (Path path : paths) {
				Files.deleteIfExists(path);
			}
		}
	}
}
