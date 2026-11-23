import Foundation

// Swift port of DroneSim.kt. Keep the two in step: the same constants, the same order of operations.

enum FlightMode: Int, CaseIterable {
  case cine, normal, sport, atti

  var label: String { ["CINE", "NORMAL", "SPORT", "ATTI (NO GPS)"][rawValue] }
  var speed: Float { [5, 14, 24, 14][rawValue] }
  var accel: Float { [3.5, 7.5, 11.5, 6][rawValue] }
  var tilt: Float { [25, 36, 50, 25][rawValue] * DEG }
  var climb: Float { [2.5, 5, 8, 4][rawValue] }
  var yawRate: Float { [55, 100, 145, 90][rawValue] * DEG }
  var avoid: Bool { self == .cine || self == .normal }
  var gps: Bool { self != .atti }
}

enum Phase {
  case landed, takeoff, flying, landing, returnHome, crashed

  var label: String {
    switch self {
    case .landed: return "LANDED"
    case .takeoff: return "TAKE-OFF"
    case .flying: return "FLYING"
    case .landing: return "LANDING"
    case .returnHome: return "TO DOCK"
    case .crashed: return "CRASHED"
    }
  }
}

/** All axes are -1...1: right, up and forward are positive. */
final class PilotInput {
  var yaw: Float = 0, climb: Float = 0, forward: Float = 0, strafe: Float = 0
  func clear() { yaw = 0; climb = 0; forward = 0; strafe = 0 }
}

struct DronePose {
  var x: Float = 0, y: Float = 0, z: Float = 0, yaw: Float = 0, pitch: Float = 0, roll: Float = 0, rotor: Float = 0

  static func interpolate(_ a: DronePose, _ b: DronePose, _ t: Float) -> DronePose {
    DronePose(
      x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t, z: a.z + (b.z - a.z) * t,
      yaw: a.yaw + (b.yaw - a.yaw) * t, pitch: a.pitch + (b.pitch - a.pitch) * t,
      roll: a.roll + (b.roll - a.roll) * t, rotor: a.rotor + (b.rotor - a.rotor) * t)
  }
}

final class DroneSim {
  static let STEP: Float = 1 / 250
  static let GEAR: Float = 0.3
  private static let RADIUS: Float = 0.45
  private static let MAX_THRUST: Float = 2.4 * GRAVITY
  private static let CEILING: Float = 120
  private static let EASE: Float = 4.5
  private static let JERK: Float = 12
  private static let CLIMB_ACCEL: Float = 9
  private static let AGILITY: Float = 15
  /// Percent per second while hovering: 100 % lasts ten minutes.
  static let HOVER_DRAIN: Float = 100 / 600
  /// Percent per second on a dock: empty to full in ten seconds.
  static let CHARGE_RATE: Float = 10
  static let LOW_BATTERY: Float = 8

  private let city: City
  private let traffic: Traffic

  private(set) var pose = DronePose()
  private(set) var render = DronePose()
  private var previous = DronePose()

  private(set) var vx: Float = 0, vy: Float = 0, vz: Float = 0
  private(set) var phase = Phase.landed
  var mode = FlightMode.normal
  private(set) var battery: Float = 100
  private(set) var thrust: Float = 0
  private(set) var obstacle = Float.greatestFiniteMagnitude
  var yawSensitivity: Float = 1
  var windEnabled = true
  /// Gustier in the monsoon rain (Environment.gust).
  var windScale: Float = 1
  var impact: Float = 0
  private(set) var crashes = 0
  /// Times the battery ran flat in the air.
  private(set) var depletions = 0
  /// Times low battery sent the drone back to a dock by itself.
  private(set) var autoReturns = 0
  /// Dock the drone is flying to, or -1.
  private(set) var dockTarget = -1

  private var accumulator: Float = 0, time: Float = 0, phaseTime: Float = 0
  private var yawVelocity: Float = 0, pitchVelocity: Float = 0, rollVelocity: Float = 0
  private var refX: Float = 0, refY: Float = 0, refZ: Float = 0
  private var refAx: Float = 0, refAy: Float = 0, refAz: Float = 0
  private var modelX: Float = 0, modelZ: Float = 0, modelAx: Float = 0, modelAz: Float = 0
  private var settledFor: Float = 0, settledHeightFor: Float = 0
  private var holdX: Float = 0, holdY: Float = 0, holdZ: Float = 0
  private var holdingPosition = false, holdingHeight = false
  private var ground: Float = 0
  private var takeoffHeight: Float = 0
  private var landingPressed: Float = 0
  private var lowBatteryHandled = false
  private var returnAltitude: Float = 0
  private var strandedFor: Float = 0
  private var normal: [Float] = [0, 0, 0]

  var airborne: Bool { phase != .landed && phase != .crashed }
  var height: Float { max(0, pose.y - DroneSim.GEAR - ground) }
  var speed: Float { hypot(vx, vz) }
  var homeDistance: Float { hypot(pose.x - city.docks[city.homeDock * 3], pose.z - city.docks[city.homeDock * 3 + 2]) }
  var nearestDock: Int { city.nearestDock(pose.x, pose.z) }
  var dockDistance: Float {
    let d = nearestDock
    return hypot(pose.x - city.docks[d * 3], pose.z - city.docks[d * 3 + 2])
  }
  /// Resting on a dock's deck, where it charges.
  var onDock: Bool {
    let d = nearestDock
    return phase == .landed && dockDistance < City.DOCK_RADIUS - 0.1 &&
      abs(pose.y - DroneSim.GEAR - city.docks[d * 3 + 1]) < 0.3
  }
  var charging: Bool { onDock && battery < 100 }
  /// Seconds of hovering left at the current charge.
  var flightSecondsLeft: Float { battery / DroneSim.HOVER_DRAIN }
  var canTakeOff: Bool { battery >= 1 }

  init(city: City, traffic: Traffic) {
    self.city = city
    self.traffic = traffic
    reset()
  }

  /// A fresh flight: full battery, on the pad at the station.
  func reset() {
    battery = 100
    placeOnDock(city.homeDock)
    pose.yaw = 0; previous.yaw = 0; render.yaw = 0
  }

  /// Puts the drone on a dock, landed, keeping whatever charge it has; the dock tops it up.
  private func placeOnDock(_ dock: Int) {
    let d = city.docks
    let yaw = pose.yaw
    pose = DronePose(x: d[dock * 3], y: d[dock * 3 + 1] + DroneSim.GEAR, z: d[dock * 3 + 2])
    pose.yaw = yaw
    previous = pose; render = pose
    vx = 0; vy = 0; vz = 0
    yawVelocity = 0; pitchVelocity = 0; rollVelocity = 0
    clearTargets()
    thrust = 0; impact = 0; accumulator = 0
    obstacle = .greatestFiniteMagnitude; ground = d[dock * 3 + 1]
    lowBatteryHandled = false; dockTarget = -1; strandedFor = 0
    change(.landed)
  }

  /// Puts the drone hovering at a point, at rest. Used by tests and tools.
  func placeAt(_ x: Float, _ y: Float, _ z: Float, _ yaw: Float) {
    pose = DronePose(x: x, y: y, z: z, yaw: yaw)
    previous = pose; render = pose
    vx = 0; vy = 0; vz = 0
    yawVelocity = 0; pitchVelocity = 0; rollVelocity = 0
    clearTargets()
    dockTarget = -1
    change(.flying)
  }

  private func respawnNearest() {
    placeOnDock(city.nearestDock(pose.x, pose.z))
  }

  private func clearTargets() {
    refX = 0; refY = 0; refZ = 0
    refAx = 0; refAy = 0; refAz = 0
    modelX = 0; modelZ = 0; modelAx = 0; modelAz = 0
    settledFor = 0; settledHeightFor = 0
    holdingPosition = false; holdingHeight = false
  }

  private func change(_ next: Phase) {
    phase = next
    phaseTime = 0
    landingPressed = 0
  }

  func toggleTakeoffLanding() {
    switch phase {
    case .landed: takeOff()
    case .crashed: respawnNearest()
    case .landing: change(.flying)
    default: change(.landing)
    }
  }

  func toggleReturnHome() {
    switch phase {
    case .returnHome: dockTarget = -1; change(.flying)
    case .flying, .takeoff, .landing: startReturn()
    default: break
    }
  }

  /// Picks the nearest dock and a cruising height that clears everything on the way there.
  private func startReturn() {
    let dock = city.nearestDock(pose.x, pose.z)
    let d = city.docks
    dockTarget = dock
    let clear = city.highestAlong(pose.x, pose.z, d[dock * 3], d[dock * 3 + 2]) + 2
    returnAltitude = min(max(clear, d[dock * 3 + 1] + 4), DroneSim.CEILING - 4)
    change(.returnHome)
  }

  private func takeOff() {
    if !canTakeOff { return }
    clearTargets()
    takeoffHeight = pose.y + 2.6
    change(.takeoff)
  }

  func update(_ dt: Float, _ input: PilotInput) {
    accumulator += min(dt, 0.1)
    while accumulator >= DroneSim.STEP {
      accumulator -= DroneSim.STEP
      previous = pose
      step(input)
    }
    render = DronePose.interpolate(previous, pose, accumulator / DroneSim.STEP)
  }

  private func step(_ input: PilotInput) {
    let dt = DroneSim.STEP
    let GEAR = DroneSim.GEAR, CEILING = DroneSim.CEILING
    time += dt
    phaseTime += dt
    ground = city.support(pose.x, pose.z, pose.y - GEAR + 0.08, 0.15)

    if phase == .landed {
      vx = 0; vy = 0; vz = 0; thrust = 0
      pose.pitch *= 1 - 10 * dt; pose.roll *= 1 - 10 * dt
      pose.rotor *= 1 - 2 * dt
      if onDock {
        battery = min(100, battery + DroneSim.CHARGE_RATE * dt)
        lowBatteryHandled = false
        strandedFor = 0
      } else if battery < 1 {
        // Flat battery away from a dock: it is carried back to the nearest one.
        strandedFor += dt
        if strandedFor > 2 { depletions += 1; respawnNearest(); return }
      }
      if input.climb > 0.45 { takeOff() }
      return
    }
    if phase == .crashed {
      fall(dt)
      return
    }

    let pilot = max(max(abs(input.forward), abs(input.strafe)), abs(input.climb))
    if (phase == .returnHome || phase == .landing) && phaseTime > 0.4 {
      let override = phase == .landing ? max(max(abs(input.forward), abs(input.strafe)), input.climb) : pilot
      if override > 0.4 { dockTarget = -1; change(.flying) }
    }
    if battery < DroneSim.LOW_BATTERY && !lowBatteryHandled && phase == .flying {
      lowBatteryHandled = true
      autoReturns += 1
      startReturn()
    }
    if battery <= 0 {
      // Out of power: the motors stop and it falls.
      depletions += 1
      dockTarget = -1
      change(.crashed)
      fall(dt)
      return
    }

    var wantX: Float = 0, wantY: Float = 0, wantZ: Float = 0
    var wantYawRate: Float = 0
    let sy = sin(pose.yaw), cy = cos(pose.yaw)
    switch phase {
    case .takeoff:
      if phaseTime > 0.8 { wantY = ((takeoffHeight - pose.y) * 2).clamped(0.3, 3) }
      if pose.y >= takeoffHeight - 0.2 || (phaseTime > 1.4 && pilot > 0.3) { change(.flying) }
      wantYawRate = input.yaw * mode.yawRate * yawSensitivity
    case .flying:
      var forward = input.forward, strafe = input.strafe
      let magnitude = hypot(forward, strafe)
      if magnitude > 1 { forward /= magnitude; strafe /= magnitude }
      wantX = (sy * forward + cy * strafe) * mode.speed
      wantZ = (-cy * forward + sy * strafe) * mode.speed
      wantY = input.climb * mode.climb
      if height < 4 && wantY < 0 { wantY = max(wantY, -(0.5 + height * 0.8)) }
      if pose.y > CEILING - 4 { wantY = min(wantY, max(-1, CEILING - pose.y)) }
      wantYawRate = input.yaw * mode.yawRate * yawSensitivity
      landingPressed = input.climb < -0.6 && height < 0.08 ? landingPressed + dt : 0
    case .landing:
      wantY = -min(2.6, 0.55 + height * 0.45)
      wantYawRate = input.yaw * mode.yawRate * yawSensitivity
      landingPressed = height < 0.08 ? landingPressed + dt : 0
      if dockTarget >= 0 {
        // Keep centered over the deck all the way down.
        let d = city.docks
        wantX = ((d[dockTarget * 3] - pose.x) * 1.5).clamped(-1.5, 1.5)
        wantZ = ((d[dockTarget * 3 + 2] - pose.z) * 1.5).clamped(-1.5, 1.5)
      }
    case .returnHome:
      if dockTarget < 0 { startReturn() }
      let d = city.docks
      let tx = d[dockTarget * 3], ty = d[dockTarget * 3 + 1], tz = d[dockTarget * 3 + 2]
      let dx = tx - pose.x, dz = tz - pose.z
      let distance = hypot(dx, dz)
      let above = pose.y - GEAR - ty
      if distance < 0.45 && speed < 0.6 && above < 2.2 && above > -0.2 {
        change(.landing)
      } else if distance < 2.6 && above < 0 {
        // Underneath the deck: slide out from under it before climbing.
        let away: Float = distance > 0.05 ? distance : 1
        wantX = -dx / away * 3; wantZ = -dz / away * 3
        wantY = 1
      } else if distance > 6 && pose.y < returnAltitude - 1.5 {
        wantY = min(7, (returnAltitude - pose.y) * 1.2 + 0.5)
      } else {
        let pace = min(14, distance * 0.7 + 0.3)
        if distance > 0.05 { wantX = dx / distance * pace; wantZ = dz / distance * pace }
        if distance > 6 {
          wantY = ((returnAltitude - pose.y) * 0.8).clamped(-3, 3)
          wantYawRate = (wrapAngle(atan2(dx, -dz) - pose.yaw) * 2).clamped(-1.4, 1.4)
        } else {
          // The air above a junction is open, so come down while closing in.
          wantY = ((ty + 1.2 + GEAR - pose.y) * 0.9).clamped(-3.5, 1.5)
        }
      }
    default: break
    }

    // Soft geofence: slide along the boundary instead of hitting a wall.
    wantX = wantX.clamped(min(14, (-City.BOUNDS - pose.x) * 0.5), max(-14, (City.BOUNDS - pose.x) * 0.5))
    wantZ = wantZ.clamped(min(14, (-City.BOUNDS - pose.z) * 0.5), max(-14, (City.BOUNDS - pose.z) * 0.5))

    obstacle = .greatestFiniteMagnitude
    let wanted = hypot(wantX, wantZ)
    if mode.avoid && phase == .flying && wanted > 0.2 {
      let dx = wantX / wanted, dz = wantZ / wanted
      let reach = max(wanted, speed)
      let range = 4 + reach * reach / mode.accel
      let hit = city.raycast(pose.x, pose.y, pose.z, dx, 0, dz, range, &normal)
      if hit < range {
        let into = -(wantX * normal[0] + wantZ * normal[2])
        if into > 0 {
          let clearance = hit * into / wanted - 1.6
          let allowed: Float = clearance <= 0 ? 0 : min(sqrt(mode.accel * clearance), clearance * 1.4)
          if allowed < into {
            wantX += normal[0] * (into - allowed); wantZ += normal[2] * (into - allowed)
            obstacle = hit
          }
        }
      }
    }

    let gps = mode.gps || phase != .flying
    let changeX = wantX - refX, changeZ = wantZ - refZ
    let changeSize = hypot(changeX, changeZ)
    let pull = changeSize * DroneSim.EASE > mode.accel ? mode.accel / changeSize : DroneSim.EASE
    let jerkX = changeX * pull - refAx, jerkZ = changeZ * pull - refAz
    let jerk = hypot(jerkX, jerkZ)
    let jerkLimit = mode.accel * DroneSim.JERK * dt
    let jerkScale = jerk > jerkLimit ? jerkLimit / jerk : 1
    refAx += jerkX * jerkScale; refAz += jerkZ * jerkScale
    refX += refAx * dt; refZ += refAz * dt
    if changeSize < 0.01 && hypot(refAx, refAz) < 0.05 {
      refX = wantX; refZ = wantZ; refAx = 0; refAz = 0
    }
    let A = DroneSim.AGILITY
    modelAx += (A * A * (refX - modelX) - 2 * A * modelAx) * dt
    modelAz += (A * A * (refZ - modelZ) - 2 * A * modelAz) * dt
    modelX += modelAx * dt; modelZ += modelAz * dt
    var ax: Float, az: Float
    let maxTilt = mode.tilt
    let horizontalDrag = 0.06 + 0.009 * sqrt(vx * vx + vy * vy + vz * vz)
    if gps {
      var targetX = modelX, targetZ = modelZ
      if wantX == 0 && wantZ == 0 {
        if !holdingPosition && hypot(modelX, modelZ) < 0.03 {
          settledFor += dt
          if speed < 0.12 || settledFor > 0.6 {
            holdingPosition = true
            holdX = pose.x + vx / 2; holdZ = pose.z + vz / 2
          }
        }
        if holdingPosition {
          targetX += ((holdX - pose.x) * 1.4).clamped(-2, 2)
          targetZ += ((holdZ - pose.z) * 1.4).clamped(-2, 2)
        }
      } else {
        holdingPosition = false
        settledFor = 0
      }
      ax = refAx + (targetX - vx) * 3 + vx * horizontalDrag
      az = refAz + (targetZ - vz) * 3 + vz * horizontalDrag
    } else {
      holdingPosition = false
      ax = wantX / mode.speed * GRAVITY * tan(maxTilt)
      az = wantZ / mode.speed * GRAVITY * tan(maxTilt)
      refX = vx; refZ = vz; refAx = 0; refAz = 0
      modelX = vx; modelZ = vz; modelAx = 0; modelAz = 0
    }
    let horizontal = hypot(ax, az)
    let horizontalLimit = GRAVITY * tan(maxTilt)
    if horizontal > horizontalLimit { ax *= horizontalLimit / horizontal; az *= horizontalLimit / horizontal }

    let C = DroneSim.CLIMB_ACCEL
    let wantAy = ((wantY - refY) * 6).clamped(-C, C)
    refAy = approach(refAy, wantAy, C * 9 * dt)
    refY += refAy * dt
    if abs(wantY - refY) < 0.01 && abs(refAy) < 0.08 { refY = wantY; refAy = 0 }
    var targetY = refY
    if wantY == 0 {
      if !holdingHeight && abs(refY) < 0.03 {
        settledHeightFor += dt
        if abs(vy) < 0.08 || settledHeightFor > 0.5 { holdingHeight = true; holdY = pose.y + vy / 3 }
      }
      if holdingHeight { targetY += ((holdY - pose.y) * 2).clamped(-2, 2) }
    } else {
      holdingHeight = false
      settledHeightFor = 0
    }
    let ay = refAy + (targetY - vy) * 4.5 + vy * horizontalDrag

    // Solve the lean that points the thrust vector along the wanted acceleration.
    let localForward = ax * sy - az * cy
    let localRight = ax * cy + az * sy
    let total = sqrt(localForward * localForward + localRight * localRight + GRAVITY * GRAVITY)
    let wantRoll = asin(localRight / total)
    let wantPitch = atan2(localForward, GRAVITY)
    pitchVelocity += (A * A * (wantPitch - pose.pitch) - 2 * A * pitchVelocity) * dt
    rollVelocity += (A * A * (wantRoll - pose.roll) - 2 * A * rollVelocity) * dt
    pose.pitch += pitchVelocity * dt
    pose.roll += rollVelocity * dt
    let yawResponse: Float = abs(wantYawRate) < abs(yawVelocity) ? 22 : 11
    yawVelocity += (wantYawRate - yawVelocity) * min(1, yawResponse * dt)
    if wantYawRate == 0 && abs(yawVelocity) < 0.001 { yawVelocity = 0 }
    pose.yaw += yawVelocity * dt

    let sp = sin(pose.pitch), cp = cos(pose.pitch), sr = sin(pose.roll), cr = cos(pose.roll)
    let spool: Float = phase == .takeoff ? (phaseTime / 0.8).clamped(0, 1) : 1
    let aboveGround = pose.y - GEAR - ground
    let cushion: Float = aboveGround < 1.2 ? 1 + 0.05 * (1 - max(0, aboveGround) / 1.2) : 1
    let command = ((GRAVITY + ay) / max(0.5, cp * cr) / cushion).clamped(0, DroneSim.MAX_THRUST) * spool * spool
    thrust += (command - thrust) * min(1, dt / 0.045)
    let lift = thrust * cushion
    let pushForward = lift * sp * cr, pushRight = lift * sr

    var windX: Float = 0, windZ: Float = 0
    if windEnabled && aboveGround > 0.2 {
      let strength = (0.15 + min(pose.y, 80) * 0.004) * windScale
      windX = strength * (0.6 + sin(time * 0.7) * 0.3 + sin(time * 2.3) * 0.2)
      windZ = strength * (0.3 + sin(time * 0.43 + 2) * 0.4 + sin(time * 1.7) * 0.15)
    }
    vx += (sy * pushForward + cy * pushRight + windX - vx * horizontalDrag) * dt
    vy += (lift * cp * cr - GRAVITY - vy * horizontalDrag) * dt
    vz += (-cy * pushForward + sy * pushRight + windZ - vz * horizontalDrag) * dt
    pose.x += vx * dt; pose.y += vy * dt; pose.z += vz * dt
    pose.rotor = max(0.25 * spool, sqrt(max(0, thrust) / GRAVITY))

    let load = thrust / GRAVITY
    battery = max(0, battery - dt * DroneSim.HOVER_DRAIN * (0.7 + 0.3 * load * sqrt(max(0, load))))

    collide()
    if phase != .crashed && landingPressed > 0.55 {
      clearTargets()
      vx = 0; vy = 0; vz = 0; yawVelocity = 0
      dockTarget = -1
      change(.landed)
    }
  }

  private func fall(_ dt: Float) {
    thrust = 0
    pose.rotor *= 1 - 3 * dt
    vy -= GRAVITY * dt
    vx *= 1 - 0.5 * dt; vz *= 1 - 0.5 * dt
    pose.x += vx * dt; pose.y += vy * dt; pose.z += vz * dt
    let floor = ground + 0.15
    if pose.y < floor {
      pose.y = floor
      vy = vy < -2 ? -vy * 0.25 : 0
      vx *= 1 - 8 * dt; vz *= 1 - 8 * dt
    } else {
      pose.pitch += 3.5 * dt; pose.roll += 2.1 * dt
    }
    if phaseTime > 3.5 { respawnNearest() }
  }

  private func collide() {
    let GEAR = DroneSim.GEAR
    let below = previous.y - GEAR + 0.08
    let floor = max(ground, city.support(pose.x, pose.z, below, 0.15))
    if pose.y - GEAR < floor {
      pose.y = floor + GEAR
      hit(0, 1, 0, 0)
      if phase == .flying || phase == .landing {
        vx *= 1 - 6 * DroneSim.STEP; vz *= 1 - 6 * DroneSim.STEP
      }
    }
    // Colliders are registered in every chunk they overlap, so the drone's own chunk is enough
    // unless it is right on a boundary.
    let r = DroneSim.RADIUS + 0.05
    for j in cellOf(pose.z - r)...cellOf(pose.z + r) {
      for i in cellOf(pose.x - r)...cellOf(pose.x + r) { collideBoxes(j * City.CELLS + i) }
    }
    if pose.y < 8 { collideTraffic() }
  }

  private func cellOf(_ v: Float) -> Int { Int(floor((v + City.HALF) / City.CELL)).clamped(0, City.CELLS - 1) }

  private func collideBoxes(_ index: Int) {
    let c = city.chunks[index]
    if pose.y - DroneSim.RADIUS > c.top { return }
    let limit = pose.y - DroneSim.GEAR + 0.03
    let b = c.boxes
    var k = 0
    while k < c.count * 8 {
      if b[k + 7] > limit { pushOut(b[k], b[k + 1], b[k + 2], b[k + 3], b[k + 4], b[k + 5], b[k + 6], b[k + 7], 0, 0) }
      k += 8
    }
  }

  private func collideTraffic() {
    for car in 0..<traffic.count {
      let x = traffic.x[car], z = traffic.z[car]
      if abs(x - pose.x) > 7 || abs(z - pose.z) > 7 { continue }
      let base = traffic.y[car]
      if pose.y < base - 1 || pose.y > base + 5 { continue }
      // A vehicle's long axis points along its heading: (sin yaw, -cos yaw).
      let c = sin(traffic.yaw[car]), s = -cos(traffic.yaw[car])
      let v = traffic.speed[car]
      pushOut(x, z, c, s, traffic.length(car) / 2, traffic.width(car) / 2, base, base + traffic.height(car), c * v, s * v)
    }
  }

  /// Pushes the drone's sphere out of an oriented box; the box may move with (moveX, moveZ).
  private func pushOut(_ cx: Float, _ cz: Float, _ c: Float, _ s: Float, _ hx: Float, _ hz: Float, _ y0: Float, _ y1: Float, _ moveX: Float, _ moveZ: Float) {
    let R = DroneSim.RADIUS
    let rx = pose.x - cx, rz = pose.z - cz
    let u = rx * c + rz * s, w = -rx * s + rz * c
    if abs(u) > hx + R || abs(w) > hz + R || pose.y < y0 - R || pose.y > y1 + R { return }
    let du = u - u.clamped(-hx, hx)
    let dy = pose.y - pose.y.clamped(y0, y1)
    let dw = w - w.clamped(-hz, hz)
    let squared = du * du + dy * dy + dw * dw
    if squared >= R * R { return }
    var nu: Float = 0, ny: Float = 0, nw: Float = 0
    let push: Float
    if squared > 1e-6 {
      let distance = sqrt(squared)
      nu = du / distance; ny = dy / distance; nw = dw / distance
      push = R - distance
    } else {
      let left = u + hx, right = hx - u
      let back = w + hz, front = hz - w
      let up = y1 - pose.y
      let least = min(min(min(left, right), min(back, front)), up)
      if least == left { nu = -1 } else if least == right { nu = 1 } else if least == back { nw = -1 } else if least == front { nw = 1 } else { ny = 1 }
      push = least + R
    }
    let nx = nu * c - nw * s, nz = nu * s + nw * c
    pose.x += nx * push; pose.y += ny * push; pose.z += nz * push
    hit(nx, ny, nz, moveX * nx + moveZ * nz)
  }

  private func hit(_ nx: Float, _ ny: Float, _ nz: Float, _ surfaceSpeed: Float) {
    let approaching = vx * nx + vy * ny + vz * nz - surfaceSpeed
    if approaching >= 0 { return }
    let force = -approaching
    let bounce: Float = force > 1.5 ? 1.25 : 1
    vx -= nx * approaching * bounce; vy -= ny * approaching * bounce; vz -= nz * approaching * bounce
    let into = refX * nx + refZ * nz
    if into < 0 { refX -= nx * into; refZ -= nz * into }
    let pushing = refAx * nx + refAz * nz
    if pushing < 0 { refAx -= nx * pushing; refAz -= nz * pushing }
    let modelled = modelX * nx + modelZ * nz
    if modelled < 0 { modelX -= nx * modelled; modelZ -= nz * modelled }
    if abs(ny) < 0.8 { holdingPosition = false }
    if force > 1.5 { impact = max(impact, force) }
    if force > 9 && phase != .crashed {
      crashes += 1
      dockTarget = -1
      change(.crashed)
    }
  }
}
