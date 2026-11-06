# TinySoundFont

Vendored `tsf.h`, v0.9, from https://github.com/schellingb/TinySoundFont on 2026-11-04.
The MIT notice is retained in the header and in the application's license assets.
The source archive includes this header; no additional download is required.

NEC Mobile validates RIFF bounds, SF2 table references and sample ranges before loading.
The host uses a bounded 128-voice synthesizer and mixes PCM on the emulation thread.
No SF2 / SF3 / DLS or instrument sample resources are shipped with the application.
