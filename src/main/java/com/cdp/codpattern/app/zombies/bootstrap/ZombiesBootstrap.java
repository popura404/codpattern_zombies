package com.cdp.codpattern.app.zombies.bootstrap;

import com.cdp.codpattern.app.match.ModeModules;
import com.cdp.codpattern.app.zombies.ZombiesModeModule;
import com.cdp.codpattern.common.block.CodPatternBlockRegister;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;

import java.util.concurrent.atomic.AtomicBoolean;

/** Addon-owned bootstrap callable directly by the current shim or a future addon entry point. */
public final class ZombiesBootstrap {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private ZombiesBootstrap() {
    }

    public static void install(IEventBus modEventBus) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        ModeModules.contribute(ZombiesModeModule.INSTANCE);
        ZombiesNetworkPacketContributor.install();
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ZombiesClientBootstrap::install);

        modEventBus.addListener(CodPatternBlockRegister::onBuildCreativeModeTabContents);
        modEventBus.addListener(ZombiesItemRegister::onBuildCreativeModeTabContents);
        CodPatternBlockRegister.BLOCKS.register(modEventBus);
        CodPatternBlockRegister.ITEMS.register(modEventBus);
        ZombiesItemRegister.ITEMS.register(modEventBus);
    }
}
