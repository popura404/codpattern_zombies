package com.cdp.codpattern.common.sound;

import com.cdp.codpattern.app.zombies.model.ZombiesWaveDefinition;
import com.cdp.codpattern.zombiesaddon.ZombiesAddonConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ZombiesSoundRegister {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ZombiesAddonConstants.MOD_ID);
    public static final RegistryObject<SoundEvent> NORMAL_WAVE_INTRO = register("wave_intro_normal");
    public static final RegistryObject<SoundEvent> BOSS_WAVE_INTRO = register("wave_intro_boss");

    private ZombiesSoundRegister() {
    }

    public static SoundEvent forWave(ZombiesWaveDefinition wave) {
        return (wave != null && wave.isBossIntro() ? BOSS_WAVE_INTRO : NORMAL_WAVE_INTRO).get();
    }

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(
                new ResourceLocation(ZombiesAddonConstants.MOD_ID, name)));
    }
}
