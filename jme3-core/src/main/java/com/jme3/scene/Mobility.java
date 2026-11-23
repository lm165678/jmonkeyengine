package com.jme3.scene;

/** Authored movement policy used by lighting and asset tools. */
public enum Mobility {
    /** Resolve the policy automatically or from the parent spatial. */
    Inherit,
    /** The world transform and shape are fixed. */
    Static,
    /** The world transform or shape may change. */
    Dynamic,
    /** World position and scale stay fixed, but rotation may change. */
    LocalDynamic
}
