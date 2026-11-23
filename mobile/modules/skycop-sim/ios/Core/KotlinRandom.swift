// Kotlin's default `Random(seed)` (XorWow), reproduced bit for bit so the iOS city, traffic and
// trees are identical to the Android build's.
struct KotlinRandom {
  private var x: Int32, y: Int32, z: Int32, w: Int32, v: Int32, addend: Int32

  init(seed: Int32) {
    let seed2 = seed >> 31
    x = seed; y = seed2; z = 0; w = 0; v = ~seed
    addend = (seed << 10) ^ Int32(bitPattern: UInt32(bitPattern: seed2) >> 4)
    for _ in 0..<64 { _ = nextInt() }
  }

  mutating func nextInt() -> Int32 {
    var t = x
    t = t ^ Int32(bitPattern: UInt32(bitPattern: t) >> 2)
    x = y; y = z; z = w
    let v0 = v
    w = v0
    t = (t ^ (t << 1)) ^ v0 ^ (v0 << 4)
    v = t
    addend = addend &+ 362437
    return t &+ addend
  }

  private mutating func nextBits(_ count: Int32) -> Int32 {
    let value = nextInt()
    return Int32(bitPattern: UInt32(bitPattern: value) >> UInt32(32 - count)) & ((-count) >> 31)
  }

  mutating func nextFloat() -> Float { Float(nextBits(24)) / Float(1 << 24) }

  mutating func nextBoolean() -> Bool { nextBits(1) != 0 }

  mutating func nextInt(_ until: Int) -> Int { nextInt(0, until) }

  mutating func nextInt(_ from: Int, _ until: Int) -> Int {
    let n = Int32(until - from)
    precondition(n > 0)
    let result: Int32
    if n & -n == n {
      result = nextBits(31 - Int32(n.leadingZeroBitCount))
    } else {
      var bits: Int32, value: Int32
      repeat {
        bits = Int32(bitPattern: UInt32(bitPattern: nextInt()) >> 1)
        value = bits % n
      } while bits &- value &+ (n - 1) < 0
      result = value
    }
    return from + Int(result)
  }
}
