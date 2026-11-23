// Phone side of the pairing protocol in ../src/pairing.ts. Keep the two in step: same relay, same
// topic and key derivation, same envelope, same chunk format. Crypto runs natively in the SkyCopSim
// module because React Native has no WebCrypto.
import SkyCopSim from './modules/skycop-sim';

const RELAY = 'https://ntfy.sh';
const PART = 3000;
const MAX_AGE_SECONDS = 120;

export type Role = 'camera' | 'viewer';
export type Envelope = {
  v: 1;
  from: Role;
  session: string;
  at: number;
  type: 'ready' | 'hello' | 'offer' | 'answer' | 'bye';
  to?: string;
  sdp?: string;
};

export const CODE_PATTERN = /^[a-hjkmnp-z2-9]{3}-[a-hjkmnp-z2-9]{3}-[a-hjkmnp-z2-9]{3}$/;

/** Accepts "abcdefghj", "ABC DEF GHJ" or a pasted link, and returns "abc-def-ghj". */
export function normalizeCode(value: string): string {
  const fromLink = /[?&](?:code|join)=([^&#\s]+)/i.exec(value)?.[1];
  const letters = (fromLink ?? value).toLowerCase().replace(/[^a-z0-9]/g, '');
  if (letters.length !== 9) return (fromLink ?? value).trim().toLowerCase();
  return `${letters.slice(0, 3)}-${letters.slice(3, 6)}-${letters.slice(6)}`;
}

/** Inserts the dashes while someone types. */
export function formatTyping(value: string): string {
  const letters = value.toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 9);
  return [letters.slice(0, 3), letters.slice(3, 6), letters.slice(6)].filter(Boolean).join('-');
}

export class PairingChannel {
  private socket: WebSocket | null = null;
  private key = '';
  private topic = '';
  private closed = false;
  private retry = 0;
  private parts = new Map<string, string[]>();
  private since = Math.floor(Date.now() / 1000) - 5;
  private timer: ReturnType<typeof setTimeout> | null = null;
  readonly session = Math.random().toString(36).slice(2, 10);

  constructor(
    code: string,
    private readonly role: Role,
    private readonly onMessage: (message: Envelope) => void,
    private readonly onState: (open: boolean) => void,
  ) {
    if (!SkyCopSim) throw new Error('The SkyCop native module is missing from this build.');
    this.key = SkyCopSim.sha256Hex(`skycop/key/${code}`);
    this.topic = `skycop-${SkyCopSim.sha256Hex(`skycop/topic/${code}`).slice(0, 32)}`;
  }

  open() {
    this.connect();
  }

  private connect() {
    if (this.closed) return;
    const socket = new WebSocket(`${RELAY.replace(/^http/, 'ws')}/${this.topic}/ws?since=${this.since}`);
    this.socket = socket;
    socket.onmessage = (event) => {
      try {
        const data = JSON.parse(String(event.data)) as { event: string; message?: string; time?: number };
        if (data.event === 'open') {
          this.retry = 0;
          this.onState(true);
        } else if (data.event === 'message' && data.message) {
          if (data.time) this.since = Math.max(this.since, data.time);
          this.receive(data.message);
        }
      } catch {
        // Ignore anything that is not a relay event.
      }
    };
    socket.onclose = () => {
      if (this.socket !== socket || this.closed) return;
      this.onState(false);
      this.timer = setTimeout(() => this.connect(), Math.min(15000, 1000 * 2 ** this.retry++));
    };
  }

  private receive(body: string) {
    const match = /^s1\.([a-z0-9]+)\.(\d+)\.(\d+)\.(.+)$/.exec(body);
    if (!match) return;
    const [, id, indexText, countText, part] = match;
    const index = Number(indexText); const count = Number(countText);
    if (count < 1 || count > 20 || index >= count) return;
    const parts = this.parts.get(id) ?? new Array<string>(count).fill('');
    parts[index] = part;
    this.parts.set(id, parts);
    if (parts.some((p) => !p)) return;
    this.parts.delete(id);
    const plain = SkyCopSim?.open(this.key, parts.join(''));
    if (!plain) return;
    try {
      const message = JSON.parse(plain) as Envelope;
      if (message.v !== 1 || message.from === this.role) return;
      if (Math.abs(Date.now() / 1000 - message.at) > MAX_AGE_SECONDS) return;
      this.onMessage(message);
    } catch {
      // Ignore.
    }
  }

  async send(type: Envelope['type'], extra: Partial<Envelope> = {}) {
    if (!SkyCopSim) return;
    const message: Envelope = { v: 1, from: this.role, session: this.session, at: Math.floor(Date.now() / 1000), type, ...extra };
    const text = SkyCopSim.seal(this.key, JSON.stringify(message));
    const id = Math.random().toString(36).slice(2, 10);
    const count = Math.ceil(text.length / PART);
    for (let i = 0; i < count; i++) {
      const response = await fetch(`${RELAY}/${this.topic}`, {
        method: 'POST',
        body: `s1.${id}.${i}.${count}.${text.slice(i * PART, (i + 1) * PART)}`,
      });
      if (!response.ok) throw new Error(`The relay refused the message (${response.status}).`);
    }
  }

  close() {
    this.closed = true;
    if (this.timer) clearTimeout(this.timer);
    const socket = this.socket;
    this.socket = null;
    socket?.close();
  }
}

export const ICE_SERVERS = [{ urls: 'stun:stun.l.google.com:19302' }];
