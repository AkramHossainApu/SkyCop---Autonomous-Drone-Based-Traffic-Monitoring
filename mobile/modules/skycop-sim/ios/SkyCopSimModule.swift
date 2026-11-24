import CryptoKit
import ExpoModulesCore
import UIKit

public class SkyCopSimModule: Module {
  private var pending: Promise?

  public func definition() -> ModuleDefinition {
    Name("SkyCopSim")
    Events("onControllerButton")

    AsyncFunction("openSimulation") { (liveAvailable: Bool, startLive: Bool, promise: Promise) in
      guard self.pending == nil else {
        promise.reject("ERR_ALREADY_OPEN", "The simulation is already open.")
        return
      }
      guard let presenter = self.appContext?.utilities?.currentViewController() else {
        promise.reject("ERR_NO_VIEW", "The app is not in the foreground.")
        return
      }
      self.pending = promise
      Textures.prepare()
      let controller = SimulationViewController(liveAvailable: liveAvailable, startLive: startLive) { [weak self] result in
        self?.pending?.resolve(result)
        self?.pending = nil
      }
      presenter.present(controller, animated: false)
    }.runOnQueue(.main)

    // The live camera stream to show in VR, by react-native-webrtc stream URL; nil when there is none.
    Function("setLiveStream") { (streamUrl: String?) in
      DispatchQueue.main.async { [weak self] in
        let module: NSObject? = self?.appContext?.nativeModule(named: "WebRTCModule")
        LiveFeed.shared.follow(module, streamUrl)
      }
    }

    AsyncFunction("watchControllerButtons") {
      ControllerHub.shared.shortcutHandler = { [weak self] button in
        self?.sendEvent("onControllerButton", ["button": button])
      }
    }.runOnQueue(.main)

    // Pairing crypto, byte for byte the same as the website's WebCrypto and PairingCrypto.kt.
    Function("sha256Hex") { (text: String) -> String in
      SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    Function("seal") { (keyHex: String, plain: String) throws -> String in
      let sealed = try AES.GCM.seal(Data(plain.utf8), using: SymmetricKey(data: Self.bytes(keyHex)))
      return sealed.combined!.base64EncodedString()
    }

    Function("open") { (keyHex: String, sealed: String) -> String? in
      guard let data = Data(base64Encoded: sealed), let box = try? AES.GCM.SealedBox(combined: data),
            let plain = try? AES.GCM.open(box, using: SymmetricKey(data: Self.bytes(keyHex))) else { return nil }
      return String(data: plain, encoding: .utf8)
    }

    Function("openFrame") { (keyHex: String, sealed: String) -> String? in
      guard let data = Data(base64Encoded: sealed), let box = try? AES.GCM.SealedBox(combined: data),
            let jpeg = try? AES.GCM.open(box, using: SymmetricKey(data: Self.bytes(keyHex))) else { return nil }
      LiveFeed.shared.pushJpeg(jpeg)
      return jpeg.base64EncodedString()
    }

    Function("openVideo") { (keyHex: String, sealed: String, gap: Bool, capturedMs: Double) -> String? in
      guard let data = Data(base64Encoded: sealed), let box = try? AES.GCM.SealedBox(combined: data),
            let unit = try? AES.GCM.open(box, using: SymmetricKey(data: Self.bytes(keyHex))) else { return nil }
      return RelayVideo.shared.push(unit, gap: gap, captured: capturedMs)
    }

    Function("closeVideo") { RelayVideo.shared.close() }

    Function("loadValue") { (key: String) -> String? in UserDefaults.standard.string(forKey: "skycop-app.\(key)") }
    Function("saveValue") { (key: String, value: String) in UserDefaults.standard.set(value, forKey: "skycop-app.\(key)") }
  }

  private static func bytes(_ hex: String) -> Data {
    var data = Data(capacity: hex.count / 2)
    var index = hex.startIndex
    while index < hex.endIndex {
      let next = hex.index(index, offsetBy: 2)
      data.append(UInt8(hex[index..<next], radix: 16) ?? 0)
      index = next
    }
    return data
  }
}
