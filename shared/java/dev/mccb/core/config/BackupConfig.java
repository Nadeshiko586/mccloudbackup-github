package dev.mccb.core.config;

import dev.mccb.core.util.NameSanitizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 配置数据结构。字段直接公开是为了让 Gson 和配置界面都能简单读写。
 *
 * <p>每次从磁盘读取后都会调用 {@link #normalize()}，所以哪怕玩家手改 JSON
 * 把某个字段删了或填了非法值，运行时也不会抛异常。
 */
public final class BackupConfig {

	public boolean enabled = true;

	public WebDav webdav = new WebDav();
	public Triggers triggers = new Triggers();
	public Retention retention = new Retention();
	public Advanced advanced = new Advanced();

	public static final class WebDav {
		/** 例如 https://dav.jianguoyun.com/dav/ */
		public String url = "";
		public String username = "";
		public String password = "";
		/** 云端根目录，会在其中按世界名建子目录。 */
		public String remoteRoot = "mccloudbackup";
		/** 自签证书的私有 WebDAV 才需要打开。 */
		public boolean allowInsecureTls = false;
		/**
		 * 在云端多分一层整合包文件夹。
		 *
		 * <p>不同整合包的存档目录经常重名，而且 mod 列表不同，互相还原基本等于毁档，
		 * 所以默认打开。
		 */
		public boolean groupByModpack = true;
		/** 整合包名。留空表示自动识别（见 ModpackDetector）。 */
		public String modpackName = "";
	}

	public static final class Triggers {
		/** 退出世界时备份。 */
		public boolean onWorldExit = true;
		/** 每次自动保存后备份。默认关闭，因为自动保存很频繁。 */
		public boolean onAutosave = false;
		/** 上一项开启时的最小间隔，防止刷屏式上传。 */
		public int autosaveMinIntervalMinutes = 10;
		/** 按固定间隔备份。 */
		public boolean onInterval = false;
		public int intervalMinutes = 30;
	}

	public static final class Retention {
		/** 每个世界在云端保留的最近快照数量，超出会删除最旧的。 */
		public int maxSnapshotsPerWorld = 10;
	}

	public static final class Advanced {
		/** zip 压缩级别 0-9。0 是不压缩，最快但文件最大。 */
		public int compressionLevel = 6;
		public int timeoutSeconds = 60;
		/** 内容与上一份快照相同时跳过上传。 */
		public boolean skipUnchanged = true;
		/** 相对世界目录的排除规则，支持 * 和 ? 通配符。 */
		public List<String> excludePatterns = defaultExcludes();
	}

	private static List<String> defaultExcludes() {
		// session.lock 是必须排除的：游戏运行期间它一直被锁定读不了，
		// 而且还原时把旧的 session.lock 写回去会导致世界被判定为"正在被占用"。
		return new ArrayList<>(Arrays.asList("session.lock", "*.lock"));
	}

	public void normalize() {
		if (webdav == null) {
			webdav = new WebDav();
		}

		if (triggers == null) {
			triggers = new Triggers();
		}

		if (retention == null) {
			retention = new Retention();
		}

		if (advanced == null) {
			advanced = new Advanced();
		}

		webdav.url = orEmpty(webdav.url).trim();
		webdav.username = orEmpty(webdav.username);
		webdav.password = orEmpty(webdav.password);
		webdav.remoteRoot = orEmpty(webdav.remoteRoot).trim();
		webdav.modpackName = orEmpty(webdav.modpackName).trim();

		if (webdav.remoteRoot.isEmpty()) {
			webdav.remoteRoot = "mccloudbackup";
		}

		triggers.autosaveMinIntervalMinutes = clamp(triggers.autosaveMinIntervalMinutes, 1, 24 * 60);
		triggers.intervalMinutes = clamp(triggers.intervalMinutes, 1, 24 * 60);

		retention.maxSnapshotsPerWorld = clamp(retention.maxSnapshotsPerWorld, 1, 1000);

		advanced.compressionLevel = clamp(advanced.compressionLevel, 0, 9);
		advanced.timeoutSeconds = clamp(advanced.timeoutSeconds, 5, 600);

		if (advanced.excludePatterns == null) {
			advanced.excludePatterns = defaultExcludes();
		}
	}

	/**
	 * 实际使用的云端根目录。
	 *
	 * <p>开启整合包分组时会变成 {@code <remoteRoot>/<整合包名>}，
	 * 关掉时就退回 {@code <remoteRoot>}（旧版本的布局）。
	 *
	 * @param detectedModpack 自动识别出的整合包名，仅在配置里没手填时使用
	 */
	public String effectiveRemoteRoot(String detectedModpack) {
		if (!webdav.groupByModpack) {
			return webdav.remoteRoot;
		}

		// 手填优先。玩家在这里写中文、带空格都行，净化成路径安全的形式即可。
		String pack = NameSanitizer.sanitize(webdav.modpackName);

		if (pack.isEmpty()) {
			pack = NameSanitizer.sanitize(detectedModpack);
		}

		if (pack.isEmpty()) {
			// 连识别出来的名字都不合法，就不要再分一层了，免得建出一堆奇怪目录。
			return webdav.remoteRoot;
		}

		return webdav.remoteRoot + "/" + pack;
	}

	/** 配置是否已经填到可以尝试连接云端的程度。 */
	public boolean isConfigured() {
		return !webdav.url.isEmpty();
	}

	private static String orEmpty(String value) {
		return value == null ? "" : value;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
