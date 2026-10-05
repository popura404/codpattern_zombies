package com.cdp.codpattern.event.client.zombies;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Captures emitted geometry without a Minecraft client or OpenGL context. */
public final class ZombiesMysteryBoxBeamCompatTest {
    private static final double EPSILON = 0.0001D;
    private static final int SAMPLE_COLOR = 0xFF3B82F6;

    public static void main(String[] args) throws Exception {
        colorsFollowTheCurrentDraw();
        for (boolean glow : new boolean[]{false, true}) {
            geometryStaysCenteredAndStill(glow);
            textureFlowsUpwardAcrossTickAndPeriodBoundaries(glow);
            posesAndCameraTranslationsStayIndependent(glow);
        }
        System.out.println("PASS zombies mystery box beam compat");
    }

    private static void colorsFollowTheCurrentDraw() {
        String[] rarities = {"common", "rare", "epic", "legendary", "custom", "", null};
        int[] expected = {0xFF22C55E, 0xFF3B82F6, 0xFFA855F7, 0xFFFF9800,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF};
        // Repeat the full lifecycle so a prior reward cannot tint another draw.
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < rarities.length; i++) {
                require(ZombiesMysteryBoxBeam.color("ROLLING", rarities[i]) == 0,
                        "rolling must hide the beam for every eventual reward");
                require(ZombiesMysteryBoxBeam.color("CLAIMABLE", rarities[i]) == expected[i],
                        "claimable color must use the current reward: " + rarities[i]);
                for (String phase : new String[]{"COOLDOWN", "IDLE", "", "UNKNOWN", null}) {
                    require(ZombiesMysteryBoxBeam.color(phase, rarities[i]) == 0xFFFFFFFF,
                            "non-reward phase must use white: " + phase);
                }
            }
        }
        require(ZombiesMysteryBoxBeam.color("CLAIMABLE", "  RaRe  ") == 0xFF3B82F6,
                "rarity aliases retain existing case and whitespace normalization");
    }

    private static void geometryStaysCenteredAndStill(boolean glow) throws Exception {
        PoseStack.Pose pose = pose(new Matrix4f());
        List<Vertex> reference = emit(pose, glow, 0L, 0.0F);
        require(reference.size() == 16, "a layer must contain exactly four side quads");
        double radius = glow ? 0.35D : 0.12D / Math.sqrt(2.0D);
        int bottomCount = 0;
        int topCount = 0;
        double sumX = 0;
        double sumZ = 0;
        for (Vertex vertex : reference) {
            close(Math.abs(vertex.x()), radius, "every corner X must use the layer half-width");
            close(Math.abs(vertex.z()), radius, "every corner Z must use the layer half-width");
            if (Math.abs(vertex.y()) < EPSILON) bottomCount++;
            else if (Math.abs(vertex.y() - 24.0D) < EPSILON) topCount++;
            else throw new AssertionError("vertices must span exactly Y=0 to Y=24: " + vertex);
            require(vertex.red() == 0x3B && vertex.green() == 0x82 && vertex.blue() == 0xF6,
                    "emitted RGB must match the requested rarity");
            require(Math.abs(vertex.alpha() - (glow ? 255.0D * 0.125D : 255.0D)) <= 1.0D,
                    "core must be opaque and glow must use one-eighth alpha");
            sumX += vertex.x();
            sumZ += vertex.z();
        }
        require(bottomCount == 8 && topCount == 8, "each side must have two bottom and two top vertices");
        close(sumX, 0.0D, "beam X center must match the supplied pose origin");
        close(sumZ, 0.0D, "beam Z center must match the supplied pose origin");
        Set<String> sides = new HashSet<>();
        for (int offset = 0; offset < reference.size(); offset += 4) {
            List<Vertex> quad = reference.subList(offset, offset + 4);
            assertLayerWinding(quad, glow);
            boolean constantX = quad.stream().allMatch(v -> v.x() == quad.get(0).x());
            boolean constantZ = quad.stream().allMatch(v -> v.z() == quad.get(0).z());
            require(constantX != constantZ, "each quad must occupy one side of the column");
            sides.add(constantX ? "x:" + Math.signum(quad.get(0).x()) : "z:" + Math.signum(quad.get(0).z()));
            double minV = quad.stream().mapToDouble(Vertex::v).min().orElseThrow();
            double maxV = quad.stream().mapToDouble(Vertex::v).max().orElseThrow();
            close(maxV - minV, glow ? 24.0D : 100.0D, "layer texture height must remain unchanged");
        }
        require(sides.size() == 4, "all four column sides must render once");
        for (long tick : new long[]{1L, 17L, 39L, 40L, 23999L, 24000L, 9_999_999_999L}) {
            for (float partial : new float[]{0.0F, 0.25F, 0.9F}) {
                List<Vertex> later = emit(pose, glow, tick, partial);
                assertSamePositions(reference, later, "time must animate UVs without rotating or bobbing the beam");
            }
        }
    }

    private static void assertLayerWinding(List<Vertex> quad, boolean glow) {
        Vertex a = quad.get(0);
        Vertex b = quad.get(1);
        Vertex c = quad.get(2);
        double abX = b.x() - a.x(), abY = b.y() - a.y(), abZ = b.z() - a.z();
        double acX = c.x() - a.x(), acY = c.y() - a.y(), acZ = c.z() - a.z();
        double normalX = abY * acZ - abZ * acY;
        double normalZ = abX * acY - abY * acX;
        double centerX = quad.stream().mapToDouble(Vertex::x).average().orElseThrow();
        double centerZ = quad.stream().mapToDouble(Vertex::z).average().orElseThrow();
        double outward = normalX * centerX + normalZ * centerZ;
        require(glow ? outward < -EPSILON : outward > EPSILON,
                "core faces outward and the glow renders the far-side shell: " + quad);
    }

    private static void textureFlowsUpwardAcrossTickAndPeriodBoundaries(boolean glow) throws Exception {
        PoseStack.Pose identity = pose(new Matrix4f());
        List<Vertex> first = emit(identity, glow, 12L, 0.25F);
        List<Vertex> second = emit(identity, glow, 12L, 0.5F);
        List<Vertex> third = emit(identity, glow, 12L, 0.75F);
        double step = wrappedDelta(second.get(0).v() - first.get(0).v());
        require(Math.abs(step) > EPSILON, "partial ticks must advance the texture between game ticks");
        double bottomV = first.stream().filter(v -> v.y() == 0.0D).findFirst().orElseThrow().v();
        double topV = first.stream().filter(v -> v.y() == 24.0D).findFirst().orElseThrow().v();
        require(-step / (topV - bottomV) > 0.0D, "texture features must move upward along the column");
        for (int i = 0; i < first.size(); i++) {
            close(wrappedDelta(second.get(i).v() - first.get(i).v()), step,
                    "all vertices must share the same UV motion");
            close(wrappedDelta(third.get(i).v() - second.get(i).v()), step,
                    "partial-tick interpolation must be continuous");
        }
        for (long boundary : new long[]{13L, 40L, 24000L, 10_000_000_000L}) {
            List<Vertex> before = emit(identity, glow, boundary - 1L, 0.75F);
            List<Vertex> after = emit(identity, glow, boundary, 0.0F);
            for (int i = 0; i < before.size(); i++) {
                close(wrappedDelta(after.get(i).v() - before.get(i).v()), step,
                        "UV motion must remain continuous modulo texture repeats at tick " + boundary);
            }
        }
    }

    private static void posesAndCameraTranslationsStayIndependent(boolean glow) throws Exception {
        Matrix4f firstTransform = new Matrix4f().translate(12.5F, 64.0F, -8.5F).rotateY(0.37F);
        Matrix4f secondTransform = new Matrix4f().translate(-4.5F, 71.0F, 29.5F).rotateY(-0.82F);
        PoseStack.Pose firstPose = pose(firstTransform);
        PoseStack.Pose secondPose = pose(secondTransform);
        Matrix4f originalFirst = new Matrix4f(firstPose.pose());
        Matrix3f originalNormal = new Matrix3f(firstPose.normal());
        List<Vertex> local = emit(pose(new Matrix4f()), glow, 35L, 0.5F);
        List<Vertex> first = emit(firstPose, glow, 35L, 0.5F);
        List<Vertex> second = emit(secondPose, glow, 35L, 0.5F);
        assertTransformed(local, first, firstTransform, "first box must use its own pose");
        assertTransformed(local, second, secondTransform, "second box must use its own pose");
        assertSamePositions(first, emit(firstPose, glow, 35L, 0.5F),
                "rendering another box must not change the first box");
        require(firstPose.pose().equals(originalFirst) && firstPose.normal().equals(originalNormal),
                "emission must not mutate the caller's position or normal matrices");
        require(secondPose.pose().equals(secondTransform), "emission must not mutate the second box pose");

        Matrix4f cameraMoved = new Matrix4f().translation(-19.0F, 2.0F, -7.0F).mul(firstTransform);
        List<Vertex> shifted = emit(pose(cameraMoved), glow, 35L, 0.5F);
        for (int i = 0; i < first.size(); i++) {
            close(shifted.get(i).x() - first.get(i).x(), -19.0D, "camera X movement must apply exactly once");
            close(shifted.get(i).y() - first.get(i).y(), 2.0D, "camera Y movement must apply exactly once");
            close(shifted.get(i).z() - first.get(i).z(), -7.0D, "camera Z movement must apply exactly once");
            close(shifted.get(i).v(), first.get(i).v(), "camera movement must not alter texture phase");
        }
    }

    private static void assertTransformed(List<Vertex> local, List<Vertex> transformed, Matrix4f matrix, String message) {
        require(local.size() == transformed.size(), message + ": vertex count");
        for (int i = 0; i < local.size(); i++) {
            Vertex vertex = local.get(i);
            Vector3f expected = new Vector3f((float) vertex.x(), (float) vertex.y(), (float) vertex.z()).mulPosition(matrix);
            Vertex actual = transformed.get(i);
            close(actual.x(), expected.x(), message + ": X");
            close(actual.y(), expected.y(), message + ": Y");
            close(actual.z(), expected.z(), message + ": Z");
        }
    }

    private static void assertSamePositions(List<Vertex> expected, List<Vertex> actual, String message) {
        require(expected.size() == actual.size(), message + ": vertex count");
        for (int i = 0; i < expected.size(); i++) {
            close(actual.get(i).x(), expected.get(i).x(), message + ": X");
            close(actual.get(i).y(), expected.get(i).y(), message + ": Y");
            close(actual.get(i).z(), expected.get(i).z(), message + ": Z");
        }
    }

    private static PoseStack.Pose pose(Matrix4f matrix) throws Exception {
        // PoseStack's constructor initializes Minecraft Util; the pose value itself only requires JOML.
        Constructor<PoseStack.Pose> constructor = PoseStack.Pose.class.getDeclaredConstructor(Matrix4f.class, Matrix3f.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new Matrix4f(matrix), matrix.normal(new Matrix3f()));
    }

    private static List<Vertex> emit(PoseStack.Pose pose, boolean glow, long tick, float partialTick) {
        CapturingVertices capture = new CapturingVertices();
        ZombiesMysteryBoxBeam.emitLayer(pose, capture, glow, SAMPLE_COLOR, tick, partialTick);
        return List.copyOf(capture.vertices);
    }

    private static double wrappedDelta(double delta) { return delta - Math.rint(delta); }

    private static void close(double actual, double expected, String message) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) < EPSILON,
                message + ": expected " + expected + ", got " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Vertex(double x, double y, double z, int red, int green, int blue, int alpha, float u, float v) { }

    private static final class CapturingVertices implements VertexConsumer {
        private final List<Vertex> vertices = new ArrayList<>();
        private double x, y, z;
        private int red, green, blue, alpha;
        private float u, v;
        @Override public VertexConsumer vertex(double x, double y, double z) { this.x = x; this.y = y; this.z = z; return this; }
        @Override public VertexConsumer color(int red, int green, int blue, int alpha) {
            this.red = red; this.green = green; this.blue = blue; this.alpha = alpha; return this;
        }
        @Override public VertexConsumer uv(float u, float v) { this.u = u; this.v = v; return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public void endVertex() { vertices.add(new Vertex(x, y, z, red, green, blue, alpha, u, v)); }
        @Override public void defaultColor(int red, int green, int blue, int alpha) { color(red, green, blue, alpha); }
        @Override public void unsetDefaultColor() { }
    }
}
