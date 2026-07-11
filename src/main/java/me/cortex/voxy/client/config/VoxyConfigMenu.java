package me.cortex.voxy.client.config;

import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;

/**
 * Registers Voxy's options page with Sodium's config API (replaces the old
 * OptionStorage/SodiumOptionsGUI-mixin integration, removed in Sodium 0.8).
 * Discovered on Fabric via the "sodium:config_api_user" entrypoint declared
 * in fabric.mod.json, and on NeoForge (including through Connector) via the
 * {@link ConfigEntryPointForge} annotation scan.
 */
@ConfigEntryPointForge("voxy")
public class VoxyConfigMenu implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        if (!VoxyCommon.isAvailable()) {
            return;
        }

        builder.registerOwnModOptions()
                .setName("Voxy")
                .addPage(VoxyConfigScreenPages.page(builder));
    }
}
