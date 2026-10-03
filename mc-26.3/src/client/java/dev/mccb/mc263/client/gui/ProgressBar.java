package dev.mccb.mc263.client.gui;

import dev.mccb.core.backup.ProgressTracker;
import dev.mccb.mc263.client.McCloudBackupClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 备份 / 还原的进度条。
 *
 * <p>画法是每帧从 {@link ProgressTracker} 现读当前值，而不是让后台线程推着界面更新。
 * 这样界面和后台之间只有几个 volatile 字段的读写，不需要跨线程通知，
 * 也就不会因为进度回调太频繁而把主线程的任务队列塞爆。
 *
 * <p>26.3 的渲染入口是 {@code Screen#extractRenderState}，画矩形和文字分别用
 * {@code GuiGraphicsExtractor#fill} 和 {@code #centeredText}。
 */
public final class ProgressBar {

	private static final int HEIGHT = 12;

	private static final int COLOR_BORDER = 0xFF000000;
	private static final int COLOR_TRACK = 0xFF1E1E1E;
	private static final int COLOR_FILL = 0xFF3CB043;
	private static final int COLOR_TEXT = 0xFFFFFFFF;

	private ProgressBar() {
	}

	/**
	 * 画一条进度条，以及它上方的一行说明文字。
	 *
	 * @param centerX 水平中心点
	 * @param top     进度条顶边的 y 坐标
	 * @param width   进度条宽度
	 */
	public static void draw(GuiGraphicsExtractor graphics, int centerX, int top, int width) {
		ProgressTracker tracker = McCloudBackupClient.progress();
		Font font = Minecraft.getInstance().font;

		int left = centerX - width / 2;
		int right = centerX + width / 2;

		// fill 的参数顺序是 (minX, minY, maxX, maxY, 颜色)。
		graphics.fill(left, top, right, top + HEIGHT, COLOR_BORDER);
		graphics.fill(left + 1, top + 1, right - 1, top + HEIGHT - 1, COLOR_TRACK);

		double fraction = tracker.fraction();

		if (fraction >= 0) {
			int filled = (int) Math.round((width - 2) * fraction);

			if (filled > 0) {
				graphics.fill(left + 1, top + 1, left + 1 + filled, top + HEIGHT - 1, COLOR_FILL);
			}
		}

		// 文字措辞由 ProgressTracker 提供，和聊天提示共用同一份，避免两处说法不一致。
		graphics.centeredText(font, Component.literal(tracker.describe()),
				centerX, top - 12, COLOR_TEXT);
	}
}
