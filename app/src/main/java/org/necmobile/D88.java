package org.necmobile;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class D88 {
    static List<String> titles(File file) throws IOException {
        List<String> titles = new ArrayList<>();
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long end = input.length(), offset = 0;
            byte[] header = new byte[32];
            while (offset < end) {
                if (titles.size() == 64 || end - offset < header.length) throw invalid(file);
                input.seek(offset); input.readFully(header);
                if (offset == 0 && new String(header, 0, 16, StandardCharsets.US_ASCII).equals("M88 RawDiskImage")) {
                    titles.add("Raw Disk"); return titles;
                }
                long size = 0;
                for (int i = 0; i < 4; i++) size |= (header[28 + i] & 255L) << (8 * i);
                int type = header[27] & 255;
                if (size < 0x170 || size > 4 * 1024 * 1024 || size > end - offset ||
                        (type != 0 && type != 0x10 && type != 0x20)) throw invalid(file);
                int length = 0;
                while (length < 17 && header[length] != 0) length++;
                titles.add(new String(header, 0, length, Charset.forName("windows-31j")).replaceAll("[\\p{Cntrl}]", " ").trim());
                offset += size;
            }
        }
        if (titles.isEmpty()) throw invalid(file);
        return titles;
    }
    private static IOException invalid(File file) { return new IOException("D88 磁盘结构无效或超过 64 张盘：" + file.getName()); }
}
