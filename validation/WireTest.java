import com.adwar.chargingcutoff.Wire;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HexFormat;

public final class WireTest {
    private static final HexFormat HEX = HexFormat.of();
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("encode")) {
            System.out.println(HEX.formatHex(Wire.command(Wire.START, 99, 0x11223344L, 0xaabbccddL, 1)));
            System.out.println(HEX.formatHex(Wire.command(Wire.REPORT, 100, 0x11223344L, 0xaabbccddL, 2)));
            System.out.println(HEX.formatHex(Wire.command(Wire.START, 50, 0x11223344L, 0xaabbccddL, 1)));
            return;
        }
        if (args.length > 0 && args[0].equals("decode")) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            for (int i = 0; i < 3; ++i) {
                Wire.Status status = Wire.decode(HEX.parseHex(reader.readLine()));
                check(status.nonce == 0x11223344L && status.session == 0xaabbccddL);
                check(status.state == (i == 0 ? Wire.CHARGING : Wire.ENDED));
                check(status.relayOn == (i == 0));
                check(status.percent == (i == 0 ? 99 : 100));
                check(status.sequence == (i == 0 ? 1 : 2));
                check(status.reason == (i == 0 ? 0 : 1));
            }
            check(reader.readLine() == null);
            System.out.println("PASS: Java commands -> C controller -> Java status (cutoff and restart latch)");
            return;
        }
        check(HEX.formatHex(Wire.command(Wire.START, 99, 0x11223344L, 0xaabbccddL, 1))
            .equals("0101630044332211ddccbbaa01000000"));
        check(HEX.formatHex(Wire.command(Wire.STOP, 255, 1, 2, 3))
            .equals("0103ff00010000000200000003000000"));
        rejects(() -> Wire.command(Wire.START, 101, 1, 1, 1));
        rejects(() -> Wire.command(Wire.START, -1, 1, 1, 1));
        rejects(() -> Wire.command(Wire.START, 50, 0, 1, 1));
        rejects(() -> Wire.command(Wire.START, 50, 1, 0, 1));
        rejects(() -> Wire.command(Wire.START, 50, 1, 1, 2));
        rejects(() -> Wire.command(Wire.REPORT, 50, 1, 1, 0x100000000L));
        rejects(() -> Wire.command(Wire.STOP, 50, 1, 1, 2));
        rejects(() -> Wire.decode(null));
        rejects(() -> Wire.decode(new byte[19]));
        byte[] good = HEX.parseHex("0101000144332211ddccbbaa0100000063000000");
        check(Wire.decode(good).relayOn);
        for (int offset : new int[] {0, 1, 2, 3, 16, 17, 18, 19}) {
            byte[] bad = good.clone(); bad[offset] = (byte)254;
            rejects(() -> Wire.decode(bad));
        }
        byte[] impossible = good.clone(); impossible[1] = (byte)Wire.ENDED;
        rejects(() -> Wire.decode(impossible));
        impossible[1] = (byte)Wire.CHARGING; impossible[16] = 100;
        rejects(() -> Wire.decode(impossible));
        System.out.println("PASS: Java wire golden vectors and malformed status rejection");
    }
}
