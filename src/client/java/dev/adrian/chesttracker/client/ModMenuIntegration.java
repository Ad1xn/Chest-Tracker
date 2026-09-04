package dev.adrian.chesttracker.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.adrian.chesttracker.client.ui.ConfigScreen;

/**
 * Opens the settings screen from Mod Menu's mod list.
 *
 * <p>Optional by design. Mod Menu is a compile-only dependency and is listed
 * under {@code suggests}, so without it this class is simply never loaded and
 * the settings stay reachable through the config file. Keeping the adapter this
 * small also contains the risk of Mod Menu's API differing between the versions
 * it ships for each Minecraft release.
 */
public final class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ConfigScreen::new;
    }
}
