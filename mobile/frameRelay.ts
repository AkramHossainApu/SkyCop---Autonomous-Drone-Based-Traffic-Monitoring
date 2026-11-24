// Phone side of ../src/frameRelay.ts: video that comes through the public MQTT brokers when the
// camera cannot reach this phone directly. Each frame comes as three pieces, one per broker (two
// halves and their XOR); the first two pieces to arrive rebuild it. Frames are then used in order,
// and a frame missing for a moment is given time to turn up before it counts as lost. They are
// decrypted and decoded natively (H.264 on the hardware decoder, or the JPEG fallback), which also
// hands each picture to the VR view; now and then a small JPEG comes back for the on-screen preview.
import SkyCopSim from './modules/skycop-sim';
import { MqttLink, VIDEO_BROKERS } from './mqtt';

/**
 * How long a missing frame may hold up the ones after it. A slow broker can deliver it this late,
 * and the picture is played a little behind anyway (see the Pacer in RelayVideo), so waiting is free.
 */
const WAIT_MS = 500;

type Frame = { video: boolean; data: string; ms: number; at: number };
type Pieces = { parts: (string | undefined)[]; have: number; length: number; ms: number };

function bytesOf(text: string): Uint8Array {
  const out = new Uint8Array(text.length);
  for (let i = 0; i < text.length; i++) out[i] = text.charCodeAt(i);
  return out;
}

function textOf(bytes: Uint8Array): string {
  let out = '';
  for (let i = 0; i < bytes.length; i += 8192) out += String.fromCharCode.apply(null, Array.from(bytes.subarray(i, i + 8192)));
  return out;
}

/** The sealed frame from any two of: first half, second half, their XOR. */
function rebuild(parts: (string | undefined)[], length: number): string {
  const [a, b, x] = parts;
  if (a !== undefined && b !== undefined) return (a + b).slice(0, length);
  const known = bytesOf((a ?? b)!);
  const parity = bytesOf(x!);
  const other = new Uint8Array(known.length);
  for (let i = 0; i < known.length; i++) other[i] = known[i] ^ parity[i];
  const missing = textOf(other);
  return (a !== undefined ? a + missing : missing + b).slice(0, length);
}

export class FrameReceiver {
  private readonly links: MqttLink[];
  private last = 0;
  private readonly waiting = new Map<number, Frame>();
  private readonly pieces = new Map<number, Pieces>();

  constructor(
    topic: string,
    private readonly keyHex: string,
    /** Runs for every frame shown; `preview` is a base64 JPEG when a new one is ready. */
    private readonly onFrame: (preview: string | null) => void,
    /** A frame was lost on the brokers: the camera should send a key frame now. */
    private readonly onLoss: () => void = () => undefined,
  ) {
    this.links = VIDEO_BROKERS.map((url) => new MqttLink(url, topic, (payload) => this.receive(payload), () => undefined));
  }

  open() {
    this.links.forEach((link) => link.open());
  }

  private receive(payload: string) {
    if (payload.startsWith('v3.')) this.piece(payload);
    else if (payload.startsWith('f1.')) {
      const dot = payload.indexOf('.', 3);
      if (dot > 0) this.arrive(Number(payload.slice(3, dot)), { video: false, data: payload.slice(dot + 1), ms: 0, at: Date.now() });
    }
  }

  /** v3.<seq>.<ms>.<piece>.<length>.<bytes> */
  private piece(payload: string) {
    const fields: number[] = [];
    let at = 3;
    for (let i = 0; i < 4; i++) {
      const dot = payload.indexOf('.', at);
      if (dot < 0) return;
      fields.push(Number(payload.slice(at, dot)));
      at = dot + 1;
    }
    const [seq, ms, index, length] = fields;
    if (!Number.isFinite(seq) || !(index >= 0 && index <= 2)) return;
    if (this.done(seq)) return;
    let entry = this.pieces.get(seq);
    if (!entry) {
      entry = { parts: [undefined, undefined, undefined], have: 0, length, ms };
      this.pieces.set(seq, entry);
    }
    if (entry.parts[index] !== undefined) return;
    entry.parts[index] = payload.slice(at);
    entry.have += 1;
    if (entry.have < 2) return;
    this.pieces.delete(seq);
    this.arrive(seq, { video: true, data: rebuild(entry.parts, entry.length), ms: entry.ms, at: Date.now() });
  }

  /** Already shown, or already rebuilt and waiting its turn. */
  private done(seq: number) {
    // A big step back means the camera started again.
    if (this.last > 0 && seq <= this.last - 300) { this.last = 0; this.waiting.clear(); this.pieces.clear(); }
    return seq <= this.last || this.waiting.has(seq);
  }

  private arrive(seq: number, frame: Frame) {
    if (!Number.isFinite(seq) || this.done(seq)) return;
    if (this.last === 0) {
      // Joined mid-stream: the picture starts at the next key frame, so ask for one now.
      this.last = seq;
      this.onLoss();
      this.show(frame, true);
      return;
    }
    this.waiting.set(seq, frame);
    this.flush();
  }

  /** Shows waiting frames in order; skips a missing one once it has been waited for long enough. */
  private flush() {
    for (;;) {
      const next = this.waiting.get(this.last + 1);
      if (next) {
        this.waiting.delete(this.last + 1);
        this.last += 1;
        this.show(next, false);
        continue;
      }
      if (this.waiting.size === 0) break;
      let first = Infinity;
      this.waiting.forEach((_, seq) => { if (seq < first) first = seq; });
      const late = this.waiting.get(first)!;
      if (Date.now() - late.at < WAIT_MS) break;
      this.waiting.delete(first);
      this.last = first;
      this.onLoss();
      this.show(late, true);
    }
    // Pieces of frames that can no longer be shown.
    this.pieces.forEach((_, seq) => { if (seq <= this.last) this.pieces.delete(seq); });
  }

  private show(frame: Frame, gap: boolean) {
    if (frame.video) {
      // Raw bytes (each character is one byte); the native side takes base64. After a gap the
      // decoder waits for the next key frame instead of showing a smeared picture, and the capture
      // time lets it play the frames at an even pace however bunched up they arrive.
      this.onFrame(SkyCopSim?.openVideo(this.keyHex, btoa(frame.data), gap, frame.ms) ?? null);
    } else {
      this.onFrame(SkyCopSim?.openFrame(this.keyHex, frame.data) ?? null);
    }
  }

  close() {
    this.links.forEach((link) => link.close());
    SkyCopSim?.closeVideo();
  }
}
