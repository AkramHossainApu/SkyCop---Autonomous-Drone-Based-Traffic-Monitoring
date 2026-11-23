import Foundation

/// Same fields and the same comma-separated encoding as Settings.kt.
final class Settings {
  var classicLayout = false, vr = true, headTracking = true, lensCorrection = true
  var lensSpacing = 0
  var comfort = true, invertClimb = false, invertLook = false
  var sensitivity = 100, deadzone = 14
  var sound = true, shadows = true, wind = true
  /// Index into MapData.FILES.
  var map = 0
  /// Index into Environment.TIMES and Environment.WEATHERS.
  var timeOfDay = 0, weather = 0

  func encode() -> String {
    func b(_ v: Bool) -> Int { v ? 1 : 0 }
    return [b(classicLayout), b(vr), b(headTracking), b(lensCorrection), lensSpacing, b(comfort),
            b(invertClimb), b(invertLook), sensitivity, deadzone, b(sound), b(shadows), b(wind), map, timeOfDay, weather]
      .map(String.init).joined(separator: ",")
  }

  func decode(_ text: String) {
    let parts = text.split(separator: ",").compactMap { Int($0) }
    guard parts.count >= 13 && parts.count <= 16 else { return }
    classicLayout = parts[0] == 1; vr = parts[1] == 1; headTracking = parts[2] == 1; lensCorrection = parts[3] == 1
    lensSpacing = abs(parts[4]) <= 10 ? parts[4] : 0
    comfort = parts[5] == 1; invertClimb = parts[6] == 1; invertLook = parts[7] == 1
    sensitivity = parts[8].clamped(50, 150); deadzone = parts[9].clamped(4, 30)
    sound = parts[10] == 1; shadows = parts[11] == 1; wind = parts[12] == 1
    map = parts.count > 13 ? parts[13].clamped(0, MapData.FILES.count - 1) : 0
    timeOfDay = parts.count > 14 ? parts[14].clamped(0, Environment.TIMES.count - 1) : 0
    weather = parts.count > 15 ? parts[15].clamped(0, Environment.WEATHERS.count - 1) : 0
  }
}
