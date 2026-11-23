package com.skycop.sim

import com.skycop.sim.core.Game
import com.skycop.sim.core.Settings

/** Keeps a flight alive across a switch to live video or an app-icon relaunch, until the pilot exits. */
internal object Session {
    val settings = Settings()

    @Volatile var game: Game? = null

    /** True after X, so the flight resumes without a menu in the way. */
    @Volatile var switchedToLive = false

    fun end() {
        game = null
        switchedToLive = false
    }
}
