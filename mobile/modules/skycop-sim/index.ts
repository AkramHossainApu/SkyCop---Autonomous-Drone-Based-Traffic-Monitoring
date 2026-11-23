import { requireOptionalNativeModule, type NativeModule } from 'expo';

export type SimulationResult = 'exit' | 'error';
export type ControllerButton = 'x' | 'b';

type Events = { onControllerButton: (event: { button: ControllerButton }) => void };

declare class SkyCopSimModule extends NativeModule<Events> {
  /** Opens the native VR view: the flight simulation, or the live camera when `startLive` is true. */
  openSimulation(liveAvailable: boolean, startLive: boolean): Promise<SimulationResult>;
  /** The live camera stream (react-native-webrtc stream URL) the VR view should show, or null. */
  setLiveStream(streamUrl: string | null): void;
  watchControllerButtons(): Promise<void>;
  sha256Hex(text: string): string;
  /** AES-256-GCM, returned as base64 of iv + ciphertext + tag. */
  seal(keyHex: string, plain: string): string;
  open(keyHex: string, sealed: string): string | null;
  loadValue(key: string): string | null;
  saveValue(key: string, value: string): void;
}

export default requireOptionalNativeModule<SkyCopSimModule>('SkyCopSim');
