package dev.mccb.mc263.client;

import dev.mccb.mc263.client.gui.MainScreen;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 在「单人游戏」界面加一个云存档入口。
 *
 * <p>用 Fabric 的 {@code ScreenEvents.AFTER_INIT} 而不是 Mixin：本工程到目前为止
 * 一个 Mixin 都没有，少一层注入就少一类"游戏一更新就崩"的故障。
 *
 * <p>这个文件踩过三个坑，都记在下面，免得以后有人重蹈：
 * <ol>
 *   <li><b>位置不能猜。</b> 先放左下角，撞上了原版按钮（那排按钮是以屏幕中心为基准
 *       排布的，GUI 缩放一大就左移）；改放左上角，又压到了居中的搜索框。
 *       现在改成读搜索框的实际坐标来定位。</li>
 *   <li><b>窗口尺寸变化会重复触发 AFTER_INIT</b>，而旧按钮不一定被
 *       {@code clearWidgets()} 清掉，于是同一个位置会叠好几颗按钮，
 *       露出的边角看上去就像"多出来一节"。所以每次加之前先摘掉自己的。</li>
 * </ol>
 */
public final class SingleplayerEntry {

	/** 按钮文字。同时也用来在重建界面时认出自己加的那颗。 */
	private static final String LABEL = "云存档";

	/** 按钮首选的宽度；左边空隙更窄时会缩到空隙大小。 */
	private static final int PREFERRED_WIDTH = 100;

	/** 空隙窄于这个值就不放同一行了，退回标题行。 */
	private static final int MIN_USABLE_WIDTH = 56;

	private static final int BUTTON_HEIGHT = 20;

	/** 距屏幕边缘的留白。 */
	private static final int MARGIN = 6;

	/** 和搜索框之间的留白。 */
	private static final int GAP = 4;

	private SingleplayerEntry() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof SelectWorldScreen)) {
				return;
			}

			List<AbstractWidget> widgets = Screens.getWidgets(screen);
			int stale = removeStaleButtons(widgets);

			if (stale > 0) {
				McCloudBackupClient.LOGGER.info(
						"单人游戏界面重建：清理了 {} 个上一轮遗留的云存档按钮", stale);
			}

			widgets.add(createButton(screen, width));
		});
	}

	/**
	 * 摘掉之前 init 留下的同名按钮。
	 *
	 * @return 摘掉了几个
	 */
	private static int removeStaleButtons(List<AbstractWidget> widgets) {
		int removed = 0;

		// 先复制一份再遍历，边遍历边改原列表会抛 ConcurrentModificationException。
		for (AbstractWidget widget : List.copyOf(widgets)) {
			if (isOurButton(widget) && widgets.remove(widget)) {
				removed++;
			}
		}

		return removed;
	}

	private static boolean isOurButton(AbstractWidget widget) {
		// 按文字认人。"云存档"这三个字不可能和原版按钮撞上，
		// 而且比持有引用可靠——界面重建后引用就失效了。
		return widget instanceof Button button && LABEL.equals(button.getMessage().getString());
	}

	private static Button createButton(Screen screen, int screenWidth) {
		EditBox searchBox = findSearchBox(screen);

		int x = MARGIN;
		int y = MARGIN;
		int buttonWidth = PREFERRED_WIDTH;

		if (searchBox != null) {
			// 搜索框是水平居中的，它左边那块是界面上唯一稳定的空地。
			int gutter = searchBox.getX() - MARGIN - GAP;

			if (gutter >= MIN_USABLE_WIDTH) {
				// 和搜索框同一行、同高，宽度正好填满左边的空隙。
				y = searchBox.getY();
				buttonWidth = Math.min(PREFERRED_WIDTH, gutter);
			} else {
				// 空隙太窄（窗口很小，或 GUI 缩放拉得很大），退回标题行左端。
				// 标题是居中的，左边这块通常还有位置。
				buttonWidth = Math.min(PREFERRED_WIDTH,
						Math.max(MIN_USABLE_WIDTH, searchBox.getX() - GAP));
			}
		}

		McCloudBackupClient.LOGGER.info(
				"单人游戏界面布局：搜索框={}，窗口宽={}，云存档按钮=({}, {}) 尺寸 {}x{}",
				searchBox == null ? "未找到" : ("x=" + searchBox.getX() + ",y=" + searchBox.getY()),
				screenWidth, x, y, buttonWidth, BUTTON_HEIGHT);

		return Button.builder(Component.literal(LABEL),
						pressed -> MainScreen.open(new MainScreen(screen)))
				.bounds(x, y, buttonWidth, BUTTON_HEIGHT)
				.build();
	}

	private static EditBox findSearchBox(Screen screen) {
		for (AbstractWidget widget : Screens.getWidgets(screen)) {
			if (widget instanceof EditBox box) {
				return box;
			}
		}

		return null;
	}
}
