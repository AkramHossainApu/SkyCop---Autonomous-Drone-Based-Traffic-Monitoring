package com.skycop.sim.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A real Dhaka neighbourhood, read from a .skymap file made by maps/build_maps.py from
 * OpenStreetMap data. Coordinates are meters from the map center: x east, z south.
 * Map data (c) OpenStreetMap contributors, ODbL.
 */
class MapData(bytes: ByteArray) {
    companion object {
        const val UNIT = 0.05f
        /** The maps the simulator ships, in menu order, with their file names. */
        val FILES = arrayOf("bashundhara", "mohammadpur")
        val TITLES = arrayOf("BASHUNDHARA R/A", "MOHAMMADPUR")

        // Building kinds.
        const val APARTMENT = 0; const val HOUSE = 1; const val SHED = 2; const val COMMERCIAL = 3; const val TOWER = 4
        const val MALL = 5; const val EDUCATION = 6; const val HOSPITAL = 7; const val MOSQUE = 8; const val INDUSTRIAL = 9
        const val CONSTRUCTION = 10
        // Area kinds.
        const val GRASS = 0; const val WATER = 1; const val PITCH = 2; const val PAVED = 3; const val GRAVEYARD = 4; const val BARE = 5
        // Ribbon kinds.
        const val FOOTPATH = 0; const val CANAL = 1; const val MINOR_ROAD = 2
        // Landmark kinds.
        const val SEVEN_DOMES = 0; const val MALL_SIGN = 1; const val CAMPUS = 2; const val HOTEL = 3
    }

    val title: String
    val half: Float

    val buildingCount: Int
    val buildingKind: IntArray
    val floors: IntArray
    val floorHeight: FloatArray
    val tint: IntArray
    val shops: BooleanArray
    /** Oriented box per building: center, angle, half sizes. */
    val boxX: FloatArray; val boxZ: FloatArray; val boxAngle: FloatArray; val boxHalfX: FloatArray; val boxHalfZ: FloatArray
    val buildingStart: IntArray
    val buildingX: FloatArray; val buildingZ: FloatArray

    val areaCount: Int
    val areaKind: IntArray
    val areaStart: IntArray
    val areaX: FloatArray; val areaZ: FloatArray

    val ribbonCount: Int
    val ribbonKind: IntArray
    val ribbonWidth: FloatArray
    val ribbonStart: IntArray
    val ribbonX: FloatArray; val ribbonZ: FloatArray

    val nodeCount: Int
    val nodeX: FloatArray; val nodeZ: FloatArray; val nodeY: FloatArray
    val nodeFlags: IntArray

    val edgeCount: Int
    val edgeA: IntArray; val edgeB: IntArray
    val edgeClass: IntArray
    val lanesForward: IntArray; val lanesBackward: IntArray
    val edgeWidth: FloatArray
    val edgeBridge: BooleanArray; val edgeTraffic: BooleanArray
    val edgeStart: IntArray
    val edgeX: FloatArray; val edgeZ: FloatArray; val edgeY: FloatArray

    val dockCount: Int
    val dockX: FloatArray; val dockZ: FloatArray
    val home: Int

    val landmarkCount: Int
    val landmarkKind: IntArray
    val landmarkX: FloatArray; val landmarkZ: FloatArray
    val landmarkBuilding: IntArray
    val landmarkName: Array<String>

    init {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8).also { b.get(it) }
        require(String(magic, Charsets.US_ASCII) == "SKYMAP01") { "Not a SkyCop map" }
        fun u8() = b.get().toInt() and 255
        fun u16() = b.short.toInt() and 0xffff
        fun s16() = b.short.toInt()
        fun text(length: Int) = ByteArray(length).also { b.get(it) }.toString(Charsets.UTF_8)
        title = text(u16())
        half = b.float
        b.float; b.float

        buildingCount = b.int
        buildingKind = IntArray(buildingCount); floors = IntArray(buildingCount); floorHeight = FloatArray(buildingCount)
        tint = IntArray(buildingCount); shops = BooleanArray(buildingCount)
        boxX = FloatArray(buildingCount); boxZ = FloatArray(buildingCount); boxAngle = FloatArray(buildingCount)
        boxHalfX = FloatArray(buildingCount); boxHalfZ = FloatArray(buildingCount)
        buildingStart = IntArray(buildingCount + 1)
        val bx = ArrayList<Float>(); val bz = ArrayList<Float>()
        for (i in 0 until buildingCount) {
            buildingKind[i] = u8(); floors[i] = u8(); floorHeight[i] = u8() / 10f; tint[i] = u8(); shops[i] = u8() != 0
            boxX[i] = s16() * UNIT; boxZ[i] = s16() * UNIT; boxAngle[i] = s16() / 10000f
            boxHalfX[i] = u16() * UNIT; boxHalfZ[i] = u16() * UNIT
            val n = u16()
            repeat(n) { bx.add(s16() * UNIT); bz.add(s16() * UNIT) }
            buildingStart[i + 1] = bx.size
        }
        buildingX = bx.toFloatArray(); buildingZ = bz.toFloatArray()

        areaCount = b.int
        areaKind = IntArray(areaCount); areaStart = IntArray(areaCount + 1)
        val ax = ArrayList<Float>(); val az = ArrayList<Float>()
        for (i in 0 until areaCount) {
            areaKind[i] = u8()
            val n = u16()
            repeat(n) { ax.add(s16() * UNIT); az.add(s16() * UNIT) }
            areaStart[i + 1] = ax.size
        }
        areaX = ax.toFloatArray(); areaZ = az.toFloatArray()

        ribbonCount = b.int
        ribbonKind = IntArray(ribbonCount); ribbonWidth = FloatArray(ribbonCount); ribbonStart = IntArray(ribbonCount + 1)
        val rx = ArrayList<Float>(); val rz = ArrayList<Float>()
        for (i in 0 until ribbonCount) {
            ribbonKind[i] = u8(); ribbonWidth[i] = u8() / 10f
            val n = u16()
            repeat(n) { rx.add(s16() * UNIT); rz.add(s16() * UNIT) }
            ribbonStart[i + 1] = rx.size
        }
        ribbonX = rx.toFloatArray(); ribbonZ = rz.toFloatArray()

        nodeCount = b.int
        nodeX = FloatArray(nodeCount); nodeZ = FloatArray(nodeCount); nodeY = FloatArray(nodeCount); nodeFlags = IntArray(nodeCount)
        for (i in 0 until nodeCount) {
            nodeX[i] = s16() * UNIT; nodeZ[i] = s16() * UNIT; nodeY[i] = s16() / 10f; nodeFlags[i] = u8()
        }

        edgeCount = b.int
        edgeA = IntArray(edgeCount); edgeB = IntArray(edgeCount); edgeClass = IntArray(edgeCount)
        lanesForward = IntArray(edgeCount); lanesBackward = IntArray(edgeCount); edgeWidth = FloatArray(edgeCount)
        edgeBridge = BooleanArray(edgeCount); edgeTraffic = BooleanArray(edgeCount); edgeStart = IntArray(edgeCount + 1)
        val ex = ArrayList<Float>(); val ez = ArrayList<Float>(); val ey = ArrayList<Float>()
        for (i in 0 until edgeCount) {
            edgeA[i] = u16(); edgeB[i] = u16(); edgeClass[i] = u8(); lanesForward[i] = u8(); lanesBackward[i] = u8()
            edgeWidth[i] = u8() / 5f; edgeBridge[i] = u8() != 0; edgeTraffic[i] = u8() != 0
            val n = u16()
            repeat(n) { ex.add(s16() * UNIT); ez.add(s16() * UNIT); ey.add(s16() / 10f) }
            edgeStart[i + 1] = ex.size
        }
        edgeX = ex.toFloatArray(); edgeZ = ez.toFloatArray(); edgeY = ey.toFloatArray()

        dockCount = b.int
        dockX = FloatArray(dockCount); dockZ = FloatArray(dockCount)
        for (i in 0 until dockCount) {
            dockX[i] = s16() * UNIT; dockZ[i] = s16() * UNIT; u16()
        }
        home = u16()

        landmarkCount = b.int
        landmarkKind = IntArray(landmarkCount); landmarkX = FloatArray(landmarkCount); landmarkZ = FloatArray(landmarkCount)
        landmarkBuilding = IntArray(landmarkCount)
        landmarkName = Array(landmarkCount) {
            landmarkKind[it] = u8(); landmarkX[it] = s16() * UNIT; landmarkZ[it] = s16() * UNIT
            landmarkBuilding[it] = u16()
            text(u16())
        }
    }
}
