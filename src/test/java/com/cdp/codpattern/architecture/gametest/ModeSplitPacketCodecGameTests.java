package com.cdp.codpattern.architecture.gametest;

import com.cdp.codpattern.CodPattern;
import com.cdp.codpattern.app.zombies.deploy.ZombiesDeployObjectEditorCompatTest;
import com.cdp.codpattern.network.SyncThrowableInventoryPacket;
import com.cdp.codpattern.network.match.KillFeedPacket;
import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.LogTestReporter;
import net.minecraft.gametest.framework.TestReporter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.Locale;

/** Runtime-only complement to the pure-JVM Phase 0 packet fixture verifier. */
@GameTestHolder(CodPattern.MODID)
@PrefixGameTestTemplate(false)
public final class ModeSplitPacketCodecGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String EMPTY_TEMPLATE = "empty";
    private static final String BATCH = "mode_split_packet_codec";

    static {
        GlobalTestReporter.replaceWith(new NamedResultReporter());
    }

    private ModeSplitPacketCodecGameTests() {
    }

    @GameTest(template = EMPTY_TEMPLATE, batch = BATCH, timeoutTicks = 20, required = true)
    public static void itemStackPacketCodecsMatchGoldenBytes(GameTestHelper helper) {
        try {
            SyncThrowableInventoryPacket throwable = new SyncThrowableInventoryPacket(
                    new ItemStack[]{ItemStack.EMPTY, ItemStack.EMPTY}, 7);
            verifyRoundTrip(
                    "SyncThrowableInventoryPacket",
                    "000000000007",
                    throwable,
                    SyncThrowableInventoryPacket::encode,
                    SyncThrowableInventoryPacket::decode);

            KillFeedPacket killFeed = new KillFeedPacket(
                    "fixture", "fixture", ItemStack.EMPTY, true);
            verifyRoundTrip(
                    "KillFeedPacket",
                    "076669787475726507666978747572650001",
                    killFeed,
                    KillFeedPacket::encode,
                    KillFeedPacket::decode);
            helper.succeed();
        } catch (Throwable error) {
            helper.fail("Phase 0 ItemStack packet codec fixture failed: " + error);
        }
    }

    @GameTest(template = EMPTY_TEMPLATE, batch = BATCH, timeoutTicks = 40, required = true)
    public static void deployObjectEditorCompatExecutesInBootstrappedRuntime(GameTestHelper helper) {
        try {
            ZombiesDeployObjectEditorCompatTest.runAll();
            helper.succeed();
        } catch (Throwable error) {
            helper.fail("Zombies deploy object-editor runtime compatibility failed: " + error);
        }
    }

    private static <T> void verifyRoundTrip(
            String name,
            String expectedHex,
            T packet,
            Encoder<T> encoder,
            Decoder<T> decoder
    ) {
        byte[] encoded = encode(packet, encoder);
        require(expectedHex.equals(hex(encoded)), name + " canonical bytes drifted");

        FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.wrappedBuffer(encoded));
        T decoded;
        try {
            decoded = decoder.decode(input);
            require(input.readableBytes() == 0, name + " decoder left unread bytes");
        } finally {
            input.release();
        }
        require(Arrays.equals(encoded, encode(decoded, encoder)), name + " round trip drifted");
    }

    private static <T> byte[] encode(T packet, Encoder<T> encoder) {
        FriendlyByteBuf output = new FriendlyByteBuf(Unpooled.buffer());
        try {
            encoder.encode(packet, output);
            byte[] bytes = new byte[output.readableBytes()];
            output.getBytes(output.readerIndex(), bytes);
            return bytes;
        } finally {
            output.release();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface Encoder<T> {
        void encode(T packet, FriendlyByteBuf buffer);
    }

    @FunctionalInterface
    private interface Decoder<T> {
        T decode(FriendlyByteBuf buffer);
    }

    /** Preserves vanilla logging and adds one directly observable result line for every test. */
    private static final class NamedResultReporter implements TestReporter {
        private final TestReporter delegate = new LogTestReporter();

        @Override
        public void onTestFailed(GameTestInfo testInfo) {
            delegate.onTestFailed(testInfo);
            printResult(testInfo, "FAIL", testInfo.getError());
        }

        @Override
        public void onTestSuccess(GameTestInfo testInfo) {
            delegate.onTestSuccess(testInfo);
            printResult(testInfo, "PASS", null);
        }

        @Override
        public void finish() {
            delegate.finish();
        }

        private static void printResult(GameTestInfo testInfo, String result, Throwable error) {
            String requirement = testInfo.isRequired() ? "REQUIRED" : "OPTIONAL";
            String detail = error == null ? "-" : sanitize(error.getMessage());
            LOGGER.info("CODEXPATTERN_GAMETEST_RESULT\t{}\t{}\t{}\t{}",
                    testInfo.getTestName(), requirement, result, detail);
        }

        private static String sanitize(String value) {
            if (value == null || value.isBlank()) {
                return "<no-message>";
            }
            return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
        }
    }
}
