package com.cdp.codpattern.client.zombies;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public final class ZombiesLabelProjectionCompatTest {
    private static final double TOLERANCE = 0.001D;

    public static void main(String[] args) {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(90.0D), 2.0F, 0.1F, 100.0F);
        assertRect(project(projection, -1, -0.5, -5, 1, 0.5, -5, false), 360, 180, 440, 220,
                "perspective screen rectangle");

        Matrix4f facingCamera = new Matrix4f(projection)
                .rotateY((float) Math.PI / 2.0F)
                .translate(5.0F, 0.0F, 0.0F)
                .rotateY(-(float) Math.PI / 2.0F)
                .scale(-0.035F, -0.035F, 0.035F);
        assertRect(project(facingCamera, -10, -5, 0, 10, 5, 0, false), 386, 193, 414, 207,
                "rotated camera and negative billboard scale");
        Matrix4f lifted = new Matrix4f(facingCamera).translate(0.0F, -12.0F, 0.0F);
        assertRect(project(lifted, -10, -5, 0, 10, 5, 0, false), 386, 176.2, 414, 190.2,
                "local negative Y lifts the billboard on screen");

        assertNull(project(projection, -0.01, -0.01, -0.2, 0.01, 0.01, 0.05, false),
                "text crossing the near plane must be rejected");
        assertRect(project(projection, -0.01, -0.01, -0.2, 0.01, 0.01, 0.05, true), 380, 180, 420, 220,
                "near-plane edge intersections preserve the entire visible weapon footprint");
        assertNull(project(projection, -1, -1, 0.1, 1, 1, 2, true), "box behind the camera");
        assertNull(project(projection, -1, -1, -0.09, 1, 1, -0.02, true), "box entirely before the near plane");

        ZombiesObjectLabelLayout.Rect scaled = ZombiesLabelProjection.projectBox(
                projection, -1, -0.5, -5, 1, 0.5, -5, 400, 200, false);
        assertRect(scaled, 180, 90, 220, 110, "GUI dimensions scale the bounds consistently");
        ZombiesObjectLabelLayout.Rect outside = project(projection, 100, -1, -5, 101, 1, -5, false);
        require(outside != null && outside.left() > 800, "off-screen coordinates must remain unclamped");
        assertNull(project(projection, Double.NaN, -1, -5, 1, 1, -5, false), "non-finite input");
        assertNull(project(new Matrix4f().m00(Float.NaN), -1, -1, -5, 1, 1, -5, false), "non-finite matrix");
        assertNull(ZombiesLabelProjection.projectBox(projection, -1, -1, -5, 1, 1, -5, 0, 200, false),
                "invalid screen dimensions");
        adjacentBoxesStayReadable();
        labelsAvoidProjectedWeaponAcrossFocusChanges();
        System.out.println("PASS zombies label projection compat");
    }

    private static void adjacentBoxesStayReadable() {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(90), 800.0F / 600.0F, 0.1F, 100.0F);
        ZombiesObjectLabelLayout layout = new ZombiesObjectLabelLayout();
        ZombiesObjectLabelLayout.Rect viewport = new ZombiesObjectLabelLayout.Rect(0, 0, 800, 600);
        List<ZombiesObjectLabelLayout.Candidate> candidates = List.of(
                label(projection, "left", -1, -5, false),
                label(projection, "middle", 0, -5, false),
                label(projection, "right", 1, -5, false));
        require(candidates.get(0).options().get(0).bounds().overlaps(candidates.get(1).options().get(0).bounds()),
                "adjacent box labels must actually overlap before packing");
        List<ZombiesObjectLabelLayout.Placement> initial = layout.arrange(candidates, List.of(), viewport, 1_000);
        require(initial.size() == 3, "three adjacent boxes should fit within six upward slots");
        assertSeparated(initial, List.of());
        for (String focused : List.of("right", "left")) {
            candidates = List.of(label(projection, "left", -1, -5, "left".equals(focused)),
                    label(projection, "middle", 0, -5, false),
                    label(projection, "right", 1, -5, "right".equals(focused)));
            List<ZombiesObjectLabelLayout.Placement> placed = layout.arrange(candidates, List.of(), viewport, 1_016);
            assertFocusedVisible(placed, focused);
            assertSeparated(placed, List.of());
        }
    }

    private static void labelsAvoidProjectedWeaponAcrossFocusChanges() {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(90), 800.0F / 600.0F, 0.1F, 100.0F);
        ZombiesObjectLabelLayout.Rect gun = ZombiesLabelProjection.projectBox(
                projection, -1.25, -0.7, -6.25, 1.25, 0.7, -3.75, 800, 600, true);
        require(gun != null, "weapon footprint must project");
        List<ZombiesObjectLabelLayout.Rect> obstacles = List.of(gun);
        ZombiesObjectLabelLayout layout = new ZombiesObjectLabelLayout();
        ZombiesObjectLabelLayout.Rect viewport = new ZombiesObjectLabelLayout.Rect(0, 0, 800, 600);
        long now = 2_000;
        for (String focused : List.of("", "back", "front")) {
            List<ZombiesObjectLabelLayout.Candidate> candidates = List.of(
                    label(projection, "front", 0, -6, "front".equals(focused)),
                    label(projection, "middle", 0, -7, false),
                    label(projection, "back", 0, -8, "back".equals(focused)));
            require(candidates.get(0).options().get(0).bounds().overlaps(gun),
                    "weapon should block the original label position");
            List<ZombiesObjectLabelLayout.Placement> placed = layout.arrange(candidates, obstacles, viewport, now);
            require(!placed.isEmpty(), "upward slots should retain a readable label above the weapon");
            if (!focused.isEmpty()) {
                assertFocusedVisible(placed, focused);
            }
            assertSeparated(placed, obstacles);
            now += 16;
        }
    }

    private static ZombiesObjectLabelLayout.Candidate label(Matrix4f projection, String id, float x, float z,
                                                              boolean focused) {
        Matrix4f labelMatrix = new Matrix4f(projection).translate(x, 0, z).scale(-0.035F, -0.035F, 0.035F);
        List<ZombiesObjectLabelLayout.Option> options = new ArrayList<>();
        for (boolean compact : List.of(false, true)) {
            for (int slot = 0; slot < 6; slot++) {
                Matrix4f shifted = new Matrix4f(labelMatrix).translate(0, -12.0F * slot, 0);
                int halfWidth = compact ? 32 : 82;
                ZombiesObjectLabelLayout.Rect bounds = ZombiesLabelProjection.projectBox(shifted,
                        -halfWidth, -12, 0, halfWidth, compact ? -2 : 10, 0, 800, 600, false);
                require(bounds != null, "representative label must project");
                options.add(new ZombiesObjectLabelLayout.Option(slot, compact, bounds));
            }
        }
        return new ZombiesObjectLabelLayout.Candidate(id, focused, Math.hypot(x, z), options);
    }

    private static void assertFocusedVisible(List<ZombiesObjectLabelLayout.Placement> placements, String id) {
        require(placements.stream().anyMatch(placement -> placement.id().equals(id) && !placement.option().compact()),
                "focused label must immediately remain visible with full text: " + id);
    }

    private static void assertSeparated(List<ZombiesObjectLabelLayout.Placement> placements,
                                        List<ZombiesObjectLabelLayout.Rect> obstacles) {
        for (int index = 0; index < placements.size(); index++) {
            ZombiesObjectLabelLayout.Rect bounds = placements.get(index).option().bounds();
            for (int other = index + 1; other < placements.size(); other++) {
                require(!bounds.overlaps(placements.get(other).option().bounds()),
                        "visible projected labels must not overlap");
            }
            for (ZombiesObjectLabelLayout.Rect obstacle : obstacles) {
                require(!bounds.overlaps(obstacle), "visible labels must not overlap the projected weapon");
            }
        }
    }

    private static ZombiesObjectLabelLayout.Rect project(
            Matrix4f matrix, double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
            boolean clipNearPlane
    ) {
        return ZombiesLabelProjection.projectBox(matrix, minX, minY, minZ, maxX, maxY, maxZ, 800, 400, clipNearPlane);
    }

    private static void assertRect(ZombiesObjectLabelLayout.Rect actual, double left, double top, double right,
                                   double bottom, String message) {
        require(actual != null, message + ": missing rectangle");
        require(Math.abs(actual.left() - left) <= TOLERANCE && Math.abs(actual.top() - top) <= TOLERANCE
                        && Math.abs(actual.right() - right) <= TOLERANCE && Math.abs(actual.bottom() - bottom) <= TOLERANCE,
                message + ": unexpected rectangle " + actual);
    }

    private static void assertNull(Object value, String message) {
        require(value == null, message + ": expected null");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
