package com.cdp.codpattern.app.zombies.deploy.baseammo;
import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;
@GameTestHolder("codpattern_zombies_baseammo")
@PrefixGameTestTemplate(false)
public final class ZombiesBaseReserveGameTests {
    @GameTest(template="empty",templateNamespace="codpattern_zombies_baseammo",timeoutTicks=100)
    public static void realBaseGunPaidRefillUpgradeSnapshotReviveAndNativeReload(GameTestHelper helper) throws Exception {
        var player = new FakePlayer(helper.getLevel(),new GameProfile(UUID.randomUUID(),"BaseAmmoProbe"));
        try {ZombiesBaseReserveRuntimeScenario.run(player,false);helper.succeed();}
        finally {player.getInventory().clearContent();}
    }
}
