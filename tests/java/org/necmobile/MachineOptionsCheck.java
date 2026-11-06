package org.necmobile;

import java.util.*;

public final class MachineOptionsCheck {
    static void require(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        for (int platform : new int[]{88,98}) {
            Set<String> keys=new HashSet<>();
            for (MachineOptions.Option option : MachineOptions.all(platform)) {
                require(keys.add(option.key)); require(option.accepts(option.fallback));
                if (!option.range()) require(option.values.length==option.names.length);
                require(!option.accepts("invalid\nnp2kai_SNDboard=None"));
                option.display(option.fallback);
            }
            require(keys.size()<=64);
            require(MachineOptions.encode(platform,Map.of(),0).lines().count()==keys.size());
            require(MachineOptions.encode(platform,Map.of(),0).contains("midi_enabled=OFF\n"));
            require(MachineOptions.encode(platform,Map.of("midi_enabled","ON","midi_volume","1000"),0).contains("midi_volume=80\n"));
        }
        Map<String,String> first=new HashMap<>(), second=new HashMap<>();
        first.put("np2kai_SNDboard","PC9801-26K + 86"); first.put("np2kai_volume_F","32"); first.put("np2kai_ExMemory","9999");
        String selected=MachineOptions.encode(98,first,1), defaults=MachineOptions.encode(98,second,0);
        require(selected.contains("np2kai_SNDboard=PC9801-26K + 86\n"));
        require(selected.contains("np2kai_volume_F=32\n")); require(selected.contains("np2kai_clk_mult=8\n"));
        require(selected.contains("np2kai_ExMemory=3\n")); require(defaults.contains("np2kai_SNDboard=PC9801-86\n"));
        first.put("unknown","value"); require(!MachineOptions.encode(98,first,0).contains("unknown"));
        require(MachineOptions.encode(88,Map.of("m88_board","OPN","m88_fm","-12"),0).contains("m88_fm=-12\n"));
        System.out.println("Machine options: defaults, legacy speed, game isolation, audio selection, bounds and config encoding passed");
    }
}
