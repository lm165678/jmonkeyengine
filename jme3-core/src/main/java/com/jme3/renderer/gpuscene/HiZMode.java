package com.jme3.renderer.gpuscene;

/** Main-view occluder depth scheduling; independent of shadow rendering. */
public enum HiZMode {
    /** Draw all eligible current-frame occluders, then query current Hi-Z. Default. */
    SAME_FRAME,
    /**
     * Query history to reduce the current occluder prepass, then retest all
     * candidates against current depth. History never directly hides final geometry.
     * This retains a reduced prepass, not reuse of unfiltered final scene depth.
     * Missing history falls back to SAME_FRAME for that frame.
     */
    HISTORY_TWO_PHASE
}
