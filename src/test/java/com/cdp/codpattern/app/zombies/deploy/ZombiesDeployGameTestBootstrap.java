package com.cdp.codpattern.app.zombies.deploy;

import com.cdp.codpattern.zombiesaddon.ZombiesAddonConstants;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/** Test-only empty registry: GameTestServer omits FPSM's ServerStarted initialization. */
@Mod.EventBusSubscriber(modid = ZombiesAddonConstants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ZombiesDeployGameTestBootstrap {
    private ZombiesDeployGameTestBootstrap() { }

    @SubscribeEvent
    public static void beforeWorldLoad(ServerAboutToStartEvent event) {
        if (!(event.getServer() instanceof GameTestServer)) { return; }
        // Forge's transforming context loader hides jdk.random service providers on Java 17.
        // Initialize the JDK factory before map constructors request its default generator.
        Thread thread = Thread.currentThread();
        ClassLoader loader = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(ClassLoader.getSystemClassLoader());
            java.util.random.RandomGenerator.getDefault();
        } finally {
            thread.setContextClassLoader(loader);
        }
        if (FPSMCore.initialized()) { return; }
        try {
            Constructor<FPSMCore> constructor = FPSMCore.class.getDeclaredConstructor(String.class);
            constructor.setAccessible(true);
            Field instance = FPSMCore.class.getDeclaredField("INSTANCE");
            instance.setAccessible(true);
            instance.set(null, constructor.newInstance("zombies_deploy_gametest"));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot initialize the GameTest-only FPSM registry", error);
        }
    }
}
