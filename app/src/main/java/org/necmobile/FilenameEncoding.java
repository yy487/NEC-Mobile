package org.necmobile;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;

final class FilenameEncoding {
    static String fromGbkToSjis(String name) {
        try {
            ByteBuffer bytes = Charset.forName("GBK").newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(name));
            String result = Charset.forName("windows-31j").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes).toString();
            if (!result.equals(name) && result.codePoints().anyMatch(c -> c >= 0x3040 && c <= 0x30ff) &&
                    result.chars().noneMatch(c -> c < 32 || "/\\:*?\"<>|".indexOf(c) >= 0)) return result;
        } catch (CharacterCodingException ignored) { }
        return name;
    }
}
