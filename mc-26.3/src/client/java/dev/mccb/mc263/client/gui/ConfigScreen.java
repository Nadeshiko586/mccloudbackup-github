package dev.mccb.mc263.client.gui;

import dev.mccb.core.config.BackupConfig;
import dev.mccb.mc263.client.McCloudBackupClient;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 设置界面。整屏由 Cloth Config 生成，我们不自己画任何东西。
 *
 * <p>这是刻意的：26.3 的渲染管线改动很大（{@code Screen#render} 换成了
 * {@code extractRenderState}，{@code GuiGraphics} 换成了 {@code GuiGraphicsExtractor}），
 * 自己画界面是最容易写错的部分。交给 Cloth Config 能挡掉绝大部分风险。
 */
public final class ConfigScreen {

	private ConfigScreen() {
	}

	public static Screen create(Screen parent) {
		// 拿一份全新的配置来改，不碰缓存里那份。
		// 这样玩家改到一半点「取消」，什么都不会生效。
		BackupConfig config = McCloudBackupClient.loadFreshConfig();

		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Component.literal("云存档设置"));

		ConfigEntryBuilder entries = builder.entryBuilder();

		buildGeneral(builder, entries, config);
		buildWebDav(builder, entries, config);
		buildTriggers(builder, entries, config);
		buildAdvanced(builder, entries, config);

		builder.setSavingRunnable(() -> McCloudBackupClient.saveConfig(config));

		return builder.build();
	}

	private static void buildGeneral(ConfigBuilder builder, ConfigEntryBuilder entries, BackupConfig config) {
		ConfigCategory category = builder.getOrCreateCategory(Component.literal("常规"));

		category.addEntry(entries.startBooleanToggle(Component.literal("启用云存档"), config.enabled)
				.setDefaultValue(true)
				.setSaveConsumer(value -> config.enabled = value)
				.build());

		category.addEntry(entries.startTextDescription(
				Component.literal("配置文件位于 " + McCloudBackupClient.configFile())).build());
	}

	private static void buildWebDav(ConfigBuilder builder, ConfigEntryBuilder entries, BackupConfig config) {
		ConfigCategory category = builder.getOrCreateCategory(Component.literal("WebDAV"));

		category.addEntry(entries.startStrField(Component.literal("服务器地址"), config.webdav.url)
				.setDefaultValue("")
				.setSaveConsumer(value -> config.webdav.url = value)
				.build());

		category.addEntry(entries.startStrField(Component.literal("用户名"), config.webdav.username)
				.setDefaultValue("")
				.setSaveConsumer(value -> config.webdav.username = value)
				.build());

		category.addEntry(entries.startStrField(Component.literal("密码"), config.webdav.password)
				.setDefaultValue("")
				.setSaveConsumer(value -> config.webdav.password = value)
				.build());

		category.addEntry(entries.startTextDescription(Component.literal(
				"密码以明文保存在配置文件里。请使用应用专用密码，不要用主账号密码。")).build());

		category.addEntry(entries.startStrField(Component.literal("云端根目录"), config.webdav.remoteRoot)
				.setDefaultValue("mccloudbackup")
				.setSaveConsumer(value -> config.webdav.remoteRoot = value)
				.build());

		category.addEntry(entries.startBooleanToggle(
						Component.literal("允许不安全的 TLS（自签证书）"), config.webdav.allowInsecureTls)
				.setDefaultValue(false)
				.setSaveConsumer(value -> config.webdav.allowInsecureTls = value)
				.build());

		category.addEntry(entries.startTextDescription(Component.literal(
				"只有自建 WebDAV 用自签证书时才需要打开上面这项。打开后不再校验服务器证书。")).build());

		category.addEntry(entries.startBooleanToggle(
						Component.literal("按整合包分文件夹"), config.webdav.groupByModpack)
				.setDefaultValue(true)
				.setSaveConsumer(value -> config.webdav.groupByModpack = value)
				.build());

		category.addEntry(entries.startTextDescription(Component.literal(
				"不同整合包的存档目录经常重名，而且 mod 列表不同，互相还原基本等于毁档，"
						+ "所以默认在云端多分一层整合包文件夹。")).build());

		category.addEntry(entries.startStrField(
						Component.literal("整合包名（留空 = 自动识别）"), config.webdav.modpackName)
				.setDefaultValue("")
				.setSaveConsumer(value -> config.webdav.modpackName = value)
				.build());

		category.addEntry(entries.startTextDescription(Component.literal(
				"自动识别结果：" + McCloudBackupClient.detectedModpack())).build());

		category.addEntry(entries.startTextDescription(Component.literal(
				"云端路径：<服务器地址>/" + config.effectiveRemoteRoot(McCloudBackupClient.detectedModpack())
						+ "/<世界名>/")).build());
	}

	private static void buildTriggers(ConfigBuilder builder, ConfigEntryBuilder entries, BackupConfig config) {
		ConfigCategory category = builder.getOrCreateCategory(Component.literal("触发时机"));

		category.addEntry(entries.startBooleanToggle(
						Component.literal("退出世界时备份"), config.triggers.onWorldExit)
				.setDefaultValue(true)
				.setSaveConsumer(value -> config.triggers.onWorldExit = value)
				.build());

		category.addEntry(entries.startBooleanToggle(
						Component.literal("每次自动保存后备份"), config.triggers.onAutosave)
				.setDefaultValue(false)
				.setSaveConsumer(value -> config.triggers.onAutosave = value)
				.build());

		category.addEntry(entries.startIntField(
						Component.literal("自动保存备份的最小间隔（分钟）"),
						config.triggers.autosaveMinIntervalMinutes)
				.setDefaultValue(10)
				.setMin(1)
				.setMax(1440)
				.setSaveConsumer(value -> config.triggers.autosaveMinIntervalMinutes = value)
				.build());

		category.addEntry(entries.startBooleanToggle(
						Component.literal("按固定间隔备份"), config.triggers.onInterval)
				.setDefaultValue(false)
				.setSaveConsumer(value -> config.triggers.onInterval = value)
				.build());

		category.addEntry(entries.startIntField(
						Component.literal("间隔（分钟）"), config.triggers.intervalMinutes)
				.setDefaultValue(30)
				.setMin(1)
				.setMax(1440)
				.setSaveConsumer(value -> config.triggers.intervalMinutes = value)
				.build());
	}

	private static void buildAdvanced(ConfigBuilder builder, ConfigEntryBuilder entries, BackupConfig config) {
		ConfigCategory category = builder.getOrCreateCategory(Component.literal("保留与高级"));

		category.addEntry(entries.startIntField(
						Component.literal("每个世界保留的快照数"), config.retention.maxSnapshotsPerWorld)
				.setDefaultValue(10)
				.setMin(1)
				.setMax(1000)
				.setSaveConsumer(value -> config.retention.maxSnapshotsPerWorld = value)
				.build());

		category.addEntry(entries.startIntField(
						Component.literal("压缩级别（0-9）"), config.advanced.compressionLevel)
				.setDefaultValue(6)
				.setMin(0)
				.setMax(9)
				.setSaveConsumer(value -> config.advanced.compressionLevel = value)
				.build());

		category.addEntry(entries.startIntField(
						Component.literal("网络超时（秒）"), config.advanced.timeoutSeconds)
				.setDefaultValue(60)
				.setMin(5)
				.setMax(600)
				.setSaveConsumer(value -> config.advanced.timeoutSeconds = value)
				.build());

		category.addEntry(entries.startBooleanToggle(
						Component.literal("内容没变化时跳过上传"), config.advanced.skipUnchanged)
				.setDefaultValue(true)
				.setSaveConsumer(value -> config.advanced.skipUnchanged = value)
				.build());

		category.addEntry(entries.startStrList(
						Component.literal("排除的文件（支持 * 和 ?）"), config.advanced.excludePatterns)
				.setDefaultValue(java.util.Arrays.asList("session.lock", "*.lock"))
				.setSaveConsumer(value -> config.advanced.excludePatterns = value)
				.build());

		category.addEntry(entries.startTextDescription(Component.literal(
				"不含斜杠的规则只匹配文件名，对任意层级都生效；含斜杠的规则按整条相对路径匹配。")).build());
	}
}
