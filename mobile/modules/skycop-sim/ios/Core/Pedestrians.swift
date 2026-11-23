import Foundation

/// People on the footpaths (Pedestrians.kt): along a stretch of pavement, on to the next, back at the end.
final class Pedestrians {
  static let MAX = 1400
  static let CLOTHES = [0xF2F0E8, 0x2D5DA8, 0xC8343C, 0x3C8C4A, 0xE8B83A, 0x7A3A8C, 0xE07A2A, 0x1F2A3A, 0xD87AA0, 0x6A7A82]

  private let walkways: [Float]
  private let segments: Int
  private var next: [Int], previous: [Int], length: [Float]

  let count: Int
  private var segment: [Int], along: [Float], heading: [Float], speed: [Float], offset: [Float]
  private(set) var x: [Float], z: [Float], yaw: [Float], color: [Int], stride: [Float]

  init(city: City) {
    walkways = city.walkways
    segments = city.walkwayCount
    next = [Int](repeating: -1, count: segments)
    previous = [Int](repeating: -1, count: segments)
    length = [Float](repeating: 0, count: segments)
    count = min(Pedestrians.MAX, Int(Float(segments) * 0.6))
    segment = [Int](repeating: 0, count: count); along = [Float](repeating: 0, count: count)
    heading = [Float](repeating: 0, count: count); speed = [Float](repeating: 0, count: count)
    offset = [Float](repeating: 0, count: count)
    x = [Float](repeating: 0, count: count); z = [Float](repeating: 0, count: count); yaw = [Float](repeating: 0, count: count)
    color = [Int](repeating: 0, count: count); stride = [Float](repeating: 0, count: count)
    for s in 0..<segments {
      let i = s * 4
      length[s] = hypotf(walkways[i + 2] - walkways[i], walkways[i + 3] - walkways[i + 1])
      if s > 0 && Pedestrians.close(walkways[i], walkways[i + 1], walkways[i - 2], walkways[i - 1]) {
        previous[s] = s - 1; next[s - 1] = s
      }
    }
    var random = KotlinRandom(seed: 99)
    for p in 0..<count {
      segment[p] = random.nextInt(segments)
      along[p] = random.nextFloat() * length[segment[p]]
      heading[p] = random.nextBoolean() ? 1 : -1
      speed[p] = 0.9 + random.nextFloat() * 0.6
      offset[p] = random.nextFloat() * 1.2 - 0.6
      color[p] = Pedestrians.CLOTHES[random.nextInt(Pedestrians.CLOTHES.count)]
      stride[p] = random.nextFloat() * 2
    }
    update(0)
  }

  private static func close(_ ax: Float, _ az: Float, _ bx: Float, _ bz: Float) -> Bool { abs(ax - bx) < 0.2 && abs(az - bz) < 0.2 }

  func update(_ dt: Float) {
    for p in 0..<count {
      var s = segment[p]
      along[p] += heading[p] * speed[p] * dt
      stride[p] = fmodf(stride[p] + speed[p] * dt * 1.6, 2)
      if along[p] > length[s] {
        if next[s] >= 0 { along[p] -= length[s]; s = next[s] } else { along[p] = length[s]; heading[p] = -1 }
      } else if along[p] < 0 {
        if previous[s] >= 0 { s = previous[s]; along[p] += length[s] } else { along[p] = 0; heading[p] = 1 }
      }
      segment[p] = s
      let i = s * 4
      let l = max(length[s], 0.01)
      let dx = (walkways[i + 2] - walkways[i]) / l, dz = (walkways[i + 3] - walkways[i + 1]) / l
      x[p] = walkways[i] + dx * along[p] - dz * offset[p]
      z[p] = walkways[i + 1] + dz * along[p] + dx * offset[p]
      yaw[p] = atan2(dx * heading[p], -dz * heading[p])
    }
  }
}
