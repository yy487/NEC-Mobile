package org.necmobile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class D88Check {
    private static byte[] disk(int count) {
        byte[] bytes = new byte[688 * count];
        for (int i = 0; i < count; i++) { bytes[i * 688 + 28] = (byte) 0xb0; bytes[i * 688 + 29] = 2; }
        return bytes;
    }
    private static void reject(Path file, byte[] bytes) throws Exception {
        Files.write(file, bytes);
        try { D88.titles(file.toFile()); throw new AssertionError("Accepted invalid container"); }
        catch (IOException expected) { }
    }
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempFile("nec-d88-", ".d88");
        try {
            byte[] two = disk(2); two[0] = 'A'; two[688] = 'B';
            Files.write(file, two);
            if (!D88.titles(file.toFile()).equals(Arrays.asList("A", "B"))) throw new AssertionError("Disk boundaries or titles");
            Files.write(file, disk(64));
            if (D88.titles(file.toFile()).size() != 64) throw new AssertionError("64 disk boundary");
            reject(file, disk(65)); reject(file, new byte[0]); reject(file, new byte[32]);
            reject(file, Arrays.copyOf(two, two.length - 1));
            byte[] zero = disk(1); Arrays.fill(zero, 28, 32, (byte) 0); reject(file, zero);
            byte[] raw = new byte[1024]; System.arraycopy("M88 RawDiskImage".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,raw,0,16);
            Files.write(file, raw);
            if (D88.titles(file.toFile()).size() != 1) throw new AssertionError("Raw image support");
            for (int i=0;i<args.length;i+=2) {
                int actual = D88.titles(Path.of(args[i]).toFile()).size();
                if (actual != Integer.parseInt(args[i+1])) throw new AssertionError(args[i]);
                System.out.println(Path.of(args[i]).getFileName() + ": " + actual + " disks");
            }
            System.out.println("D88 header checks passed");
        } finally { Files.deleteIfExists(file); }
    }
}
