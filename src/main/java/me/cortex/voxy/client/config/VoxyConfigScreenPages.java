package me.cortex.voxy.client.config;

import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import me.cortex.voxy.client.core.gl.Capabilities;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public abstract class VoxyConfigScreenPages {
    private static final Component[] SSAO_MODE_LABELS = {
            Component.translatable("voxy.config.general.ssao_mode.auto"),
            Component.translatable("voxy.config.general.ssao_mode.basic"),
            Component.translatable("voxy.config.general.ssao_mode.better"),
            Component.translatable("voxy.config.general.ssao_mode.best")
    };

    private VoxyConfigScreenPages(){}

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("voxy", path);
    }

    private static final ResourceLocation ADAPT_CLOUD_DISTANCE_ID = id("adapt_cloud_distance");

    // Sodium's config API requires a storage handler on every option purely
    // as a "config was saved" notification hook (the actual field writes
    // happen through setBinding above) - Voxy keeps its own config file, so
    // this just persists it whenever any option is applied.
    private static final StorageEventHandler SAVE_HANDLER = () -> VoxyConfig.CONFIG.save();

    public static OptionPageBuilder page(ConfigBuilder builder) {
        VoxyConfig storage = VoxyConfig.CONFIG;

        OptionPageBuilder page = builder.createOptionPage()
                .setName(Component.translatable("voxy.config.title"));

        //General
        OptionGroupBuilder group1 = builder.createOptionGroup();
        group1.addOption(builder.createBooleanOption(id("enabled"))
                .setName(Component.translatable("voxy.config.general.enabled"))
                .setTooltip(Component.translatable("voxy.config.general.enabled.tooltip"))
                .setBinding(v -> {
                    storage.enabled = v;
                    if (v && ClientSessionEvents.inSession) {
                        VoxyCommon.createInstance();
                    }

                    if (!v) {
                        var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                        if (vrsh != null) {
                            vrsh.voxy$shutdownRenderer();
                        }
                        VoxyCommon.shutdownInstance();
                    }

                    try { IrisUtil.reload(); } catch (Throwable ignored) {}
                }, () -> storage.enabled)
                .setDefaultValue(true)
                .setStorageHandler(SAVE_HANDLER)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
        );
        page.addOptionGroup(group1);

        OptionGroupBuilder group2 = builder.createOptionGroup();
        group2.addOption(builder.createIntegerOption(id("service_threads"))
                .setName(Component.translatable("voxy.config.general.serviceThreads"))
                .setTooltip(Component.translatable("voxy.config.general.serviceThreads.tooltip"))
                .setRange(1, Runtime.getRuntime().availableProcessors(), 1)
                .setValueFormatter(v -> Component.literal(Integer.toString(v)))
                .setBinding(v -> {
                    storage.serviceThreads = v;
                    var instance = VoxyCommon.getInstance();
                    if (instance != null) {
                        instance.updateDedicatedThreads();
                    }
                }, () -> storage.serviceThreads)
                .setDefaultValue(Math.max(Math.min(CpuLayout.getCoreCount() / 2, 8), 1))
                .setStorageHandler(SAVE_HANDLER)
                .setImpact(OptionImpact.HIGH)
        ).addOption(builder.createBooleanOption(id("use_sodium_builder"))
                .setName(Component.translatable("voxy.config.general.useSodiumBuilder"))
                .setTooltip(Component.translatable("voxy.config.general.useSodiumBuilder.tooltip"))
                .setImpact(OptionImpact.VARIES)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .setBinding(v -> {
                    storage.dontUseSodiumBuilderThreads = !v;
                    var instance = VoxyCommon.getInstance();
                    if (instance != null) {
                        instance.updateDedicatedThreads();
                    }
                }, () -> !storage.dontUseSodiumBuilderThreads)
                .setDefaultValue(true)
                .setStorageHandler(SAVE_HANDLER)
        ).addOption(builder.createBooleanOption(id("ingest"))
                .setName(Component.translatable("voxy.config.general.ingest"))
                .setTooltip(Component.translatable("voxy.config.general.ingest.tooltip"))
                .setBinding(v -> storage.ingestEnabled = v, () -> storage.ingestEnabled)
                .setDefaultValue(true)
                .setStorageHandler(SAVE_HANDLER)
                .setImpact(OptionImpact.MEDIUM)
        );
        page.addOptionGroup(group2);

        OptionGroupBuilder group3 = builder.createOptionGroup();
        group3.addOption(builder.createBooleanOption(id("rendering"))
                .setName(Component.translatable("voxy.config.general.rendering"))
                .setTooltip(Component.translatable("voxy.config.general.rendering.tooltip"))
                .setBinding(v -> {
                    storage.enableRendering = v;
                    var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                    if (vrsh != null) {
                        if (v) {
                            vrsh.voxy$createRenderer();
                        } else {
                            vrsh.voxy$shutdownRenderer();
                        }
                    }
                    try { IrisUtil.reload(); } catch (Throwable ignored) {}
                }, () -> storage.enableRendering)
                .setDefaultValue(true)
                .setStorageHandler(SAVE_HANDLER)
                .setImpact(OptionImpact.HIGH)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
        ).addOption(builder.createIntegerOption(id("sub_division_size"))
                .setName(Component.translatable("voxy.config.general.subDivisionSize"))
                .setTooltip(Component.translatable("voxy.config.general.subDivisionSize.tooltip"))
                .setRange(0, SUBDIV_IN_MAX, 1)
                .setValueFormatter(v -> Component.literal(Integer.toString(Math.round(ln2subDiv(v)))))
                .setBinding(v -> storage.subDivisionSize = ln2subDiv(v), () -> subDiv2ln(storage.subDivisionSize))
                .setDefaultValue(subDiv2ln(64))
                .setStorageHandler(SAVE_HANDLER)
                .setImpact(OptionImpact.HIGH)
        ).addOption(builder.createIntegerOption(id("render_distance"))
                .setName(Component.translatable("voxy.config.general.renderDistance"))
                .setTooltip(Component.translatable("voxy.config.general.renderDistance.tooltip"))
                // Range: 10 to 64*16 (1024). Display: v*2
                .setRange(10, 64 * 16, 1)
                .setValueFormatter(v -> Component.literal(Integer.toString(v * 2)))
                .setBinding(v -> {
                    // Value stored as float fraction
                    storage.sectionRenderDistance = ((float) v) / 16.0f;

                    var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                    if (vrsh != null) {
                        var vrs = vrsh.voxy$getRenderSystem();
                        if (vrs != null) {
                            vrs.setRenderDistance(storage.sectionRenderDistance);
                        }
                    }
                }, () -> Math.round(storage.sectionRenderDistance * 16))
                .setDefaultValue(Math.round(16 * 16))
                .setStorageHandler(SAVE_HANDLER)
                .setImpact(OptionImpact.LOW)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
        ).addOption(builder.createBooleanOption(id("use_mesh_shaders"))
                .setName(Component.literal("Use Mesh Shaders (Nvidia)"))
                .setTooltip(Component.literal("Enables the advanced GPU-driven Mesh Shader rendering pipeline (requires GL_NV_mesh_shader support)"))
                .setEnabledProvider(state -> Capabilities.INSTANCE.meshShaders)
                .setBinding(v -> {
                    storage.useMeshShaders = v;
                    reloadActiveRenderer();
                }, () -> storage.useMeshShaders)
                .setDefaultValue(false)
                .setStorageHandler(SAVE_HANDLER)
                .setImpact(OptionImpact.HIGH)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
        );
        page.addOptionGroup(group3);

        OptionGroupBuilder group4 = builder.createOptionGroup();
        group4.addOption(builder.createBooleanOption(id("render_fog"))
                .setName(Component.translatable("voxy.config.general.render_fog"))
                .setTooltip(Component.translatable("voxy.config.general.render_fog.tooltip"))
                .setBinding(v -> storage.renderVoxyFog = v, () -> storage.renderVoxyFog)
                .setDefaultValue(true)
                .setStorageHandler(SAVE_HANDLER)
                .setImpact(OptionImpact.LOW)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
        ).addOption(builder.createIntegerOption(id("sky_fog_distance"))
                .setName(Component.literal("Sky fog distance"))
                .setTooltip(Component.literal("Higher distance, sharper sky fog"))
                .setRange(16, 512, 16)
                .setValueFormatter(v -> Component.literal(Integer.toString(v)))
                .setBinding(v -> storage.skyFogDistance = v, () -> storage.skyFogDistance)
                .setDefaultValue(96)
                .setStorageHandler(SAVE_HANDLER)
        ).addOption(builder.createIntegerOption(id("fog_intensity"))
                .setName(Component.literal("Fog intensity"))
                .setTooltip(Component.literal("Multiplier for terrain fog opacity. 0.0 = off, 1.0 = vanilla, 3.0 = triple"))
                .setRange(0, 300, 10)
                .setValueFormatter(v -> Component.literal(String.format("%.1f", v / 100.0f)))
                .setBinding(v -> storage.fogIntensity = v / 100.0f, () -> (int) (storage.fogIntensity * 100))
                .setDefaultValue(100)
                .setStorageHandler(SAVE_HANDLER)
        ).addOption(builder.createIntegerOption(id("fog_curve"))
                .setName(Component.literal("Fog curve"))
                .setTooltip(Component.literal("Shape of the fog curve. 0.0 = linear, higher values push fog towards the far end"))
                .setRange(0, 50, 1)
                .setValueFormatter(v -> Component.literal(String.format("%.1f", v / 10.0f)))
                .setBinding(v -> storage.fogDensity = v / 10.0f, () -> (int) (storage.fogDensity * 10))
                .setDefaultValue(0)
                .setStorageHandler(SAVE_HANDLER)
        ).addOption(builder.createBooleanOption(ADAPT_CLOUD_DISTANCE_ID)
                .setName(Component.literal("Adapt cloud distance"))
                .setTooltip(Component.literal("Extends the cloud distance according to the current render distance. It's automatically capped at 256"))
                .setBinding(v -> storage.adaptCloudDistance = v, () -> storage.adaptCloudDistance)
                .setDefaultValue(true)
                .setStorageHandler(SAVE_HANDLER)
                .setImpact(OptionImpact.LOW)
        ).addOption(builder.createIntegerOption(id("cloud_distance"))
                .setName(Component.literal("Cloud distance"))
                .setTooltip(Component.literal("Cloud render distance in chunks"))
                .setEnabledProvider(state -> !state.readBooleanOption(ADAPT_CLOUD_DISTANCE_ID), ADAPT_CLOUD_DISTANCE_ID)
                .setRange(0, 2048, 2)
                .setValueFormatter(v -> {
                    if (storage.adaptCloudDistance) {
                        return Component.literal("Adaptive");
                    }
                    return Component.literal(v < 1 ? "Default" : Integer.toString(v));
                })
                .setBinding(v -> storage.cloudDistance = v, () -> storage.cloudDistance)
                .setDefaultValue(0)
                .setStorageHandler(SAVE_HANDLER)
                .setImpact(OptionImpact.VARIES)
        ).addOption(builder.createEnumOption(id("ssao_mode"), SSAO.SSAOMode.class)
                .setName(Component.translatable("voxy.config.general.ssao_mode"))
                .setTooltip(Component.translatable("voxy.config.general.ssao_mode.tooltip"))
                .setElementNameProvider(net.caffeinemc.mods.sodium.api.config.structure.EnumOptionBuilder.nameProviderFrom(SSAO_MODE_LABELS))
                .setBinding(v -> {
                    storage.setSSAOMode(v);
                    reloadActiveRenderer();
                }, storage::getSSAOMode)
                .setDefaultValue(SSAO.SSAOMode.AUTO)
                .setStorageHandler(SAVE_HANDLER)
                .setImpact(OptionImpact.HIGH)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
        );
        page.addOptionGroup(group4);

        return page;
    }

    private static void reloadActiveRenderer() {
        try {
            var minecraft = Minecraft.getInstance();
            var renderer = (IGetVoxyRenderSystem) minecraft.levelRenderer;
            if (renderer != null && minecraft.level != null && VoxyConfig.CONFIG.isRenderingEnabled()) {
                renderer.voxy$shutdownRenderer();
                renderer.voxy$createRenderer();
            }
        } catch (Throwable ignored) {}

        try { IrisUtil.reload(); } catch (Throwable ignored) {}
    }

    private static final int SUBDIV_IN_MAX = 100;
    private static final double SUBDIV_MIN = 28;
    private static final double SUBDIV_MAX = 256;
    private static final double SUBDIV_CONST = Math.log(SUBDIV_MAX/SUBDIV_MIN)/Math.log(2);


    //In range is 0->200
    //Out range is 28->256
    private static float ln2subDiv(int in) {
        return (float) (SUBDIV_MIN*Math.pow(2, SUBDIV_CONST*((double)in/SUBDIV_IN_MAX)));
    }

    //In range is ... any?
    //Out range is 0->200
    private static int subDiv2ln(float in) {
        return (int) (((Math.log(((double)in)/SUBDIV_MIN)/Math.log(2))/SUBDIV_CONST)*SUBDIV_IN_MAX);
    }

}
