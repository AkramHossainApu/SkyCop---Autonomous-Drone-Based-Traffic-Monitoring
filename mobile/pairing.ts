// Phone side of the pairing protocol in ../src/pairing.ts. Keep the two in step: same relay, same
// topic and key derivation, same envelope, same chunk format. Crypto runs natively in the SkyCopSim
// module because React Native has no WebCrypto.
import SkyCopSim from './modules/skycop-sim';
import { MQTT_BROKERS, MqttLink } from './mqtt';

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

/**
 * Returns the pairing code from a SkyCop QR code, or null for any other QR code. The website's QR
 * holds exactly `<site address>?join=<code>`; the app link `skycop://join?code=<code>` is accepted too.
 */
export function codeFromSkyCopQr(data: string): string | null {
  const text = data.trim();
  const match = /^https?:\/\/[^\s?#]+\?join=([a-z0-9-]{11})$/i.exec(text) ?? /^skycop:\/\/join\?code=([a-z0-9-]{11})$/i.exec(text);
  const code = match?.[1].toLowerCase();
  return code && CODE_PATTERN.test(code) ? code : null;
}

/** Inserts the dashes while someone types. */
export function formatTyping(value: string): string {
  const letters = value.toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 9);
  return [letters.slice(0, 3), letters.slice(3, 6), letters.slice(6)].filter(Boolean).join('-');
}

export class PairingChannel {
  private socket: WebSocket | null = null;
  private links: MqttLink[] = [];
  private ntfyOpen = false;
  private wasOpen = false;
  private done = new Set<string>();
  private openedAt = 0;
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
    private readonly onState: (open: boolean, detail?: string) => void,
  ) {
    if (!SkyCopSim) throw new Error('The SkyCop native module is missing from this build.');
    this.key = SkyCopSim.sha256Hex(`skycop/key/${code}`);
    this.topic = `skycop-${SkyCopSim.sha256Hex(`skycop/topic/${code}`).slice(0, 32)}`;
  }

  open() {
    this.openedAt = Date.now();
    this.links = MQTT_BROKERS.map((url) => new MqttLink(url, `skycop/${this.topic}`, (body) => this.receive(body), (ready, detail) => this.report(ready, detail)));
    this.links.forEach((link) => link.open());
    this.connect();
  }

  /** Reports every transport that comes up (so the other side can be greeted again), and the moment all are down. */
  private report(cameUp: boolean, detail?: string) {
    if (this.closed) return;
    const open = this.ntfyOpen || this.links.some((link) => link.ready);
    if (cameUp) this.onState(true);
    else if (!open && this.wasOpen) this.onState(false, detail);
    this.wasOpen = open;
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
          this.ntfyOpen = true;
          this.report(true);
        } else if (data.event === 'message' && data.message) {
          if (data.time) this.since = Math.max(this.since, data.time);
          this.receive(data.message);
        }
      } catch {
        // Ignore anything that is not a relay event.
      }
    };
    let failure = '';
    socket.onerror = (event) => {
      failure = (event as unknown as { message?: string }).message ?? 'connection error';
    };
    socket.onclose = (event) => {
      if (this.socket !== socket || this.closed) return;
      const detail = failure || `closed (${event.code}${event.reason ? ` ${event.reason}` : ''})`;
      this.ntfyOpen = false;
      this.report(false, detail);
      this.timer = setTimeout(() => this.connect(), Math.min(30000, 1000 * 2 ** this.retry++));
    };
  }

  private receive(body: string) {
    const match = /^s1\.([a-z0-9]+)\.(\d+)\.(\d+)\.(.+)$/.exec(body);
    if (!match) return;
    const [, id, indexText, countText, part] = match;
    if (this.done.has(id)) return;
    const index = Number(indexText); const count = Number(countText);
    if (count < 1 || count > 20 || index >= count) return;
    const parts = this.parts.get(id) ?? new Array<string>(count).fill('');
    parts[index] = part;
    this.parts.set(id, parts);
    if (parts.some((p) => !p)) return;
    this.parts.delete(id);
    this.done.add(id);
    if (this.done.size > 500) this.done.clear();
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
    const bodies = Array.from({ length: count }, (_, i) => `s1.${id}.${i}.${count}.${text.slice(i * PART, (i + 1) * PART)}`);
    // Right after opening, give the brokers a moment to connect before falling back.
    for (let waited = 0; waited < 5000 && !this.links.some((link) => link.ready) && Date.now() - this.openedAt < 8000; waited += 250) {
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
    let sent = false;
    for (const link of this.links) {
      if (bodies.every((body) => link.publish(body))) sent = true;
    }
    if (sent) return;
    // No broker reachable: ntfy.sh, which allows 250 messages per network every 12 hours.
    for (const body of bodies) {
      let response: Response;
      try {
        response = await fetch(`${RELAY}/${this.topic}`, { method: 'POST', body });
      } catch (caught) {
        throw new Error(caught instanceof Error ? caught.message : String(caught));
      }
      if (response.status === 429) throw new Error('the relay is busy for this network, retrying');
      if (!response.ok) throw new Error(`the relay answered ${response.status}`);
    }
  }

  close() {
    this.closed = true;
    this.links.forEach((link) => link.close());
    if (this.timer) clearTimeout(this.timer);
    const socket = this.socket;
    this.socket = null;
    socket?.close();
  }
}

export const ICE_SERVERS = [{ urls: 'stun:stun.l.google.com:19302' }];
