package dev.mccb.mc263.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * 右上角的系统提示。
 *
 * <p>为什么必须要有它：退出世界会触发的自动备份，此时玩家已经回到标题界面，
 * <b>看不到聊天栏，也看不到任何界面内的进度条</b>。备份就这样隐形地跑着，
 * 玩家很可能觉得没事干就直接关游戏了——这正是实际反馈里提到的问题。
 * 系统提示是标题界面上唯一能显示动态信息的地方。
 */
public final class Toasts {

	/**
	 * 所有提示共用同一个 id。
	 *
	 * <p>{@code addOrUpdate} 对同一个 id 是"替换"而不是"追加"，
	 * 所以反复刷新进度不会堆出一列提示来。
	 */
	private static final SystemToast.SystemToastId ID = new SystemToast.SystemToastId();

	private Toasts() {
	}

	/**
	 * 弹一条提示。可以从任意线程调用。
	 */
	public static void show(String title, String description) {
		Minecraft client = Minecraft.getInstance();

		// 必须回到客户端线程。
		//
		// ToastManager 会去碰字体和渲染状态，从后台线程调用会直接抛
		// "Rendersystem called from wrong thread"。备份完成的那条提示就是从
		// worker 线程发出来的——实测踩过这个坑：提示静默失败，玩家什么都看不到，
		// 只在日志里留下一行 warn。
		client.execute(() -> showOnClientThread(client, title, description));
	}

	private static void showOnClientThread(Minecraft client, String title, String description) {
		try {
			// 注意 toastManager() 挂在 Gui 上，不是 Minecraft 上。
			// Minecraft 本身没有任何 toast 相关的方法。
			SystemToast.addOrUpdate(client.gui.toastManager(), ID,
					Component.literal(title), Component.literal(description));
		} catch (Throwable t) {
			// 提示显示失败绝不能影响到备份本身。
			McCloudBackupClient.LOGGER.warn("显示系统提示失败", t);
		}
	}
}
