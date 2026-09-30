package com.cdp.codpattern.app.zombies.deploy;

import net.minecraft.core.Direction;

import java.util.Map;

public final class ZombiesDeployPreviewFacingCompatTest {
    private ZombiesDeployPreviewFacingCompatTest() {
    }

    public static void main(String[] args) {
        verify("north", Direction.NORTH, 180.0F);
        verify("east", Direction.EAST, 270.0F);
        verify("south", Direction.SOUTH, 0.0F);
        verify("west", Direction.WEST, 90.0F);
        verify(" EAST ", Direction.EAST, 270.0F);
        require(Direction.fromYRot(ZombiesDeployPreviewService.boxFacingYaw(Map.of())) == Direction.NORTH,
                "legacy drafts without facing must retain the model's north orientation");
        for (String invalid : new String[]{"up", "down", "", "diagonal", "90"}) {
            boolean rejected = false;
            try {
                ZombiesDeployPreviewService.boxFacingYaw(Map.of("facing", invalid));
            } catch (RuntimeException expected) {
                rejected = expected.getMessage().contains("field facing must be");
            }
            require(rejected, "invalid facing must not silently produce a misleading model: " + invalid);
        }
        System.out.println("PASS zombies deployment facing to preview yaw round trips");
    }

    private static void verify(String field, Direction expectedDirection, float expectedYaw) {
        float yaw = ZombiesDeployPreviewService.boxFacingYaw(Map.of("facing", field));
        require(yaw == expectedYaw, "preview must use Minecraft's horizontal yaw convention for " + field);
        require(Direction.fromYRot(yaw) == expectedDirection,
                "client model must face the same direction as the server draft for " + field);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
