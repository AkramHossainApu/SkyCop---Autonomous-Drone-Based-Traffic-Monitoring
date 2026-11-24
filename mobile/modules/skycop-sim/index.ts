import { requireOptionalNativeModule, type NativeModule } from 'expo';

export type SimulationResult = 'exit' | 'error';
export type ControllerButton = 'x' | 'b';

type Events = { onControllerButton: (event: { button: ControllerButton }) => void };

declare class SkyCopSimModule extends NativeModule<Events> {
  /** Opens the native VR view: the flight simulation, or the live camera when `startLive` is true. */
  openSimulation(liveAvailable: boolean, startLive: boolean): Promise<SimulationResult>;
  /** The live camera stream the VR view should show: a react-native-webrtc stream URL, "relay", or null. */
  setLiveStream(streamUrl: string | null): void;
  watchControllerButtons(): Promise<void>;
  sha256Hex(text: string): string;
  /** AES-256-GCM, returned as base64 of iv + ciphertext + tag. */
  seal(keyHex: string, plain: string): string;
  open(keyHex: string, sealed: string): string | null;
  /**
   * Decrypts one relayed video frame (base64 of iv + ciphertext + tag holding a JPEG), hands it to
   * the VR view, and returns the JPEG as base64 for the preview; null if it does not decrypt.
   */
  openFrame(keyHex: string, sealed: string): string | null;
  /** Decrypts and decodes one H.264 access unit from the relay; returns a preview JPEG (base64) when a new one is ready. */
  openVideo(keyHex: string, sealed: string, gap: boolean): string | null;
  /** Frees the relay's video decoder. */
  closeVideo(): void;
  loadValue(key: string): string | null;
  saveValue(key: string, value: string): void;
}

export default requireOptionalNativeModule<SkyCopSimModule>('SkyCopSim');
