import Foundation

/// Time of day and weather (Environment.kt): the light, the sky, the haze, wet streets and gusts.
final class Environment {
  static let TIMES = ["DAY", "SUNSET", "NIGHT", "CYCLE"]
  static let WEATHERS = ["CLEAR", "MONSOON RAIN"]
  static let HOUR_SECONDS: Float = 40

  private(set) var hour: Float = 10
  var light: [Float] = [0.52, 0.6, 0.46]
  var lightColor: [Float] = [3.1, 2.8, 2.3]
  var skyTop: [Float] = [0.33, 0.52, 0.76]
  var skyLow: [Float] = [0.8, 0.8, 0.77]
  private(set) var night: Float = 0
  private(set) var rain: Float = 0
  private(set) var wet: Float = 0
  private(set) var fog: Float = 0.0021
  private(set) var gust: Float = 1

  private var time: Float = 0
  private var lastSetting = -1

  func update(_ dt: Float, _ settings: Settings) {
    time += dt
    if settings.timeOfDay != lastSetting {
      switch settings.timeOfDay { case 0: hour = 10; case 1: hour = 17.6; case 2: hour = 21.5; default: hour = 16.5 }
      lastSetting = settings.timeOfDay
    }
    if settings.timeOfDay == 3 { hour = fmodf(hour + dt / Environment.HOUR_SECONDS, 24) }
    let raining = settings.weather == 1
    rain += ((raining ? 1 : 0) - rain) * min(1, dt / 6)
    wet = raining ? min(1, wet + dt / 20) : max(0, wet - dt / 120)
    gust = 1 + rain * (1.6 + 0.9 * sin(time * 0.37) * sin(time * 1.13))

    let day = (hour - 6) / 12 * PI_F
    let sunUp = sin(day)
    let sx = cos(day), sy = sunUp * 0.62, sz: Float = 0.38
    night = smooth(0.08, -0.12, sunUp)
    let low = smooth(0.55, 0.05, sunUp)
    if sunUp > -0.02 {
      setLight(sx, max(sy, 0.12), sz)
      let clear = 1 - rain * 0.72
      let fade = smooth(-0.02, 0.18, sunUp)
      lightColor[0] = (3.1 + 0.1 * low) * clear * fade
      lightColor[1] = (2.8 - 1.15 * low) * clear * fade
      lightColor[2] = (2.3 - 1.6 * low) * clear * fade
    } else {
      setLight(-0.25, 0.85, 0.45)
      lightColor = [0.16, 0.2, 0.32].map { $0 * (1 - rain * 0.7) }
    }
    mix3(&skyTop, 0.33, 0.52, 0.76, 0.3, 0.36, 0.55, low)
    mix3(&skyLow, 0.8, 0.8, 0.77, 0.95, 0.62, 0.4, low)
    blend(&skyTop, 0.03, 0.05, 0.11, night)
    blend(&skyLow, 0.1, 0.1, 0.14, night)
    let dim = 1 - night * 0.85
    blend(&skyTop, 0.42 * dim, 0.44 * dim, 0.47 * dim, rain * 0.85)
    blend(&skyLow, 0.56 * dim, 0.57 * dim, 0.58 * dim, rain * 0.85)
    fog = 0.0021 + rain * 0.0016 + night * 0.0004
  }

  private func setLight(_ x: Float, _ y: Float, _ z: Float) {
    let l = sqrt(x * x + y * y + z * z)
    light = [x / l, y / l, z / l]
  }

  private func smooth(_ edge0: Float, _ edge1: Float, _ v: Float) -> Float {
    let t = min(max((v - edge0) / (edge1 - edge0), 0), 1)
    return t * t * (3 - 2 * t)
  }

  private func mix3(_ out: inout [Float], _ r0: Float, _ g0: Float, _ b0: Float, _ r1: Float, _ g1: Float, _ b1: Float, _ t: Float) {
    out[0] = r0 + (r1 - r0) * t; out[1] = g0 + (g1 - g0) * t; out[2] = b0 + (b1 - b0) * t
  }

  private func blend(_ out: inout [Float], _ r: Float, _ g: Float, _ b: Float, _ t: Float) {
    out[0] += (r - out[0]) * t; out[1] += (g - out[1]) * t; out[2] += (b - out[2]) * t
  }
}
