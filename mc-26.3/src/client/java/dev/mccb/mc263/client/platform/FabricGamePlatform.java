package dev.mccb.mc263.client.platform;

import dev.mccb.core.platform.GamePlatform;
import dev.mccb.core.platform.WorldHandle;
import dev.mccb.core.util.Log;
import dev.mccb.core.util.NameSanitizer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Optional;

/**
 * {@link GamePlatform} 在 Minecraft 26.3 上的实现。
 *
 * <p><b>这是整个 mod 里唯一 import Minecraft 的文件。</b>
 * 将来要支持 1.20.1 / 1.21.x 时，只需要照着这个类再写一份实现，
 * shared/ 目录下的核心逻辑一行都不用改。
 *
 * <p>关于命名：26.1 起 Minecraft 官方已不再混淆代码，Fabric 也停止维护 Yarn，
 * 所以这里用的全部是 <b>Mojang 官方名称</b>，不是网上大多数教程里的 Yarn 名称。
 * 常见的对照关系：
 * <ul>
 *   <li>{@code MinecraftClient} → {@code Minecraft}</li>
 *   <li>{@code runDirectory} → {@code gameDirectory}</li>
 *   <li>{@code getSavePath(WorldSavePath.ROOT)} → {@code getWorldPath(LevelResource.ROOT)}</li>
 *   <li>{@code Text} → {@code Component}</li>
 * </ul>
 */
public final class FabricGamePlatform implements GamePlatform {

	/**
	 * 从服务端实例解析出世界目录。
	 *
	 * <p>用世界目录的<b>目录名</b>而不是玩家看到的显示名：目录名天然是文件系统安全的，
	 * 而显示名可能含斜杠、冒号等在 URL 和文件名里有特殊含义的字符。
	 * 顺带一提，26.3 的 {@code MinecraftServer} 已经没有 {@code getLevelName()} 了，
	 * 想拿世界名也只能走目录这条路。
	 */
	public static Optional<WorldHandle> resolveWorld(MinecraftServer server) {
		if (server == null) {
			return Optional.empty();
		}

		try {
			// 注意这个 normalize()，别删。
			//
			// 26.3 里 LevelResource 是个 record，ROOT 的 id 就是字符串 "."，
			// 所以 getWorldPath(ROOT) 返回的路径形如 <世界目录>/.
			// 直接对它 getFileName() 会得到 "."，不是世界目录名。
			//
			// 后果不是"报错"，而是更阴的静默错误：世界名变成 "."，
			// 云端路径里的 /./ 被服务器规范化掉，所有存档变成堆在整合包目录下的
			// 一堆散文件，而还原界面只列目录 → 看起来就是"云端明明有文件但列表是空的"。
			Path worldDir = server.getWorldPath(LevelResource.ROOT).normalize();
			Path fileName = worldDir.getFileName();

			if (fileName == null) {
				return Optional.empty();
			}

			String id = NameSanitizer.sanitize(fileName.toString());

			if (id.isEmpty()) {
				// 兜底：真出现纯点号之类不能当文件夹用的名字，就放弃这次备份，
				// 而不是拿它去拼一个诡异的云端路径。
				Log.warn("世界目录名无法用作云端文件夹名，本次跳过：" + fileName);
				return Optional.empty();
			}

			return Optional.of(new WorldHandle(id, worldDir));
		} catch (Throwable t) {
			// 宁可这次不备份，也不能因为拿不到路径把游戏搞崩。
			Log.warn("无法确定当前世界目录，本次将跳过备份", t);
			return Optional.empty();
		}
	}

	@Override
	public Optional<WorldHandle> currentWorld() {
		return resolveWorld(Minecraft.getInstance().getSingleplayerServer());
	}

	@Override
	public Path gameDirectory() {
		return Minecraft.getInstance().gameDirectory.toPath();
	}

	@Override
	public void notifyPlayer(String message) {
		Minecraft client = Minecraft.getInstance();

		// 这个方法会被后台的备份线程调用，而改游戏状态必须回主线程。
		client.execute(() -> {
			if (client.player != null) {
				// 26.3 把旧的 displayClientMessage(Component, boolean) 拆成了两个方法：
				// sendSystemMessage 发到聊天栏，sendOverlayMessage 发到物品栏上方的动作条。
				// 备份进度发聊天栏更合适——动作条那行会被别的提示挤掉。
				client.player.sendSystemMessage(Component.literal("[云存档] " + message));
			}
		});
	}
}
