package dev.mccb.mc263.client.gui;

import dev.mccb.core.backup.BackupService;
import dev.mccb.core.backup.RestoreService;
import dev.mccb.core.platform.WorldHandle;
import dev.mccb.core.snapshot.SnapshotMeta;
import dev.mccb.core.util.HumanSize;
import dev.mccb.core.util.Timestamps;
import dev.mccb.mc263.client.BackupEvents;
import dev.mccb.mc263.client.McCloudBackupClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 浏览云端快照并还原。
 *
 * <p><b>这个界面刻意只用按钮，一行自绘代码都没有。</b> 26.3 的渲染管线改动非常大
 * （{@code Screen#render} 被 {@code extractRenderState} 取代，{@code GuiGraphics}
 * 换成了 {@code GuiGraphicsExtractor}），自己画文字和背景是最容易写错的部分。
 * 把信息全放进按钮文字里就能完全绕开这套 API，代价是快照列表分页显示而非连续滚动，
 * 状态栏也用一个禁用按钮来充当。
 *
 * <p>网络请求全部在后台线程跑，结果回到主线程后才重建控件，不会卡住游戏。
 */
public class RestoreScreen extends Screen {

	private static final int MAX_ROWS = 6;
	private static final int ROW_HEIGHT = 22;
	private static final int ROWS_TOP = 34;
	private static final int STATUS_Y = 10;
	private static final int WIDTH = 308;
	private static final int BUTTON_HEIGHT = 20;

	private final Screen parent;

	private List<String> worlds = new ArrayList<>();
	private List<SnapshotMeta> snapshots = new ArrayList<>();

	/** 当前展开的世界；为 null 表示正在看世界列表。 */
	private String selectedWorld;

	private int page;
	private boolean busy;
	private String status = "正在读取云端列表……";

	/** 已点过一次、等待二次确认的快照文件名。 */
	private String pendingRestore;

	/** 首次打开时自动拉一次列表，之后由玩家手动刷新。 */
	private boolean loaded;

	/**
	 * 当前是否还显示在屏幕上。
	 *
	 * <p>异步任务回来时玩家可能早就返回上一层了，这时候再去 setScreen 会把人硬拽回来。
	 * 靠 init()/removed() 这对生命周期回调来维护：setScreen 的顺序是
	 * 「旧屏 removed() → 新屏 init()」，所以 reinit() 自己触发的那轮也依然是 true。
	 */
	private boolean alive;

	public RestoreScreen(Screen parent) {
		// 标题交给父类。原版 Screen 会负责画它，我们不用管。
		super(Component.literal("云存档 · 浏览云端快照"));
		this.parent = parent;
	}

	@Override
	public void removed() {
		alive = false;
		super.removed();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		// 每帧现读进度，所以不需要任何定时刷新。
		// 忙的时候列表是空的，中间这块正好空出来给进度条。
		if (busy) {
			ProgressBar.draw(graphics, this.width / 2, ROWS_TOP + 60, WIDTH);
		}
	}

	@Override
	protected void init() {
		super.init();

		alive = true;

		addStatusBar();

		if (selectedWorld == null) {
			initWorldList();
		} else {
			initSnapshotList();
		}

		if (!loaded) {
			loaded = true;

			// 必须延到下一 tick 再拉数据。loadWorlds() 会立刻调 reinit()，
			// 而 reinit() 是 setScreen(this)——在 init() 里重入 setScreen 会出问题。
			runOnClient(this::loadWorlds);
		}
	}

	// ------------------------------------------------------------------
	// 各状态下的控件
	// ------------------------------------------------------------------

	private void initWorldList() {
		if (!busy) {
			int totalPages = pageCount(worlds.size());

			for (int row = 0; row < MAX_ROWS; row++) {
				int index = page * MAX_ROWS + row;

				if (index >= worlds.size()) {
					break;
				}

				String world = worlds.get(index);
				addButton(world, rowY(row), () -> openWorld(world));
			}

			if (worlds.isEmpty()) {
				addButton("（云端还没有任何备份）", rowY(0), null);
			}

			addPagingButtons(totalPages);
		}

		addFooter("刷新列表", this::loadWorlds, "返回", this::closeToParent);
	}

	private void initSnapshotList() {
		if (pendingRestore != null) {
			initRestoreConfirmation();
			return;
		}

		if (!busy) {
			int totalPages = pageCount(snapshots.size());

			for (int row = 0; row < MAX_ROWS; row++) {
				int index = page * MAX_ROWS + row;

				if (index >= snapshots.size()) {
					break;
				}

				SnapshotMeta meta = snapshots.get(index);

				addButton(describe(meta), rowY(row), () -> {
					pendingRestore = meta.fileName;
					status = "即将还原：" + meta.fileName;
					reinit();
				});
			}

			if (snapshots.isEmpty()) {
				addButton("（这个世界在云端没有快照）", rowY(0), null);
			}

			addPagingButtons(totalPages);
			addBackupNowButton();
		}

		addFooter("刷新快照", this::loadSnapshots, "返回世界列表", this::backToWorlds);
	}

	private void initRestoreConfirmation() {
		// 还原会覆盖本地世界，值得多问一句。
		addButton("确认还原并覆盖本地世界（原世界会自动另存备份）", rowY(0), () -> startRestore(pendingRestore));
		addButton("取消", rowY(1), () -> {
			pendingRestore = null;
			status = "已取消";
			reinit();
		});

		addFooter(null, null, "返回快照列表", () -> {
			pendingRestore = null;
			reinit();
		});
	}

	private static String describe(SnapshotMeta meta) {
		int hashLength = Math.min(8, meta.contentHash.length());

		return Timestamps.forDisplay(meta.createdAtEpochMillis)
				+ " · " + HumanSize.format(meta.sizeBytes)
				+ " · " + meta.contentHash.substring(0, hashLength);
	}

	// ------------------------------------------------------------------
	// 布局辅助
	// ------------------------------------------------------------------

	/**
	 * 状态栏。
	 *
	 * <p>因为不写自绘代码，这里用一个禁用的按钮来显示文字。看着有点取巧，
	 * 但它是这个取舍下唯一能在不碰渲染 API 的前提下把状态显示出来的办法。
	 */
	private void addStatusBar() {
		addButton(status, STATUS_Y, null);
	}

	private int rowY(int row) {
		return ROWS_TOP + row * ROW_HEIGHT;
	}

	private int left() {
		return (this.width - WIDTH) / 2;
	}

	/** action 为 null 表示这是一个只用来显示文字、不可点击的项。 */
	private void addButton(String label, int y, Runnable action) {
		Button button = Button.builder(Component.literal(label), pressed -> {
			if (action != null) {
				action.run();
			}
		}).bounds(left(), y, WIDTH, BUTTON_HEIGHT).build();

		button.active = action != null;
		addRenderableWidget(button);
	}

	private void addPagingButtons(int totalPages) {
		if (totalPages <= 1) {
			return;
		}

		int y = this.height - 74;

		Button previous = Button.builder(Component.literal("上一页"), pressed -> {
			page = Math.max(0, page - 1);
			reinit();
		}).bounds(left(), y, 100, BUTTON_HEIGHT).build();
		previous.active = page > 0;
		addRenderableWidget(previous);

		Button next = Button.builder(Component.literal("下一页"), pressed -> {
			page = Math.min(totalPages - 1, page + 1);
			reinit();
		}).bounds(left() + WIDTH - 100, y, 100, BUTTON_HEIGHT).build();
		next.active = page < totalPages - 1;
		addRenderableWidget(next);
	}

	private void addBackupNowButton() {
		WorldHandle active = BackupEvents.activeWorld();

		if (active == null) {
			return;
		}

		Button button = Button.builder(
						Component.literal("立即备份当前世界「" + active.id() + "」"),
						pressed -> backupNow(active))
				.bounds(left(), this.height - 50, WIDTH, BUTTON_HEIGHT)
				.build();

		addRenderableWidget(button);
	}

	private void addFooter(String leftLabel, Runnable leftAction, String rightLabel, Runnable rightAction) {
		int y = this.height - 28;

		if (leftLabel != null) {
			Button left = Button.builder(Component.literal(leftLabel), pressed -> leftAction.run())
					.bounds(left(), y, 148, BUTTON_HEIGHT).build();
			addRenderableWidget(left);
		}

		Button right = Button.builder(Component.literal(rightLabel), pressed -> rightAction.run())
				.bounds(left() + WIDTH - 148, y, 148, BUTTON_HEIGHT).build();
		addRenderableWidget(right);
	}

	private static int pageCount(int size) {
		return Math.max(1, (size + MAX_ROWS - 1) / MAX_ROWS);
	}

	/** 重建整个界面。重新 setScreen 等价于再调一次 init()。 */
	private void reinit() {
		Minecraft.getInstance().gui.setScreen(this);
	}

	// ------------------------------------------------------------------
	// 动作
	// ------------------------------------------------------------------

	private void loadWorlds() {
		busy = true;
		status = "正在读取云端世界列表……";
		reinit();

		runAsync(
				() -> McCloudBackupClient.backupService().listCloudWorlds(),
				result -> {
					busy = false;
					worlds = result.worlds();
					page = 0;
					status = result.error() != null
							? result.error()
							: "共 " + worlds.size() + " 个云端世界";
					reinit();
				});
	}

	private void openWorld(String world) {
		selectedWorld = world;
		loadSnapshots();
	}

	private void loadSnapshots() {
		String world = selectedWorld;

		busy = true;
		status = "正在读取「" + world + "」的快照……";
		reinit();

		runAsync(
				() -> McCloudBackupClient.backupService().listSnapshots(world),
				result -> {
					busy = false;
					snapshots = result.snapshots();
					page = 0;
					status = result.error() != null
							? result.error()
							: "「" + world + "」共 " + snapshots.size() + " 份快照";
					reinit();
				});
	}

	private void backToWorlds() {
		selectedWorld = null;
		snapshots = new ArrayList<>();
		pendingRestore = null;
		page = 0;
		status = "共 " + worlds.size() + " 个云端世界";
		reinit();
	}

	private void startRestore(String fileName) {
		String worldId = selectedWorld;

		// 还原目标就是本地存档目录下的同名文件夹。云端世界的名字本来就是本地目录名，
		// 所以即使本地已经把这个世界删了，也能从云端拉回来。
		Path saves = McCloudBackupClient.savesDirectory().normalize();
		Path resolved = saves.resolve(worldId).normalize();

		// 云端返回的目录名属于外部输入，不能直接当路径用：万一是 ".." 或者
		// 带上了路径分隔符，还原就会往存档目录外面写。这里确认解析后仍落在存档目录内。
		if (!resolved.startsWith(saves) || resolved.equals(saves)) {
			status = "云端返回的世界名不合法，已拒绝还原：" + worldId;
			reinit();
			return;
		}

		WorldHandle target = new WorldHandle(worldId, resolved);

		McCloudBackupClient.progress().reset();
		busy = true;
		pendingRestore = null;
		status = "正在还原 " + fileName + "……（大世界需要一会儿）";
		reinit();

		// 走和备份同一个调度器，还原和备份就不会同时动同一个世界目录。
		McCloudBackupClient.scheduler().submit(() -> {
			RestoreService.Outcome outcome = McCloudBackupClient.restoreService()
					.restore(target, fileName, McCloudBackupClient.progress());

			runOnClient(() -> {
				busy = false;
				status = outcome.message();
				reinit();
				McCloudBackupClient.notifyPlayer(outcome.message());
			});
		});
	}

	private void backupNow(WorldHandle world) {
		McCloudBackupClient.progress().reset();
		busy = true;
		status = "正在备份「" + world.id() + "」……";
		reinit();

		McCloudBackupClient.scheduler().submit(() -> {
			BackupService.Outcome outcome = McCloudBackupClient.backupService()
					.backup(world, McCloudBackupClient.progress());

			runOnClient(() -> {
				busy = false;
				status = outcome.message();
				reinit();
			});
		});
	}

	private void closeToParent() {
		Minecraft.getInstance().gui.setScreen(parent);
	}

	// ------------------------------------------------------------------
	// 线程
	// ------------------------------------------------------------------

	/** 后台跑一段活，完成后回主线程执行 onDone。 */
	private <T> void runAsync(Supplier<T> work, Consumer<T> onDone) {
		CompletableFuture.supplyAsync(work)
				.exceptionally(error -> {
					McCloudBackupClient.LOGGER.warn("后台请求失败", error);
					return null;
				})
				.thenAccept(result -> {
					if (result != null) {
						runOnClient(() -> onDone.accept(result));
					}
				});
	}

	/**
	 * 把一段操作丢回游戏主线程。
	 *
	 * <p>真正执行时会再确认一次这个界面还在显示：网络请求可能跑很久，
	 * 期间玩家完全可能已经退回上一层甚至进了别的界面。
	 */
	private void runOnClient(Runnable action) {
		try {
			Minecraft.getInstance().execute(() -> {
				if (!alive) {
					return;
				}

				action.run();
			});
		} catch (Throwable t) {
			// 游戏正在关闭时 execute 会被拒绝，忽略即可。
			McCloudBackupClient.LOGGER.warn("无法回到主线程更新界面", t);
		}
	}
}
