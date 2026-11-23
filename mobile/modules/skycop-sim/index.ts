import { requireOptionalNativeModule, type NativeModule } from 'expo';

/** `live` means the pilot pressed X to switch to the live camera. */
export type SimulationResult = 'exit' | 'live' | 'error';
export type ControllerButton = 'x' | 'b';

type Events = { onControllerButton: (event: { button: ControllerButton }) => void };

declare class SkyCopSimModule extends NativeModule<Events> {
  openSimulation(liveAvailable: boolean): Promise<SimulationResult>;
  watchControllerButtons(): Promise<void>;
  sha256Hex(text: string): string;
  /** AES-256-GCM, returned as base64 of iv + ciphertext + tag. */
  seal(keyHex: string, plain: string): string;
  open(keyHex: string, sealed: string): string | null;
  loadValue(key: string): string | null;
  saveValue(key: string, value: string): void;
}

export default requireOptionalNativeModule<SkyCopSimModule>('SkyCopSim');
