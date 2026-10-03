package dev.mccb.core.platform;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 核心层与 Minecraft 之间的<b>唯一</b>边界。
 *
 * <p>整个 shared/ 目录里的其它代码都不许 import Minecraft 的类，只能通过这个接口
 * 拿它需要的东西。这样做的回报是：将来要支持 1.20.1 或 1.21.x 时，只需要为新版本
 * 再写一个实现类，核心逻辑一行都不用动。
 *
 * <p>接口刻意保持得很窄——每多一个方法，就多一处将来要跨版本适配的地方。
 */
public interface GamePlatform {

	/**
	 * 当前正在游玩的世界；不在任何单人世界中时返回 {@link Optional#empty()}。
	 *
	 * <p>这个方法是<b>热路径</b>，会被自动保存等事件频繁调用，实现里不要做重活。
	 */
	Optional<WorldHandle> currentWorld();

	/** 游戏运行目录（通常就是 .minecraft）。 */
	Path gameDirectory();

	/** 给玩家一条可见的提示。实现要保证在任意线程调用都安全。 */
	void notifyPlayer(String message);
}
