package dev.mccb.mc263.client.gui;

import dev.mccb.core.platform.WorldHandle;
import dev.mccb.mc263.client.BackupEvents;
import dev.mccb.mc263.client.McCloudBackupClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 模组的中心页，Mod Menu 打开的就是这一屏。
 *
 * <p>之所以不直接把 Cloth Config 生成的设置界面挂给 Mod Menu：那样就没有地方
 * 放「浏览云端快照」的入口了——Cloth Config 的配置界面是纯表单，
 * 加自定义按钮要依赖它的扩展 API。用一个自己的中心页把设置、还原、手动备份
 * 三件事并排放，就不会被第三方库的接口限制住。
 */
public class MainScreen extends Screen {

	private static final int WIDTH = 308;
	private static final int BUTTON_HEIGHT = 20;
	private static final int SPACING = 26;

	private final Screen parent;

	/** 手动备份按钮。备份进行中要把它置灰并改文字，所以得留个引用。 */
	private Button backupButton;

	/** 上一次渲染时看到的后台忙闲状态，用来判断按钮要不要刷。 */
	private boolean busy;

	public MainScreen(Screen parent) {
		super(Component.literal("云存档"));
		this.parent = parent;
	}

	public static void open(Screen screen) {
		Minecraft.getInstance().gui.setScreen(screen);
	}

	@Override
	protected void init() {
		super.init();

		int x = (this.width - WIDTH) / 2;
		int y = this.height / 2 - 52;

		addButton("设置", x, y, () -> open(ConfigScreen.create(this)));
		addButton("浏览云端快照 / 还原", x, y + SPACING, () -> open(new RestoreScreen(this)));

		backupButton = Button.builder(Component.literal(""), pressed -> startBackup())
				.bounds(x, y + SPACING * 2, WIDTH, BUTTON_HEIGHT)
				.build();

		addRenderableWidget(backupButton);
		addButton("完成", x, y + SPACING * 3, () -> open(parent));

		syncBackupButton();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		// 后台任务的忙闲只有调度器知道。这里每帧同步一次按钮状态，
		// 而不是去调 setScreen 重建整个界面——那样既没必要，又容易踩到重入问题。
		syncBackupButton();

		if (busy) {
			ProgressBar.draw(graphics, this.width / 2, this.height / 2 + 70, WIDTH);
		}
	}

	private void syncBackupButton() {
		if (backupButton == null) {
			return;
		}

		busy = McCloudBackupClient.scheduler().isBusy();
		WorldHandle active = BackupEvents.activeWorld();

		backupButton.active = active != null && !busy;

		if (active == null) {
			backupButton.setMessage(Component.literal("（当前不在世界中，无法手动备份）"));
		} else if (busy) {
			backupButton.setMessage(Component.literal("正在备份……"));
		} else {
			backupButton.setMessage(Component.literal("立即备份「" + active.id() + "」"));
		}
	}

	private void startBackup() {
		WorldHandle active = BackupEvents.activeWorld();

		if (active == null) {
			return;
		}

		McCloudBackupClient.progress().reset();
		McCloudBackupClient.submitBackup(active, "手动备份");

		// 刻意不关界面：留在这里才能看到进度条。
		// 备份完成后会通过聊天栏提示结果。
		syncBackupButton();
	}

	private void addButton(String label, int x, int y, Runnable action) {
		addRenderableWidget(Button.builder(Component.literal(label), pressed -> action.run())
				.bounds(x, y, WIDTH, BUTTON_HEIGHT)
				.build());
	}
}
