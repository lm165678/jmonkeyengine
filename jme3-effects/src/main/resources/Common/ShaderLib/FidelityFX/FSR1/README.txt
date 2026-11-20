AMD FidelityFX FSR 1.0, MIT license retained in each file.
Source: https://github.com/GPUOpen-Effects/FidelityFX-FSR
Commit: a21ffb8f6c13233ba336352bdff293894c706575
Original .h sources are retained. .glsllib copies remove conditional #extension lines because jME extracts them outside their guards; the adapter uses the FP32 path with GLSL430.
The material adapter uses gamma-2 working values inside EASU/RCAS and returns display-linear RGB.
