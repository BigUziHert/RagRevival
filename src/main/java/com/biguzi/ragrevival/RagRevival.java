package com.biguzi.ragrevival;

import com.biguzi.ragrevival.client.RevivalClient;
import com.biguzi.ragrevival.compat.CarryOnCompat;
import com.biguzi.ragrevival.network.RevivalNetwork;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

@Mod(RagRevival.MOD_ID)
public final class RagRevival {
    public static final String MOD_ID = "ragrevival";
    public RagRevival(IEventBus bus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, RevivalConfig.SPEC);
        bus.addListener(RevivalNetwork::register);
        NeoForge.EVENT_BUS.register(DownedManager.class);
        CarryOnCompat.register();
        if (FMLEnvironment.dist == Dist.CLIENT) RevivalClient.register(bus);
    }
}
