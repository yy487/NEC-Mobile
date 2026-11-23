package org.necmobile;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ExportOptions {
    private static String quote(String text) { return "\"" + text.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n") + "\""; }
    private static String list(String[] values) { List<String> result=new ArrayList<>(); for(String value:values)result.add(quote(value)); return "["+String.join(",",result)+"]"; }
    public static void main(String[] args) throws Exception {
        List<String> platforms=new ArrayList<>();
        for(int platform:new int[]{88,98}) {
            List<String> options=new ArrayList<>();
            for(MachineOptions.Option option:MachineOptions.all(platform)) options.add("{\"key\":"+quote(option.key)+",\"label\":"+quote(option.label)+",\"group\":"+option.group+",\"fallback\":"+quote(option.fallback)+",\"values\":"+list(option.values)+",\"names\":"+list(option.names)+",\"min\":"+option.min+",\"max\":"+option.max+"}");
            platforms.add(quote(Integer.toString(platform))+ ":["+String.join(",",options)+"]");
        }
        Files.writeString(Path.of(args[0]),"{\"modes\":"+list(MachineOptions.PC88_BOOT_MODES)+",\"platforms\":{"+String.join(",",platforms)+"}}",StandardCharsets.UTF_8);
    }
}
