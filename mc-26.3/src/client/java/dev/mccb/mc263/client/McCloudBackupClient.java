package dev.mccb.mc263.client;

import dev.mccb.core.backup.BackupScheduler;
import dev.mccb.core.backup.BackupService;
import dev.mccb.core.backup.ProgressTracker;
import dev.mccb.core.backup.RestoreService;
import dev.mccb.core.config.BackupConfig;
import dev.mccb.core.config.ConfigStore;
import dev.mccb.core.platform.GamePlatform;
import dev.mccb.core.platform.ModpackDetector;
import dev.mccb.core.platform.WorldHandle;
import dev.mccb.core.util.Log;
import dev.mccb.mc263.client.platform.FabricGamePlatform;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 模组入口，同时充当各个服务的持有者。
 *
 * <p>所有依赖 Minecraft 的代码都放在 client 源集里。虽然 fabric.mod.json 已经声明
 * {@code "environment": "client"}，但把代码物理上放进 client 源集更保险——
 * 这样万一将来有人想让它跑在服务端，编译期就会直接报错，而不是等到运行时才炸。
 */
public class McCloudBackupClient implements ClientModInitializer {

	public static final String MOD_ID = "mccloudbackup";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** 关游戏时最多等正在进行的备份多久（秒）。 */
	private static final int SHUTDOWN_WAIT_SECONDS = 20;

	private static GamePlatform platform;
	private static ConfigStore configStore;
	private static BackupService backupService;
	private static RestoreService restoreService;
	private static BackupScheduler scheduler;
	private static Path workDir;
	private static String detectedModpack;

	/** 配置缓存。每次备份都读磁盘太浪费，尤其定时触发检查会被每 tick 调用。 */
	private static volatile BackupConfig cachedConfig;

	/**
	 * 当前操作的进度。备份和还原共用一份。
	 *
	 * <p>共用是安全的，因为两者都走同一个单线程 {@link BackupScheduler}，
	 * 永远不会同时进行。
	 */
	private static final ProgressTracker PROGRESS = new ProgressTracker();

	@Override
	public void onInitializeClient() {
		installLogSink();

		platform = new FabricGamePlatform();

		configStore = new ConfigStore(
				FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json"));

		Path gameDir = FabricLoader.getInstance().getGameDir();
		workDir = gameDir.resolve(MOD_ID);
		cleanWorkDirectory();

		// 只识别一次。整合包不会在游戏运行期间换掉，没必要每次备份都去读一遍元数据。
		detectedModpack = ModpackDetector.detect(gameDir);

		backupService = new BackupService(configStore, detectedModpack, workDir);
		restoreService = new RestoreService(platform, configStore, detectedModpack, workDir);
		scheduler = new BackupScheduler();
		cachedConfig = configStore.load();

		BackupEvents.register();
		KeyBindings.register();
		SingleplayerEntry.register();

		LOGGER.info("云存档已加载。整合包：{}，配置：{}，临时目录：{}",
				detectedModpack, configStore.file(), workDir);
	}

	// ------------------------------------------------------------------
	// 日志
	// ------------------------------------------------------------------

	private static void installLogSink() {
		Log.setSink((level, message, error) -> {
			switch (level) {
				case DEBUG -> LOGGER.debug(message);
				case INFO -> LOGGER.info(message);
				case WARN -> {
					if (error != null) {
						LOGGER.warn(message, error);
					} else {
						LOGGER.warn(message);
					}
				}
				case ERROR -> {
					if (error != null) {
						LOGGER.error(message, error);
					} else {
						LOGGER.error(message);
					}
				}
			}
		});
	}

	// ------------------------------------------------------------------
	// 服务访问
	// ------------------------------------------------------------------

	public static BackupService backupService() {
		return backupService;
	}

	public static RestoreService restoreService() {
		return restoreService;
	}

	public static BackupScheduler scheduler() {
		return scheduler;
	}

	public static Path workDir() {
		return workDir;
	}

	/** 当前操作的实时进度，界面每帧读它来画进度条。 */
	public static ProgressTracker progress() {
		return PROGRESS;
	}

	/**
	 * 清理上次运行残留的临时文件。
	 *
	 * <p>正常路径下这些文件用完就删了，但游戏被强杀（任务管理器结束进程、断电、
	 * 或者开发时直接把 runClient 掐掉）就会留下来。实测遇到过一次：一个只下了一半的
	 * 还原包躺在临时目录里，大小看着挺正常，很容易被后续排查误当成"下载完整"。
	 */
	private static void cleanWorkDirectory() {
		if (!Files.isDirectory(workDir)) {
			return;
		}

		int removed = 0;

		try (Stream<Path> stream = Files.list(workDir)) {
			for (Path path : stream.toList()) {
				if (Files.isDirectory(path)) {
					// 子目录放的是还原前自动另存的世界备份，那是玩家的救命稻草，绝不能删。
					continue;
				}

				try {
					if (Files.deleteIfExists(path)) {
						removed++;
					}
				} catch (IOException e) {
					LOGGER.warn("清理临时文件失败：{}", path, e);
				}
			}
		} catch (IOException e) {
			LOGGER.warn("读取临时目录失败：{}", workDir, e);
		}

		if (removed > 0) {
			LOGGER.info("已清理 {} 个上次残留的临时文件", removed);
		}
	}

	/**
	 * 本地存档目录。
	 *
	 * <p>这里假设存档都在 {@code <游戏目录>/saves/<世界目录名>} 下。
	 * 备份方向不受这个假设影响——那份路径是游戏自己通过
	 * {@code MinecraftServer#getWorldPath(LevelResource.ROOT)} 给的，一定是权威的。
	 * 只有"还原到哪个目录"需要这个推断，所以把假设集中在这一处，
	 * 万一 26.x 改了存档布局，也只需要改这一行。
	 */
	public static Path savesDirectory() {
		return platform.gameDirectory().resolve("saves");
	}

	// ------------------------------------------------------------------
	// 配置
	// ------------------------------------------------------------------

	public static BackupConfig config() {
		BackupConfig local = cachedConfig;

		if (local == null) {
			local = configStore.load();
			cachedConfig = local;
		}

		return local;
	}

	/**
	 * 从磁盘读一份全新的配置对象，和缓存里那份互不影响。
	 *
	 * <p>设置界面必须用这个而不是 {@link #config()}：玩家改到一半点了取消，
	 * 缓存里那份不能已经被改脏了。
	 */
	public static BackupConfig loadFreshConfig() {
		return configStore.load();
	}

	public static Path configFile() {
		return configStore.file();
	}

	/** 启动时识别出的整合包名，用作云端的分组文件夹。 */
	public static String detectedModpack() {
		return detectedModpack;
	}

	/** 放弃缓存重新从磁盘读。界面保存后、进世界时各调一次。 */
	public static void reloadConfig() {
		cachedConfig = configStore.load();
	}

	public static void saveConfig(BackupConfig config) {
		configStore.save(config);
		cachedConfig = config;
	}

	// ------------------------------------------------------------------
	// 任务调度
	// ------------------------------------------------------------------

	/** 把一次备份丢到后台。调用方可以是主线程，这里会立刻返回。 */
	public static void submitBackup(WorldHandle world, String reason) {
		if (scheduler.isBusy()) {
			LOGGER.info("已有备份在进行，本次「{}」会排在它后面", reason);
		}

		scheduler.submit(() -> {
			BackupService.Outcome outcome = backupService.backup(world, PROGRESS);

			if (outcome.status() == BackupService.Status.SKIPPED_UNCHANGED) {
				// 内容没变是常态，不用每次都弹提示打扰玩家。
				LOGGER.info("{}：{}", reason, outcome.message());
				return;
			}

			String message = reason + "：" + outcome.message();

			// 退出世界触发的备份，玩家此刻在标题界面，聊天栏是看不见的，
			// 所以额外弹一个系统提示。否则整个过程对玩家完全隐形。
			Toasts.show("云存档", message);
			notifyPlayer(message);
		});
	}

	public static void notifyPlayer(String message) {
		if (platform == null) {
			LOGGER.info(message);
			return;
		}

		// 通过平台实现去发消息，核心层不关心它到底是聊天栏还是别的东西。
		platform.notifyPlayer(message);
	}

	/**
	 * 等待正在进行的备份结束，用于关游戏前的收尾。
	 *
	 * <p>没有这一步的话，"退出世界 → 立刻关游戏"会让上传半路被 JVM 掐死。
	 * 备份线程是守护线程，所以即使这里等超时了，游戏照样能正常退出。
	 */
	public static void awaitPendingWork() {
		if (scheduler == null || !scheduler.isBusy()) {
			return;
		}

		LOGGER.info("还有备份任务未完成，最多等待 {} 秒……", SHUTDOWN_WAIT_SECONDS);

		long deadline = System.currentTimeMillis() + SHUTDOWN_WAIT_SECONDS * 1000L;

		while (scheduler.isBusy() && System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(100L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}

		if (scheduler.isBusy()) {
			LOGGER.warn("等待超时，未完成的备份会被中断。");
		}
	}
}
