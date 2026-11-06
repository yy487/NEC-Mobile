# M88 changes in NEC Mobile

NEC Mobile includes source from M88 by cisc, Copyright (C) 1998, 2003, using the rururutan/m88 fork. The original M88 and FMGEN notices accompany this source. [M88 notice](../../app/src/main/assets/licenses/M88-original-readme.txt) · [FMGEN notice](../../app/src/main/assets/licenses/M88-FMGEN-readme.txt)

The Android build uses the C++ Z80 engine, machine devices, shared code, and interface headers selected by the native build. [Build definition](../../native/pc88/CMakeLists.txt)

NEC Mobile adaptations include the following changes.

- Android file I/O, display, audio, keyboard, and mouse integration in the native platform layer and adapter.
- Compiler portability fixes for device callback tables, object deletion, and palette data.
- Correct CRTC read-port mapping at 0x50 and 0x51.
- Initialized OPN audio sampling rate and corrected the audio conversion ring-buffer wrap condition.
- Initialized mouse protocol state and connected the existing mouse hardware interface to Android input.
- USART MIDI output forwarding through C2/C3 and initialized transmit/receive enable state.
- Device-state save failure propagation and sound-buffer and timing reset after loading a state.

The native adapter adds D88 internal-disk selection, machine configuration, save-state file handling, and shutdown ordering for the Android lifecycle. The preparation script records the changes applied to the vendor source. [Adapter](../../native/pc88/adapter.cpp) · [Preparation script](../../scripts/prepare_m88.py)
