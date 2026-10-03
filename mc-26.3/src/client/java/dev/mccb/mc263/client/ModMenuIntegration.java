package dev.mccb.mc263.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.mccb.mc263.client.gui.MainScreen;

/**
 * 把入口挂到 Mod Menu 上。
 *
 * <p>Mod Menu 是软依赖：没装它这个 mod 照常工作，只是少一个图形入口
 * （配置文件依然可以直接手改，见 README）。
 */
public class ModMenuIntegration implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return MainScreen::new;
	}
}
