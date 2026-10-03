package dev.mccb.mc263.client;

import dev.mccb.core.config.BackupConfig;
import dev.mccb.core.platform.WorldHandle;
import dev.mccb.mc263.client.gui.MainScreen;
import dev.mccb.mc263.client.gui.RestoreScreen;
import dev.mccb.mc263.client.platform.FabricGamePlatform;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;

/**
 * 触发时机的接线。
 *
 * <p>用 Fabric API 的事件而不是 Mixin：事件名是稳定的公开 API，
 * 而 Mixin 注入点每次游戏更新都可能变。少一层 Mixin，就少一类"游戏一更新就崩"的故障。
 */
public final class BackupEvents {

	/** 当前正在玩的世界。退出世界时要靠它，因为那时服务端实例已经没了。 */
	private static volatile WorldHandle activeWorld;

	private static volatile long lastAutosaveBackupAt;
	private static volatile long lastIntervalBackupAt;

	/** 进度提示的节流计数器。 */
	private static int toastTick;

	private BackupEvents() {
	}

	/** 当前正在游玩的世界；不在世界里时为 null。界面拿来判断能否手动备份。 */
	public static WorldHandle activeWorld() {
		return activeWorld;
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			activeWorld = FabricGamePlatform.resolveWorld(server).orElse(null);
			McCloudBackupClient.reloadConfig();

			long now = System.currentTimeMillis();
			lastAutosaveBackupAt = now;
			lastIntervalBackupAt = now;

			if (activeWorld != null) {
				McCloudBackupClient.LOGGER.info("进入世界：{}", activeWorld.id());
			}
		});

		// AFTER_SAVE 的回调是 (server, flush, force) 三个参数。
		// 这个回调在主线程上，必须立刻返回，所以真正的活在后台线程干。
		ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> onAfterSave(server));

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> onServerStopped());

		ClientTickEvents.END_CLIENT_TICK.register(client -> onClientTick());

		// 关游戏前给后台任务一点时间收尾，否则"退出世界后马上关游戏"会丢备份。
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> McCloudBackupClient.awaitPendingWork());
	}

	private static void onAfterSave(MinecraftServer server) {
		BackupConfig config = McCloudBackupClient.config();

		if (!config.enabled || !config.triggers.onAutosave) {
			return;
		}

		WorldHandle world = activeWorld;

		if (world == null) {
			// 极少数情况下 SERVER_STARTED 没抓到（例如玩家用了别的模组加的世界切换），这里补一次。
			Optional<WorldHandle> resolved = FabricGamePlatform.resolveWorld(server);

			if (resolved.isEmpty()) {
				return;
			}

			world = resolved.get();
			activeWorld = world;
		}

		long now = System.currentTimeMillis();
		long minInterval = config.triggers.autosaveMinIntervalMinutes * 60_000L;

		if (now - lastAutosaveBackupAt < minInterval) {
			return;
		}

		lastAutosaveBackupAt = now;
		McCloudBackupClient.submitBackup(world, "自动保存");
	}

	private static void onServerStopped() {
		WorldHandle world = activeWorld;
		activeWorld = null;

		if (world == null) {
			return;
		}

		BackupConfig config = McCloudBackupClient.config();

		if (!config.enabled || !config.triggers.onWorldExit) {
			return;
		}

		McCloudBackupClient.submitBackup(world, "退出世界");
	}

	private static void onClientTick() {
		updateProgressToast();

		BackupConfig config = McCloudBackupClient.config();

		if (!config.enabled || !config.triggers.onInterval) {
			return;
		}

		WorldHandle world = activeWorld;

		if (world == null) {
			return;
		}

		long now = System.currentTimeMillis();
		long interval = config.triggers.intervalMinutes * 60_000L;

		if (now - lastIntervalBackupAt < interval) {
			return;
		}

		lastIntervalBackupAt = now;
		McCloudBackupClient.submitBackup(world, "定时备份");
	}

	/**
	 * 备份进行中时，在标题界面弹一个会实时更新的系统提示。
	 *
	 * <p>这是为了解决实际反馈里的问题：退出世界触发的自动备份跑在标题界面上，
	 * 那里既没有聊天栏也没有界面内的进度条，玩家完全不知道有事在跑，
	 * 很容易直接关游戏。
	 */
	private static void updateProgressToast() {
		if (!McCloudBackupClient.scheduler().isBusy()) {
			return;
		}

		Screen screen = Minecraft.getInstance().gui.screen();

		// 我们自己的界面里已经有进度条了，再弹一个提示纯属重复。
		if (screen instanceof MainScreen || screen instanceof RestoreScreen) {
			return;
		}

		// 大约每半秒刷新一次。再密没有意义，反而会让提示不断闪烁。
		if (++toastTick % 10 != 0) {
			return;
		}

		// 用精简版：系统提示很窄，带上字节数会把 "9.9 MB" 从中间折断。
		// 括号里这句是给玩家看的——备份没传完就关游戏虽然不会立刻出问题
		//（退出时会等），但会让人以为卡住了然后强杀进程。
		Toasts.show("云存档", McCloudBackupClient.progress().describeShort() + "（请勿关闭游戏）");
	}
}
