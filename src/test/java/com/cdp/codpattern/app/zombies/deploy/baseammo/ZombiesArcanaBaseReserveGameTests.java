package com.cdp.codpattern.app.zombies.deploy.baseammo;
import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;
/** Opt-in: requires actual Arcana 1.1.4 and the official Vanguard AK47 / GP25 fixture. */
@GameTestHolder("codpattern_zombies_baseammo_arcana")
@PrefixGameTestTemplate(false)
public final class ZombiesArcanaBaseReserveGameTests {
    @GameTest(template="empty",templateNamespace="codpattern_zombies_baseammo_arcana",timeoutTicks=100)
    public static void realGp25KeepsOwnAmmoWhileRootInitializesRefillsUpgradesAndRestores(GameTestHelper helper) throws Exception {
        var player = new FakePlayer(helper.getLevel(),new GameProfile(UUID.randomUUID(),"Gp25BaseAmmo"));
        try {ZombiesBaseReserveRuntimeScenario.run(player,true);helper.succeed();}
        finally {player.getInventory().clearContent();}
    }
}
