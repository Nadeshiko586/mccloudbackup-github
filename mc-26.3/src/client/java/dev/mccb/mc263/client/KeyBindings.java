package dev.mccb.mc263.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mccb.mc263.client.gui.MainScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/**
 * 快捷键。
 *
 * <p>26.x 有好几处改名，照抄旧教程会一路编译不过：
 * <ul>
 *   <li>Fabric 侧是 {@code KeyMappingHelper}，不是旧的 {@code KeyBindingHelper}，
 *       而且它在 {@code keymapping} 包里（不是 {@code keybinding}）。</li>
 *   <li>{@code KeyMapping} 的最后一个参数从分类字符串变成了
 *       {@code KeyMapping.Category} 这个 record。</li>
 *   <li>{@code InputConstants.Type} 的常量是 {@code KEYBOARD} / {@code MOUSE}，
 *       没有旧版教程里的 {@code KEYSYM}。</li>
 *   <li>按键码用 {@code InputConstants.KEY_*} 即可，<b>不要引 LWJGL 的
 *       {@code GLFW.GLFW_KEY_*}</b>——LWJGL 不在编译期类路径上，
 *       引了会直接报"程序包 org.lwjgl.glfw 不存在"。</li>
 * </ul>
 */
public final class KeyBindings {

	/** 默认按键。玩家可以在「选项 → 控制」里改。 */
	private static final int DEFAULT_KEY = InputConstants.KEY_B;

	private static KeyMapping openGui;

	private KeyBindings() {
	}

	public static void register() {
		openGui = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.mccloudbackup.open_gui",
				InputConstants.Type.KEYBOARD,
				DEFAULT_KEY,
				KeyMapping.Category.MISC));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// consumeClick 一次只取走一个排队的按键，用 while 才不会丢掉连按。
			while (openGui.consumeClick()) {
				// 只在没有任何界面打开时响应，免得在别人的界面里横插一脚。
				if (client.gui.screen() == null) {
					MainScreen.open(new MainScreen(null));
				}
			}
		});
	}
}
