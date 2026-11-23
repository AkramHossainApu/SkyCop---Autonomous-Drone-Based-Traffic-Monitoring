import Foundation

@inline(__always) func positiveMod(_ a: Float, _ b: Float) -> Float {
  let m = fmodf(a, b)
  return m < 0 ? m + b : m
}

@inline(__always) func javaRound(_ v: Float) -> Int { Int(floor(v + 0.5)) }

enum Signals {
  static let CYCLE: Float = 26, GREEN: Float = 9, YELLOW: Float = 11.5

  /** 0 green, 1 yellow, 2 red. Axis 0 is traffic moving along Z. */
  static func phase(_ time: Float, _ axis: Int, _ group: Int) -> Int {
    let t = positiveMod(time + Float(group) * 6.5 + Float(axis) * 13, CYCLE)
    return t < GREEN ? 0 : t < YELLOW ? 1 : 2
  }
}

/// Swift port of Traffic.kt (Intelligent Driver Model with signals).
final class Traffic {
  static let SPAN: Float = 480
  static let PER_LANE = 3
  static let LANES = (City.BLOCKS + 1) * 8
  static let COUNT = LANES * PER_LANE
  static let POLICE = 4, BUS = 3
  private static let STOP_LINE: Float = 12.2
  private static let LENGTH: [Float] = [4.2, 3.6, 4.9, 10, 4.3, 2.2]
  private static let WIDTH: [Float] = [1.85, 1.75, 2, 2.5, 1.9, 0.9]
  private static let HEIGHT: [Float] = [1.55, 1.6, 2.2, 3.1, 1.8, 1.5]
  private static let COLORS = [0xE9E9E6, 0x1E2227, 0x9EA5AA, 0xA3302B, 0x2C5580, 0xE0B33C, 0x3C6B52, 0xD9702F]
  private static let STEP: Float = 1 / 60

  private(set) var type = [Int](repeating: 0, count: COUNT)
  private(set) var color = [Int](repeating: 0, count: COUNT)
  private(set) var x = [Float](repeating: 0, count: COUNT)
  private(set) var z = [Float](repeating: 0, count: COUNT)
  private(set) var yaw = [Float](repeating: 0, count: COUNT)
  private(set) var speed = [Float](repeating: 0, count: COUNT)
  private var s = [Float](repeating: 0, count: COUNT)
  private var previous = [Float](repeating: 0, count: COUNT)
  private var desired = [Float](repeating: 0, count: COUNT)
  private(set) var time: Float = 0
  private var accumulator: Float = 0
  private var random: KotlinRandom

  init(seed: Int32 = 71) {
    random = KotlinRandom(seed: seed)
    for lane in 0..<Traffic.LANES {
      let start = random.nextInt(12)
      for k in 0..<Traffic.PER_LANE {
        let car = lane * Traffic.PER_LANE + k
        let roll = random.nextFloat()
        type[car] = roll < 0.36 ? 0 : roll < 0.58 ? 1 : roll < 0.74 ? 2 : roll < 0.80 ? Traffic.BUS : roll < 0.84 ? Traffic.POLICE : 5
        color[car] = type[car] == Traffic.POLICE ? 0xF2F4F5 : Traffic.COLORS[random.nextInt(Traffic.COLORS.count)]
        s[car] = -City.HALF + Float((start + k * 4) % 12) * City.PITCH + 22 + random.nextFloat() * 20
        previous[car] = s[car]
        desired[car] = 7.5 + random.nextFloat() * 3.5
        speed[car] = desired[car] * 0.5
      }
      sortLane(lane)
    }
    place(1)
  }

  private func sortLane(_ lane: Int) {
    let first = lane * Traffic.PER_LANE
    for a in first..<(first + Traffic.PER_LANE) {
      for b in (a + 1)..<(first + Traffic.PER_LANE) where s[b] < s[a] {
        let value = s[a]; s[a] = s[b]; s[b] = value
        previous[a] = s[a]; previous[b] = s[b]
      }
    }
  }

  func length(_ car: Int) -> Float { Traffic.LENGTH[type[car]] }
  func width(_ car: Int) -> Float { Traffic.WIDTH[type[car]] }
  func height(_ car: Int) -> Float { Traffic.HEIGHT[type[car]] }

  private func roadOf(_ lane: Int) -> Int { (lane / 4) % (City.BLOCKS + 1) }
  private func axisOf(_ lane: Int) -> Int { lane / ((City.BLOCKS + 1) * 4) }
  private func directionOf(_ lane: Int) -> Float { lane & 2 == 0 ? 1 : -1 }
  private func offset(_ lane: Int) -> Float { lane & 1 == 0 ? 2.05 : 5.6 }
  private func laneX(_ lane: Int, _ along: Float) -> Float {
    axisOf(lane) == 0 ? City.road(roadOf(lane)) - directionOf(lane) * offset(lane) : directionOf(lane) * along
  }
  private func laneZ(_ lane: Int, _ along: Float) -> Float {
    axisOf(lane) == 0 ? directionOf(lane) * along : City.road(roadOf(lane)) + directionOf(lane) * offset(lane)
  }
  private func laneYaw(_ lane: Int) -> Float {
    let direction = directionOf(lane)
    return axisOf(lane) == 1 ? direction * PI_F / 2 : direction > 0 ? PI_F : 0
  }

  func update(_ dt: Float) {
    accumulator += dt
    while accumulator >= Traffic.STEP {
      accumulator -= Traffic.STEP
      step()
    }
    place(accumulator / Traffic.STEP)
  }

  private func step() {
    let STEP = Traffic.STEP, SPAN = Traffic.SPAN, STOP_LINE = Traffic.STOP_LINE
    time += STEP
    previous = s
    for car in 0..<Traffic.COUNT {
      let lane = car / Traffic.PER_LANE
      let leader = lane * Traffic.PER_LANE + (car + 1) % Traffic.PER_LANE
      let position = previous[car]
      let v = speed[car]
      let halfLength = length(car) / 2
      let leaderDistance = positiveMod(previous[leader] - position, 2 * SPAN)
      var gap = leaderDistance - halfLength - length(leader) / 2
      var closing = v - speed[leader]

      let front = position + halfLength
      let junction = ceil((front + STOP_LINE - 0.4) / City.PITCH) * City.PITCH
      if abs(junction) <= City.HALF {
        let toLine = junction - STOP_LINE - front
        let direction = directionOf(lane)
        let crossing = javaRound(junction * direction / City.PITCH) + City.BLOCKS / 2
        let phase = Signals.phase(time, axisOf(lane), (crossing + roadOf(lane)) & 1)
        let comfortable = toLine >= v * v / 9 - 0.3
        let exitBlocked = speed[leader] < 2 &&
          leaderDistance - toLine < 2 * STOP_LINE + length(leader) + halfLength && leaderDistance > toLine
        let stop = (phase != 0 || exitBlocked) && comfortable
        if stop && toLine + 0.8 < gap { gap = max(toLine + 0.8, 0.05); closing = v }
      }

      let limit = desired[car]
      let wanted = 1.4 + max(0, v * 1.1 + v * closing / (2 * sqrt(Float(2.4 * 3.2))))
      let ratio = v / limit
      let squeeze = wanted / max(gap, 0.05)
      let acceleration = (2.4 * (1 - ratio * ratio * ratio * ratio - squeeze * squeeze)).clamped(-12, 2.4)
      let next = max(0, v + acceleration * STEP)
      speed[car] = next
      var moved = position + min(next * STEP, max(0, gap - 0.04))
      if moved >= SPAN {
        moved -= 2 * SPAN
        previous[car] -= 2 * SPAN
      }
      s[car] = moved
    }
  }

  private func place(_ alpha: Float) {
    for car in 0..<Traffic.COUNT {
      let lane = car / Traffic.PER_LANE
      let along = previous[car] + (s[car] - previous[car]) * alpha
      x[car] = laneX(lane, along); z[car] = laneZ(lane, along); yaw[car] = laneYaw(lane)
    }
  }
}
