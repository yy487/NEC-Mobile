#include "../../native/machine_config.h"
#include <iostream>
#include <stdexcept>
static void require(bool valid) { if (!valid) throw std::runtime_error("Machine config check failed"); }
int main(int argc,char** argv) {
    if (argc!=2) return 2;
    auto settings=readMachineConfig(argv[1]);
    require(machineText(settings,"np2kai_SNDboard","")=="PC9801-26K + 86");
    require(machineInt(settings,"np2kai_volume_F",64,0,128)==32);
    require(machineInt(settings,"output_volume",100,0,100)==75);
    require(machineInt(settings,"m88_fm",0,-40,20)==-12);
    require(machineInt(settings,"invalid",7,0,100)==7);
    require(machineInt(settings,"outside",7,0,100)==7);
    require(machineText(settings,"missing","OPNA")=="OPNA");
    std::cout<<"Native machine config: selected board, volumes, bounds and defaults passed\n";
}
