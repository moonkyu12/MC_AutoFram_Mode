package org.rust.autofarm;

import com.mojang.logging.LogUtils;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(Autofarm.MODID)
public final class Autofarm {
    public static final String MODID = "autofarm";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Autofarm(FMLJavaModLoadingContext context) {
        var modBusGroup = context.getModBusGroup();

        context.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        ModConfigEvent.Loading.getBus(modBusGroup).addListener(event -> Config.load());
        ModConfigEvent.Reloading.getBus(modBusGroup).addListener(event -> Config.load());

        context.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new AutoFarmConfigScreen(parent)));

        RegisterKeyMappingsEvent.BUS.addListener(KeyBindings::register);
        TickEvent.ClientTickEvent.Post.BUS.addListener(FarmController::onClientTick);
        MovementInputUpdateEvent.BUS.addListener(FarmController::onMovementInput);
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(FarmController::onLoggingOut);

        LOGGER.info("AutoFarm loaded");
    }
}
