import CoreImage
import QuartzCore
import CoreMedia
import Foundation
import VideoToolbox

/// Plays relay frames at the pace they were filmed (Pacer in RelayVideo.kt has the same logic): each
/// frame shows at its capture time plus a delay just long enough to cover how unevenly they arrive.
final class Pacer {
  private var delays = [Double](repeating: 0, count: 90)
  private var count = 0, next = 0
  private var lastDue = 0.0
  /// Extra delay after a frame came too late: the picture pauses once instead of rushing to catch up.
  private var shift = 0.0

  /// The local media time (seconds) at which a frame captured at `captured` (camera ms) should show.
  func due(captured: Double, arrived: Double) -> Double {
    let raw = arrived * 1000 - captured
    if count > 0 && abs(raw - delays[(next + delays.count - 1) % delays.count]) > 4000 { count = 0; next = 0 }
    delays[next] = raw; next = (next + 1) % delays.count; if count < delays.count { count += 1 }
    let sorted = delays[0..<count].sorted()
    let fastest = sorted[0], slow = sorted[((count - 1) * 90) / 100]
    let buffer = min(1000, max(100, slow - fastest + 40))
    // Running late eases back down a little with every frame (about 30 ms a second).
    if shift > 0 { shift = max(0, shift - 0.002) }
    var due = (captured + fastest + buffer) / 1000 + shift
    let now = CACurrentMediaTime()
    if due < now { shift += now - due; due = now }
    if due < lastDue { due = lastDue }
    if due > now + 1.5 { shift = 0; due = now }
    lastDue = due
    return due
  }

  func reset() { count = 0; next = 0; lastDue = 0; shift = 0 }
}

/// Decodes the H.264 video that comes through the relay with VideoToolbox and hands each picture to
/// LiveFeed (see RelayVideo.kt). Each message is one access unit in Annex B form; key frames carry
/// SPS and PPS. A preview JPEG of the newest picture is made a few times a second for the app's screen.
final class RelayVideo {
  static let shared = RelayVideo()
  /// Only tells the app that pictures are coming: RelayPreviewView draws the moving preview.
  private static let previewInterval = 1.5

  private let worker = DispatchQueue(label: "SkyCopRelayVideo")
  private let lock = NSLock()
  private var queued = 0
  private var dropped = false
  private var preview: String?

  // Worker-queue state.
  private var session: VTDecompressionSession?
  private var format: CMVideoFormatDescription?
  private var sps = Data(), pps = Data()
  private var waitKey = true
  private var lastPreview = 0.0
  private let pacer = Pacer()
  private var due = 0.0
  /// Shows decoded frames when they are due.
  private let display = DispatchQueue(label: "SkyCopRelayDisplay")
  private var waitingFrames = 0
  private let images = CIContext(options: [.cacheIntermediates: false])

  /// Queues one access unit; `gap` says frames were lost before it. Returns a new preview, if any.
  func push(_ unit: Data, gap: Bool, captured: Double) -> String? {
    let arrived = CACurrentMediaTime()
    lock.lock()
    // A decoder that cannot keep up drops whole stretches up to the next key frame.
    let full = queued > 40
    if full { dropped = true } else { queued += 1 }
    let out = preview
    preview = nil
    lock.unlock()
    if !full {
      worker.async { [weak self] in
        guard let self else { return }
        self.due = self.pacer.due(captured: captured, arrived: arrived)
        self.decode(unit, gap: gap)
        self.lock.lock(); self.queued -= 1; self.lock.unlock()
      }
    }
    return out
  }

  /// Frees the decoder when the relay is no longer used.
  func close() {
    worker.async { [weak self] in self?.reset() }
  }

  private func reset() {
    pacer.reset()
    if let session { VTDecompressionSessionInvalidate(session) }
    session = nil
    format = nil
    sps = Data(); pps = Data()
    waitKey = true
  }

  /// The NAL units of an Annex B access unit, without their start codes.
  private static func units(_ data: [UInt8]) -> [ArraySlice<UInt8>] {
    var starts: [Int] = []
    var i = 0
    while i + 3 < data.count {
      if data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 { starts.append(i + 3); i += 3 } else { i += 1 }
    }
    return starts.enumerated().map { index, start in
      var end = index + 1 < starts.count ? starts[index + 1] - 3 : data.count
      while end > start && data[end - 1] == 0 { end -= 1 }
      return data[start..<end]
    }
  }

  private func decode(_ unit: Data, gap: Bool) {
    lock.lock()
    let lost = gap || dropped
    dropped = false
    lock.unlock()
    if lost { waitKey = true }

    let nals = Self.units([UInt8](unit))
    let key = nals.contains { ($0.first ?? 0) & 0x1f == 5 }
    if waitKey && !key { return }
    if let s = nals.first(where: { ($0.first ?? 0) & 0x1f == 7 }), let p = nals.first(where: { ($0.first ?? 0) & 0x1f == 8 }),
       session == nil || Data(s) != sps || Data(p) != pps {
      reset()
      if !makeSession(sps: Data(s), pps: Data(p)) { return }
    }
    guard let session, let format else { return }
    if key { waitKey = false }

    // VideoToolbox takes length-prefixed NAL units; parameter sets live in the format description.
    var avcc = Data()
    for nal in nals {
      let type = (nal.first ?? 0) & 0x1f
      if type == 7 || type == 8 || type == 9 { continue }
      var length = UInt32(nal.count).bigEndian
      avcc.append(Data(bytes: &length, count: 4))
      avcc.append(contentsOf: nal)
    }
    if avcc.isEmpty { return }

    var block: CMBlockBuffer?
    guard CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: avcc.count,
                                             blockAllocator: kCFAllocatorDefault, customBlockSource: nil, offsetToData: 0,
                                             dataLength: avcc.count, flags: 0, blockBufferOut: &block) == noErr, let block else { return }
    let copied = avcc.withUnsafeBytes { raw in
      CMBlockBufferReplaceDataBytes(with: raw.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: avcc.count)
    }
    if copied != noErr { return }
    var sample: CMSampleBuffer?
    var size = avcc.count
    guard CMSampleBufferCreateReady(allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format, sampleCount: 1,
                                    sampleTimingEntryCount: 0, sampleTimingArray: nil, sampleSizeEntryCount: 1,
                                    sampleSizeArray: &size, sampleBufferOut: &sample) == noErr, let sample else { return }
    var flags = VTDecodeInfoFlags()
    let status = VTDecompressionSessionDecodeFrame(session, sampleBuffer: sample, flags: [], infoFlagsOut: &flags) { [weak self] status, _, image, _, _ in
      guard let self, status == noErr, let image else { return }
      self.schedule(image)
    }
    if status != noErr {
      // A session that went bad (the app was in the background, say) starts over at the next key frame.
      reset()
    }
  }

  private func makeSession(sps: Data, pps: Data) -> Bool {
    var description: CMVideoFormatDescription?
    let made = sps.withUnsafeBytes { s in
      pps.withUnsafeBytes { p -> OSStatus in
        let pointers = [s.bindMemory(to: UInt8.self).baseAddress!, p.bindMemory(to: UInt8.self).baseAddress!]
        let sizes = [sps.count, pps.count]
        return CMVideoFormatDescriptionCreateFromH264ParameterSets(allocator: kCFAllocatorDefault, parameterSetCount: 2,
                                                                   parameterSetPointers: pointers, parameterSetSizes: sizes,
                                                                   nalUnitHeaderLength: 4, formatDescriptionOut: &description)
      }
    }
    guard made == noErr, let description else { return false }
    let attributes: [CFString: Any] = [kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange]
    var created: VTDecompressionSession?
    guard VTDecompressionSessionCreate(allocator: kCFAllocatorDefault, formatDescription: description, decoderSpecification: nil,
                                       imageBufferAttributes: attributes as CFDictionary, outputCallback: nil,
                                       decompressionSessionOut: &created) == noErr, let created else { return false }
    VTSessionSetProperty(created, key: kVTDecompressionPropertyKey_RealTime, value: kCFBooleanTrue)
    session = created
    format = description
    self.sps = sps
    self.pps = pps
    return true
  }

  private func schedule(_ image: CVImageBuffer) {
    lock.lock()
    // Too far behind to wait for its turn: show it now.
    let late = waitingFrames > 20
    waitingFrames += 1
    lock.unlock()
    let wait = late ? 0 : max(0, due - CACurrentMediaTime())
    display.asyncAfter(deadline: .now() + wait) { [weak self] in
      guard let self else { return }
      self.show(image)
      self.lock.lock(); self.waitingFrames -= 1; self.lock.unlock()
    }
  }

  private func show(_ image: CVImageBuffer) {
    CVPixelBufferLockBaseAddress(image, .readOnly)
    defer { CVPixelBufferUnlockBaseAddress(image, .readOnly) }
    guard CVPixelBufferGetPlaneCount(image) >= 2,
          let yBase = CVPixelBufferGetBaseAddressOfPlane(image, 0), let cBase = CVPixelBufferGetBaseAddressOfPlane(image, 1) else { return }
    let w = CVPixelBufferGetWidth(image) & ~1, h = CVPixelBufferGetHeight(image) & ~1
    LiveFeed.shared.putNV12(w, h, yBase.assumingMemoryBound(to: UInt8.self), CVPixelBufferGetBytesPerRowOfPlane(image, 0),
                            cBase.assumingMemoryBound(to: UInt8.self), CVPixelBufferGetBytesPerRowOfPlane(image, 1))
    let now = CACurrentMediaTime()
    if now - lastPreview >= Self.previewInterval {
      lastPreview = now
      let picture = CIImage(cvPixelBuffer: image)
      if let jpeg = images.jpegRepresentation(of: picture, colorSpace: CGColorSpaceCreateDeviceRGB(),
                                              options: [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: 0.6]) {
        let text = jpeg.base64EncodedString()
        lock.lock(); preview = text; lock.unlock()
      }
    }
  }
}
