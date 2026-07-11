package me.cortex.voxy.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        // Sodium 0.8 registers Voxy's page through the config API
        // (VoxyConfigMenu) instead of the old OptionStorage/SodiumOptionsGUI
        // mixin approach, which no longer exists. Deep-linking straight to
        // Voxy's own page isn't exposed by the new API, so this just opens
        // the video settings screen where Voxy's page is listed.
        return VideoSettingsScreen::createScreen;
    }
}