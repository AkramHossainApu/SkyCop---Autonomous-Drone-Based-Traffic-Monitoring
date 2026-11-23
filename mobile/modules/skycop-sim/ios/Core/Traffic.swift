import Foundation

@inline(__always) func positiveMod(_ a: Float, _ b: Float) -> Float {
  let m = fmodf(a, b)
  return m < 0 ? m + b : m
}

@inline(__always) func javaRound(_ v: Float) -> Int { Int(floor(v + 0.5)) }

enum Signals {
  static let CYCLE: Float = 26, GREEN: Float = 9, YELLOW: Float = 11.5

  /** 0 green, 1 yellow, 2 red. Axis 0 is traffic heading mostly north or south. */
  static func phase(_ time: Float, _ axis: Int, _ group: Int) -> Int {
    let t = positiveMod(time + Float(group) * 6.5 + Float(axis) * 13, CYCLE)
    return t < GREEN ? 0 : t < YELLOW ? 1 : 2
  }
}

/// Swift port of Traffic.kt: Dhaka traffic on the real road network, keeping left, taking turns
/// at junctions, and coming back in at the map's edge. Same seed, same draws, same traffic.
final class Traffic {
  static let CAR = 0, CNG = 1, RICKSHAW = 2, BUS = 3, BIKE = 4, MICROBUS = 5
  static let MAX_VEHICLES = 2200
  private static let LENGTH: [Float] = [4.4, 2.7, 2.0, 10.5, 2.0, 4.7]
  private static let WIDTH: [Float] = [1.75, 1.35, 1.1, 2.5, 0.75, 1.8]
  private static let HEIGHT: [Float] = [1.45, 1.75, 1.95, 3.2, 1.4, 1.95]
  private static let TOP_SPEED: [Float] = [11, 8.5, 3.6, 10, 12, 10.5]
  private static let CLASS_SPEED: [Float] = [16, 14, 12, 11, 9.5, 8, 6.5, 5, 5]
  private static let SPACING: [Float] = [16, 16, 16, 20, 26, 40, 50, 70, 80]
  private static let CAR_COLORS = [0xF2F2EE, 0xF2F2EE, 0xF2F2EE, 0xB9BCC0, 0x1E2227, 0x7A1E22, 0x2C4C78, 0xD7D2C4]
  private static let CNG_COLORS = [0x1F8A3B, 0x1F8A3B, 0x1F8A3B, 0x23753A, 0x6B7176]
  private static let RICKSHAW_COLORS = [0xD02A2A, 0x2C55B8, 0xE8B21E, 0x2E9C4A, 0xD8418A, 0x7A3FB0]
  private static let BUS_COLORS = [0xC8302A, 0x2E5DA8, 0x2F8F4E, 0xE0A020, 0xE6E3DA, 0x8A2BB0, 0xD8572A]
  private static let BIKE_COLORS = [0xC02020, 0x1E2227, 0x2050B0, 0xE0E0E0]
  private static let MICROBUS_COLORS = [0xF2F2EE, 0xF2F2EE, 0xB9BCC0, 0x9FA4A8]
  private static let STEP: Float = 1 / 60

  private let city: City
  private let map: MapData

  // Lanes
  private(set) var laneCount = 0
  private var laneEdge: [Int] = [], laneStartNode: [Int] = [], laneEndNode: [Int] = [], laneClass: [Int] = []
  private var laneKey: [Int] = [], laneForward: [Bool] = []
  private var laneLength: [Float] = [], trimStart: [Float] = [], trimEnd: [Float] = []
  private var pointStart: [Int] = [0]
  private var px: [Float] = [], pz: [Float] = [], py: [Float] = [], pcum: [Float] = []
  private var outStart: [Int] = [0], outLanes: [Int] = [], portalStarts: [Int] = []

  // Junctions
  private var holder: [Int] = [], inside: [Int] = [], requestKey: [Int] = [], requestSince: [Float] = []
  private var controlled: [Bool] = []

  // Vehicles
  private(set) var count = 0
  private(set) var type: [Int] = [], color: [Int] = []
  private(set) var x: [Float] = [], y: [Float] = [], z: [Float] = [], yaw: [Float] = [], speed: [Float] = []
  private var lane: [Int] = [], s: [Float] = [], previousS: [Float] = [], nextLane: [Int] = []
  private var connector: [Float] = [], connectorLength: [Float] = [], admitted: [Bool] = [], segment: [Int] = []
  private var desired: [Float] = [], pace: [Float] = [], paceTarget: [Float] = [], paceUntil: [Float] = []
  private var fromX: [Float] = [], fromY: [Float] = [], fromZ: [Float] = [], fromYaw: [Float] = []
  private var toX: [Float] = [], toY: [Float] = [], toZ: [Float] = [], toYaw: [Float] = []
  private var laneCars: [[Int]] = []
  /// Room on each lane already promised to vehicles still crossing the junction into it.
  private var reserved: [Float] = []
  /// When each vehicle started waiting for room on its chosen exit, or -1.
  private var blockedSince: [Float] = []
  /// The vehicle first in line for room on each lane where two queues merge, and since when.
  private var claimBy: [Int] = []
  private var claimAt: [Float] = []
  /// When each vehicle started waiting at the line, or -1.
  private var queuedSince: [Float] = []
  /// The speed a vehicle can take its next turn at (see Traffic.kt).
  private var turnSpeed: [Float] = []
  /// The curve it just came through, where its rear wheels still are: 6 floats each, and its length.
  private var tail: [Float] = []
  private var tailLength: [Float] = []

  private(set) var time: Float = 0
  private var accumulator: Float = 0
  private var random: KotlinRandom
  private var tmp: [Float] = [0, 0, 0, 0]
  private var lastHeight: Float = 0

  private typealias T = Traffic

  init(city: City, seed: Int32 = 71) {
    self.city = city
    map = city.map
    random = KotlinRandom(seed: seed)
    var edgeOf: [Int] = [], dirOf: [Int] = [], indexOf: [Int] = []
    for e in 0..<map.edgeCount where map.edgeTraffic[e] {
      for k in 0..<map.lanesForward[e] { edgeOf.append(e); dirOf.append(1); indexOf.append(k) }
      for k in 0..<map.lanesBackward[e] { edgeOf.append(e); dirOf.append(-1); indexOf.append(k) }
    }
    laneCount = edgeOf.count
    laneEdge = edgeOf
    laneStartNode = (0..<laneCount).map { dirOf[$0] > 0 ? map.edgeA[edgeOf[$0]] : map.edgeB[edgeOf[$0]] }
    laneEndNode = (0..<laneCount).map { dirOf[$0] > 0 ? map.edgeB[edgeOf[$0]] : map.edgeA[edgeOf[$0]] }
    laneClass = edgeOf.map { map.edgeClass[$0] }
    laneKey = Array(0..<laneCount)
    laneForward = dirOf.map { $0 > 0 }
    laneLength = [Float](repeating: 0, count: laneCount)
    trimStart = laneStartNode.map { city.junctionRadius[$0] }
    trimEnd = laneEndNode.map { city.junctionRadius[$0] }
    for l in 0..<laneCount {
      let e = laneEdge[l], dir = dirOf[l]
      let from = map.edgeStart[e], to = map.edgeStart[e + 1]
      let n = to - from
      let total = map.lanesForward[e] + map.lanesBackward[e]
      let width = map.edgeWidth[e] / Float(total)
      // Keep left: lanes sit to the left of the direction of travel.
      let offset: Float = map.lanesBackward[e] > 0 ? (Float(indexOf[l]) + 0.5) * width
        : (Float(indexOf[l]) + 0.5 - Float(map.lanesForward[e]) / 2) * width
      var cumulative: Float = 0
      for j in 0..<n {
        let k = dir > 0 ? from + j : to - 1 - j
        let prev = dir > 0 ? max(from, k - 1) : min(to - 1, k + 1)
        let next = dir > 0 ? min(to - 1, k + 1) : max(from, k - 1)
        var nx: Float = 0, nz: Float = 0
        if prev != k {
          let dx = map.edgeX[k] - map.edgeX[prev], dz = map.edgeZ[k] - map.edgeZ[prev], d = hypot(dx, dz)
          if d > 0 { nx += dz / d; nz -= dx / d }
        }
        if next != k {
          let dx = map.edgeX[next] - map.edgeX[k], dz = map.edgeZ[next] - map.edgeZ[k], d = hypot(dx, dz)
          if d > 0 { nx += dz / d; nz -= dx / d }
        }
        let nl = hypot(nx, nz)
        var mx: Float = 0, mz: Float = 0
        if nl > 0 {
          nx /= nl; nz /= nl
          var miter: Float = 1
          if prev != k {
            let dx = map.edgeX[k] - map.edgeX[prev], dz = map.edgeZ[k] - map.edgeZ[prev], d = hypot(dx, dz)
            if d > 0 { miter = 1 / max(0.6, nx * dz / d - nz * dx / d) }
          }
          mx = nx * offset * miter; mz = nz * offset * miter
        }
        let lx = map.edgeX[k] + mx, lz = map.edgeZ[k] + mz
        if j > 0 { cumulative += hypot(lx - px[px.count - 1], lz - pz[pz.count - 1]) }
        px.append(lx); pz.append(lz); py.append(map.edgeY[k]); pcum.append(cumulative)
      }
      laneLength[l] = cumulative
      pointStart.append(px.count)
    }
    separate()

    var startsAt: [Int: [Int]] = [:]
    for l in 0..<laneCount { startsAt[laneStartNode[l], default: []].append(l) }
    for l in 0..<laneCount where map.nodeFlags[laneStartNode[l]] & 2 != 0 { portalStarts.append(l) }
    for l in 0..<laneCount {
      let candidates = startsAt[laneEndNode[l]] ?? []
      var added = 0
      for c in candidates where laneEdge[c] != laneEdge[l] { outLanes.append(c); added += 1 }
      if added == 0 { for c in candidates { outLanes.append(c); added += 1 } }
      outStart.append(outLanes.count)
    }

    holder = [Int](repeating: -1, count: map.nodeCount)
    inside = [Int](repeating: 0, count: map.nodeCount)
    requestKey = [Int](repeating: -1, count: map.nodeCount)
    requestSince = [Float](repeating: 0, count: map.nodeCount)
    // Any node where more than one lane comes in needs turns taken, even a plain merge.
    var incoming = [Int](repeating: 0, count: map.nodeCount)
    for l in 0..<laneCount { incoming[laneEndNode[l]] += 1 }
    controlled = (0..<map.nodeCount).map { incoming[$0] >= 2 && map.nodeFlags[$0] & 2 == 0 }

    var wanted = [Int](repeating: 0, count: laneCount)
    var total = 0
    for l in 0..<laneCount {
      let room = laneLength[l] - trimStart[l] - trimEnd[l] - 6
      if room <= 0 { continue }
      wanted[l] = min(Int(room / T.SPACING[laneClass[l]]), Int(room / 12))
      total += wanted[l]
    }
    let scale: Float = total > T.MAX_VEHICLES ? Float(T.MAX_VEHICLES) / Float(total) : 1
    var n = 0
    for l in 0..<laneCount { wanted[l] = Int(Float(wanted[l]) * scale); n += wanted[l] }
    count = n

    let zeros = [Float](repeating: 0, count: n)
    type = [Int](repeating: 0, count: n); color = [Int](repeating: 0, count: n)
    x = zeros; y = zeros; z = zeros; yaw = zeros; speed = zeros
    lane = [Int](repeating: 0, count: n); s = zeros; previousS = zeros
    nextLane = [Int](repeating: -1, count: n); connector = [Float](repeating: 0, count: n * 6); connectorLength = zeros
    admitted = [Bool](repeating: false, count: n); segment = [Int](repeating: 0, count: n)
    desired = zeros; pace = [Float](repeating: 1, count: n); paceTarget = pace; paceUntil = zeros
    fromX = zeros; fromY = zeros; fromZ = zeros; fromYaw = zeros; toX = zeros; toY = zeros; toZ = zeros; toYaw = zeros
    laneCars = [[Int]](repeating: [], count: laneCount)
    reserved = [Float](repeating: 0, count: laneCount)
    blockedSince = [Float](repeating: -1, count: n)
    claimBy = [Int](repeating: -1, count: laneCount)
    claimAt = [Float](repeating: 0, count: laneCount)
    queuedSince = [Float](repeating: -1, count: n)
    turnSpeed = [Float](repeating: 16, count: n)
    tail = [Float](repeating: 0, count: n * 6)
    tailLength = [Float](repeating: 0, count: n)

    var car = 0
    for l in 0..<laneCount {
      let k = wanted[l]
      if k == 0 { continue }
      let room = laneLength[l] - trimStart[l] - trimEnd[l] - 6
      let gap = room / Float(k)
      for j in 0..<k {
        type[car] = pickType(laneClass[l])
        color[car] = pickColor(type[car])
        lane[car] = l
        s[car] = trimStart[l] + 3 + (Float(j) + 0.5) * gap + (random.nextFloat() - 0.5) * max(0, gap - T.LENGTH[T.BUS] - 2) * 0.5
        previousS[car] = s[car]
        desired[car] = min(T.TOP_SPEED[type[car]], T.CLASS_SPEED[laneClass[l]]) * (0.85 + random.nextFloat() * 0.25)
        speed[car] = 0
        laneCars[l].append(car)
        chooseNext(car)
        car += 1
      }
    }
    for c in 0..<count {
      locate(c)
      fromX[c] = toX[c]; fromY[c] = toY[c]; fromZ[c] = toZ[c]; fromYaw[c] = toYaw[c]
    }
    place(1)
  }

  private func pickType(_ cls: Int) -> Int {
    let r = random.nextFloat()
    if cls <= 3 { return r < 0.34 ? T.CAR : r < 0.54 ? T.CNG : r < 0.68 ? T.BUS : r < 0.85 ? T.BIKE : r < 0.95 ? T.MICROBUS : T.RICKSHAW }
    if cls <= 5 { return r < 0.30 ? T.CAR : r < 0.52 ? T.CNG : r < 0.57 ? T.BUS : r < 0.75 ? T.BIKE : r < 0.83 ? T.MICROBUS : T.RICKSHAW }
    return r < 0.22 ? T.CAR : r < 0.37 ? T.CNG : r < 0.57 ? T.BIKE : r < 0.62 ? T.MICROBUS : T.RICKSHAW
  }

  private func pickColor(_ t: Int) -> Int {
    let palette: [Int]
    switch t {
    case T.CAR: palette = T.CAR_COLORS
    case T.CNG: palette = T.CNG_COLORS
    case T.RICKSHAW: palette = T.RICKSHAW_COLORS
    case T.BUS: palette = T.BUS_COLORS
    case T.BIKE: palette = T.BIKE_COLORS
    default: palette = T.MICROBUS_COLORS
    }
    return palette[random.nextInt(palette.count)]
  }

  func length(_ car: Int) -> Float { T.LENGTH[type[car]] }
  func width(_ car: Int) -> Float { T.WIDTH[type[car]] }
  func height(_ car: Int) -> Float { T.HEIGHT[type[car]] }

  // MARK: Geometry

  @discardableResult
  private func lanePoint(_ l: Int, _ d: Float, _ hint: Int) -> Int {
    let from = pointStart[l], to = pointStart[l + 1]
    var k = (from + hint).clamped(from, to - 2)
    while k < to - 2 && pcum[k + 1] < d { k += 1 }
    while k > from && pcum[k] > d { k -= 1 }
    let length = pcum[k + 1] - pcum[k]
    let t: Float = length > 0 ? ((d - pcum[k]) / length).clamped(0, 1) : 0
    let dx = px[k + 1] - px[k], dz = pz[k + 1] - pz[k]
    tmp[0] = px[k] + dx * t; tmp[1] = pz[k] + dz * t
    let l2 = hypot(dx, dz)
    tmp[2] = l2 > 0 ? dx / l2 : 0; tmp[3] = l2 > 0 ? dz / l2 : -1
    lastHeight = py[k] + (py[k + 1] - py[k]) * t
    return k - from
  }

  /// Where roads meet at a shallow angle, each lane stops (and each exit begins) far enough back
  /// to be clear of the other lanes there.
  private func separate() {
    var ending: [Int: [Int]] = [:], starting: [Int: [Int]] = [:]
    for l in 0..<laneCount {
      ending[laneEndNode[l], default: []].append(l)
      starting[laneStartNode[l], default: []].append(l)
    }
    func at(_ l: Int, _ back: Float, _ fromEnd: Bool) -> (Float, Float) {
      lanePoint(l, fromEnd ? max(0, laneLength[l] - back) : min(laneLength[l], back), 0)
      return (tmp[0], tmp[1])
    }
    for pass in 0...1 {
      let groups = pass == 0 ? ending : starting
      for node in groups.keys.sorted() {
        let lanes = groups[node]!
        if lanes.count < 2 || map.nodeFlags[node] & 2 != 0 { continue }
        for l in lanes {
          var d = pass == 0 ? trimEnd[l] : trimStart[l]
          let limit = min(25, laneLength[l] * 0.4)
          while d < limit {
            let a = at(l, d, pass == 0)
            var clear = true
            for m in lanes {
              if m == l || laneEdge[m] == laneEdge[l] { continue }
              let b = at(m, max(d, pass == 0 ? trimEnd[m] : trimStart[m]), pass == 0)
              if hypot(a.0 - b.0, a.1 - b.1) < 2.6 { clear = false; break }
            }
            if clear { break }
            d += 1
          }
          if pass == 0 { trimEnd[l] = min(d, limit) } else { trimStart[l] = min(d, limit) }
        }
      }
    }
  }

  private func chooseNext(_ car: Int) {
    let l = lane[car]
    let from = outStart[l], to = outStart[l + 1]
    if map.nodeFlags[laneEndNode[l]] & 2 != 0 || from == to {
      nextLane[car] = -1
      connectorLength[car] = 0
      return
    }
    lanePoint(l, laneLength[l] - trimEnd[l], segment[car])
    let ax = tmp[0], az = tmp[1], hx = tmp[2], hz = tmp[3]
    var totalWeight: Float = 0
    for i in from..<to { totalWeight += turnWeight(car, hx, hz, outLanes[i]) }
    var pick = random.nextFloat() * totalWeight
    var chosen = outLanes[to - 1]
    for i in from..<to {
      pick -= turnWeight(car, hx, hz, outLanes[i])
      if pick <= 0 { chosen = outLanes[i]; break }
    }
    connect(car, chosen, ax, az, hx, hz)
  }

  /// The curve from the stop point (ax, az) heading (hx, hz) onto `chosen`.
  private func connect(_ car: Int, _ chosen: Int, _ ax: Float, _ az: Float, _ hx: Float, _ hz: Float) {
    nextLane[car] = chosen
    lanePoint(chosen, trimStart[chosen], 0)
    let bx = tmp[0], bz = tmp[1], gx = tmp[2], gz = tmp[3]
    var cx = (ax + bx) / 2, cz = (az + bz) / 2
    let det = hx * (-gz) - hz * (-gx)
    if abs(det) > 0.15 {
      let t = ((bx - ax) * (-gz) - (bz - az) * (-gx)) / det
      let span = hypot(bx - ax, bz - az)
      if t > 0 && t < span * 1.5 { cx = ax + hx * t; cz = az + hz * t }
    }
    let o = car * 6
    connector[o] = ax; connector[o + 1] = az; connector[o + 2] = cx; connector[o + 3] = cz; connector[o + 4] = bx; connector[o + 5] = bz
    var length: Float = 0
    var lx = ax, lz = az
    for k in 1...6 {
      let t = Float(k) / 6, u = 1 - t
      let qx = u * u * ax + 2 * u * t * cx + t * t * bx, qz = u * u * az + 2 * u * t * cz + t * t * bz
      length += hypot(qx - lx, qz - lz)
      lx = qx; lz = qz
    }
    connectorLength[car] = max(0.5, length)
    let turn = acos(min(max(hx * gx + hz * gz, -1), 1))
    let radius = connectorLength[car] / max(turn, 0.05)
    turnSpeed[car] = min(max(sqrt(2.8 * radius), 3), 16)
  }

  private func turnWeight(_ car: Int, _ hx: Float, _ hz: Float, _ next: Int) -> Float {
    lanePoint(next, trimStart[next], 0)
    let turn = hx * tmp[2] + hz * tmp[3]
    var w: Float = turn > 0.85 ? 3 : turn > -0.3 ? 1.6 : 0.15
    w *= Float(9 - laneClass[next]) / 4 + 0.5
    if type[car] == T.RICKSHAW && laneClass[next] <= 2 { w *= 0.05 }
    let e = laneEdge[next]
    let lanesThatWay = laneForward[next] ? map.lanesForward[e] : map.lanesBackward[e]
    return w / Float(max(1, lanesThatWay))
  }

  /// Where the vehicle is now, into to*. Its body lies between its front and rear wheels on the path.
  private func locate(_ car: Int) {
    let l = lane[car]
    let stop = laneLength[l] - trimEnd[l]
    let d = s[car]
    if d <= stop || nextLane[car] < 0 {
      segment[car] = lanePoint(l, min(d, laneLength[l]), segment[car])
      toY[car] = lastHeight
    } else {
      toY[car] = map.nodeY[laneEndNode[l]]
    }
    pathPoint(car, d)
    toX[car] = pathX; toZ[car] = pathZ
    let axle = T.LENGTH[type[car]] * 0.3
    pathPoint(car, d + axle)
    let frontX = pathX, frontZ = pathZ
    pathPoint(car, d - axle)
    let dx = frontX - pathX, dz = frontZ - pathZ
    if abs(dx) + abs(dz) > 1e-4 { toYaw[car] = atan2(dx, -dz) }
  }

  private var pathX: Float = 0, pathZ: Float = 0

  /// The point `d` along the vehicle's path (see Traffic.kt) into pathX, pathZ.
  private func pathPoint(_ car: Int, _ d: Float) {
    let l = lane[car]
    let stop = laneLength[l] - trimEnd[l]
    let next = nextLane[car]
    if d < trimStart[l] && tailLength[car] > 0 {
      curve(tail, car * 6, (1 - (trimStart[l] - d) / tailLength[car]).clamped(0, 1))
    } else if d <= stop || next < 0 {
      let on = d.clamped(0, laneLength[l])
      lanePoint(l, on, segment[car])
      pathX = tmp[0] + tmp[2] * (d - on); pathZ = tmp[1] + tmp[3] * (d - on)
    } else if d <= stop + connectorLength[car] {
      curve(connector, car * 6, (d - stop) / connectorLength[car])
    } else {
      let along = trimStart[next] + d - stop - connectorLength[car]
      let on = min(along, laneLength[next])
      lanePoint(next, on, 0)
      pathX = tmp[0] + tmp[2] * (along - on); pathZ = tmp[1] + tmp[3] * (along - on)
    }
  }

  private func curve(_ points: [Float], _ o: Int, _ t: Float) {
    let u = 1 - t
    pathX = u * u * points[o] + 2 * u * t * points[o + 2] + t * t * points[o + 4]
    pathZ = u * u * points[o + 1] + 2 * u * t * points[o + 3] + t * t * points[o + 5]
  }

  // MARK: Simulation

  func update(_ dt: Float) {
    accumulator += dt
    while accumulator >= T.STEP {
      accumulator -= T.STEP
      for c in 0..<count { fromX[c] = toX[c]; fromY[c] = toY[c]; fromZ[c] = toZ[c]; fromYaw[c] = toYaw[c] }
      step()
      for c in 0..<count { locate(c) }
    }
    place(accumulator / T.STEP)
  }

  private func mood(_ car: Int) {
    if time < paceUntil[car] { return }
    if paceTarget[car] != 1 { paceTarget[car] = 1; return }
    let roll = random.nextFloat()
    let l = lane[car]
    let midLane = s[car] > trimStart[l] + 6 && s[car] < laneLength[l] - trimEnd[l] - 18
    let stopper = type[car] == T.BUS || type[car] == T.CNG || type[car] == T.RICKSHAW
    if roll < (stopper ? 0.0012 : 0.0004) && midLane {
      paceTarget[car] = 0
      paceUntil[car] = time + 4 + random.nextFloat() * (type[car] == T.BUS ? 9 : 5)
    } else if roll < 0.004 {
      paceTarget[car] = 0.35 + random.nextFloat() * 0.3
      paceUntil[car] = time + 1.5 + random.nextFloat() * 3
    }
  }

  /// 0: go. 1: red light. 2: another approach has the junction. 3: no room on the far side.
  /// Who a vehicle would hold the junction for: its lane, or at a signal everyone on its green.
  private var entryKey = -1

  private func mayEnter(_ car: Int, _ node: Int, _ comfortable: Bool) -> Int {
    let l = lane[car]
    let key = laneKey[l]
    if map.nodeFlags[node] & 1 != 0 {
      let axis = abs(tmp[2]) > abs(tmp[3]) ? 1 : 0
      let phase = Signals.phase(time, axis, node & 1)
      if phase == 2 || (phase == 1 && comfortable) { return 1 }
      // Everyone with the green goes together (see Traffic.kt).
      entryKey = -2 - axis
      if holder[node] != -1 && holder[node] != entryKey { return 2 }
      return mayTake(car, nextLane[car]) ? 0 : 3
    }
    entryKey = key
    if requestKey[node] != -1 && time - requestSince[node] > 12 { requestKey[node] = -1 }
    if holder[node] != -1 && holder[node] != key { return 2 }
    if requestKey[node] != -1 && requestKey[node] != key && time - requestSince[node] > 3.5 { return 2 }
    return mayTake(car, nextLane[car]) ? 0 : 3
  }

  /// Room on `next` for `car`, taking turns where queues merge (see Traffic.kt).
  private func mayTake(_ car: Int, _ next: Int) -> Bool {
    if next < 0 { return true }
    let claimant = claimBy[next]
    let claimed = claimant >= 0 && time - claimAt[next] < 2
    let mine = queuedSince[car] < 0 ? time : queuedSince[car]
    if claimed && claimant != car && mine >= (queuedSince[claimant] < 0 ? time : queuedSince[claimant]) { return false }
    if hasRoom(car, next) {
      if claimant >= 0 { claimBy[next] = -1 }
      return true
    }
    claimBy[next] = car; claimAt[next] = time
    return false
  }

  /// Whether `car` fits on `next` past the junction, counting room promised to vehicles still crossing into it.
  private func hasRoom(_ car: Int, _ next: Int) -> Bool {
    let need = reserved[next] + T.LENGTH[type[car]] + 2
    guard let last = laneCars[next].first else { return reserved[next] < 0.01 || trimStart[next] + need <= laneLength[next] - trimEnd[next] + 2 }
    return s[last] - T.LENGTH[type[last]] / 2 >= trimStart[next] + need
  }

  /// After waiting a while for a full exit, take another way out that has room, if there is one.
  private func reroute(_ car: Int) {
    let l = lane[car]
    let from = outStart[l], to = outStart[l + 1]
    lanePoint(l, laneLength[l] - trimEnd[l], segment[car])
    let ax = tmp[0], az = tmp[1], hx = tmp[2], hz = tmp[3]
    var best = -1
    var bestWeight: Float = 0
    for i in from..<to {
      let option = outLanes[i]
      if option == nextLane[car] || !hasRoom(car, option) { continue }
      let w = turnWeight(car, hx, hz, option)
      if w > bestWeight { bestWeight = w; best = option }
    }
    if best >= 0 { connect(car, best, ax, az, hx, hz) }
    blockedSince[car] = -1
  }

  private func step() {
    let STEP = T.STEP
    time += STEP
    previousS = s
    for l in 0..<laneCount {
      let cars = laneCars[l]
      var k = cars.count - 1
      while k >= 0 {
        let car = cars[k]
        let position = previousS[car]
        let v = speed[car]
        let half = T.LENGTH[type[car]] / 2
        var gap: Float = 1000
        var closing: Float = 0
        if k + 1 < cars.count {
          let leader = cars[k + 1]
          gap = previousS[leader] - position - half - T.LENGTH[type[leader]] / 2
          closing = v - speed[leader]
        } else {
          let next = nextLane[car]
          if next >= 0, let leader = laneCars[next].first {
            let end = laneLength[l] - trimEnd[l] + connectorLength[car]
            gap = (end - position) + (previousS[leader] - trimStart[next]) - half - T.LENGTH[type[leader]] / 2
            closing = v - speed[leader]
          }
        }

        mood(car)
        pace[car] += (paceTarget[car] - pace[car]) * min(1, 0.8 * STEP)

        let node = laneEndNode[l]
        let stop = laneLength[l] - trimEnd[l]
        // Only the first car waiting at the line asks to go (see Traffic.kt).
        let firstInLine = k + 1 >= cars.count || admitted[cars[k + 1]]
        if controlled[node] && !admitted[car] && nextLane[car] >= 0 && firstInLine {
          let toLine = stop - 0.4 - (position + half)
          if toLine < v * v / 5 + 6 {
            lanePoint(l, stop, segment[car])
            let comfortable = toLine >= v * v / 9 - 0.3
            let answer = mayEnter(car, node, comfortable)
            if answer == 0 {
              admitted[car] = true
              holder[node] = entryKey
              inside[node] += 1
              if requestKey[node] == laneKey[l] { requestKey[node] = -1 }
              reserved[nextLane[car]] += T.LENGTH[type[car]] + 2
              blockedSince[car] = -1
              queuedSince[car] = -1
            } else {
              if queuedSince[car] < 0 { queuedSince[car] = time }
              if answer == 3 {
                if blockedSince[car] < 0 { blockedSince[car] = time } else if time - blockedSince[car] > 4 { reroute(car) }
              } else {
                blockedSince[car] = -1
              }
              if answer == 2 && requestKey[node] == -1 && map.nodeFlags[node] & 1 == 0 { requestKey[node] = laneKey[l]; requestSince[node] = time }
              if toLine + 0.3 < gap { gap = max(toLine + 0.3, 0.05); closing = v }
            }
          }
        } else if nextLane[car] < 0 && map.nodeFlags[node] & 2 == 0 {
          let toEnd = laneLength[l] - 0.5 - (position + half)
          if toEnd < gap { gap = max(toEnd, 0.05); closing = v }
        }

        var limit = max(0.05, desired[car] * pace[car])
        if nextLane[car] >= 0 {
          let toTurn = stop - position
          if toTurn < 60 { limit = min(limit, sqrt(turnSpeed[car] * turnSpeed[car] + 4 * max(0, toTurn))) }
        }
        let wantedGap = 1.2 + max(0, v * 1.0 + v * closing / (2 * sqrt(Float(2.2 * 3.2))))
        let ratio = min(v / limit, 1.6)
        let squeeze = wantedGap / max(gap, 0.05)
        let acceleration = (2.2 * (1 - ratio * ratio * ratio * ratio - squeeze * squeeze)).clamped(-9, 2.2)
        let next = max(0, v + acceleration * STEP)
        speed[car] = next
        s[car] = position + min(next * STEP, max(0, gap - 0.04))
        k -= 1
      }
    }
    for l in 0..<laneCount {
      while let car = laneCars[l].last {
        let next = nextLane[car]
        if next < 0 {
          if map.nodeFlags[laneEndNode[l]] & 2 != 0 && s[car] >= laneLength[l] - 0.2 {
            if !reenter(car) { s[car] = laneLength[l] - 0.2; speed[car] = 0 }
            if lane[car] == l { break }
            continue
          }
          break
        }
        let end = laneLength[l] - trimEnd[l] + connectorLength[car]
        if s[car] < end { break }
        laneCars[l].removeLast()
        let node = laneEndNode[l]
        if admitted[car] {
          admitted[car] = false
          inside[node] = max(0, inside[node] - 1)
          if inside[node] == 0 { holder[node] = -1 }
          reserved[next] = max(0, reserved[next] - T.LENGTH[type[car]] - 2)
        }
        let overshoot = s[car] - end
        for k in 0..<6 { tail[car * 6 + k] = connector[car * 6 + k] }
        tailLength[car] = connectorLength[car]
        lane[car] = next
        s[car] = trimStart[next] + overshoot
        previousS[car] = s[car]
        segment[car] = 0
        laneCars[next].insert(car, at: 0)
        desired[car] = min(T.TOP_SPEED[type[car]], T.CLASS_SPEED[laneClass[next]]) * (0.85 + random.nextFloat() * 0.25)
        chooseNext(car)
      }
    }
  }

  private func reenter(_ car: Int) -> Bool {
    if portalStarts.isEmpty { return false }
    let start = random.nextInt(portalStarts.count)
    for k in 0..<min(6, portalStarts.count) {
      let target = portalStarts[(start + k) % portalStarts.count]
      if let first = laneCars[target].first, s[first] - T.LENGTH[type[first]] / 2 < T.LENGTH[type[car]] + 6 { continue }
      let old = lane[car]
      if let at = laneCars[old].firstIndex(of: car) { laneCars[old].remove(at: at) }
      lane[car] = target
      s[car] = T.LENGTH[type[car]] / 2 + 0.5
      previousS[car] = s[car]
      speed[car] = desired[car] * 0.6
      segment[car] = 0
      admitted[car] = false
      tailLength[car] = 0
      laneCars[target].insert(car, at: 0)
      chooseNext(car)
      locate(car)
      fromX[car] = toX[car]; fromY[car] = toY[car]; fromZ[car] = toZ[car]; fromYaw[car] = toYaw[car]
      return true
    }
    return false
  }

  private func place(_ alpha: Float) {
    for c in 0..<count {
      x[c] = fromX[c] + (toX[c] - fromX[c]) * alpha
      y[c] = fromY[c] + (toY[c] - fromY[c]) * alpha
      z[c] = fromZ[c] + (toZ[c] - fromZ[c]) * alpha
      yaw[c] = fromYaw[c] + wrapAngle(toYaw[c] - fromYaw[c]) * alpha
    }
  }
}
