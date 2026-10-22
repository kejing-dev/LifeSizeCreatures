package io.embodit.lifesize.ui.room.debug

/**
 * Seams for the debug receiver (QA station). Nothing here is product UI: the hooks are only driven by
 * `src/debug`. In a release build no receiver exists and [enabled] stays false.
 */
object Probe {
    /** Set once at start-up from the debuggable flag of the app. */
    @Volatile var enabled: Boolean = false

    /** Debug `slowload=1`: a model counts as loaded only 6 s after it is asked for (state S12). */
    @Volatile var slowLoad: Boolean = false

    /** What the room scene exposes to the debug receiver while the Stage is open. */
    interface Host {
        /** `POSE ...`, `PANEL ...`, `ANIMAL ...` lines from the scene. */
        fun sceneLines(what: String): List<String>

        /** A tap on the floor at Stage (x, z), as if the floor catcher had been pinched. */
        fun tapFloor(x: Float, z: Float)

        /** A point on the floor [aheadM] in front of the viewer (debug placing). */
        fun floorAhead(aheadM: Float, sideM: Float): Pair<Float, Float>?

        /** Drag an animal by (dx, dz) metres over [ms] (S9 capture). */
        fun drag(uid: Int, dx: Float, dz: Float, ms: Long)

        /** Show the placing ghost at Stage (x, z) as if a controller ray pointed there. */
        fun ghostAt(x: Float, z: Float)
    }

    @Volatile var host: Host? = null
}
