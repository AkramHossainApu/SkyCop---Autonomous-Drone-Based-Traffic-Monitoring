package com.skycop.sim

import com.skycop.sim.core.City
import com.skycop.sim.core.Game
import com.skycop.sim.core.MapData
import com.skycop.sim.core.Settings

/** Keeps a flight alive across a switch to live video or an app-icon relaunch, until the pilot exits. */
internal object Session {
    val settings = Settings()

    @Volatile var game: Game? = null

    /** True after X, so the flight resumes without a menu in the way. */
    @Volatile var switchedToLive = false

    /** The built city for the current map; building one takes a moment, so it is kept. */
    private var city: City? = null
    private var cityMap = -1

    fun city(index: Int, load: (Int) -> MapData): City {
        city?.let { if (cityMap == index) return it }
        city = null
        val built = City(load(index))
        city = built
        cityMap = index
        return built
    }

    fun end() {
        game = null
        switchedToLive = false
    }
}
