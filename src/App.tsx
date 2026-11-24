import { useEffect, useMemo, useRef, useState } from 'react';
import { Camera, CameraOff, Check, Copy, RefreshCw, Smartphone } from 'lucide-react';
import { useCameraBroadcast, type BroadcastStatus } from './useCameraBroadcast';
import { isCode, newCode, normalizeCode } from './pairing';
import { encodeQr } from './qr';
import { landscapeStream } from './landscape';

type Facing = 'environment' | 'user';
type Quality = '720' | '1080';
const CODE_KEY = 'skycop.pairing-code';

function savedCode() {
  try {
    const value = localStorage.getItem(CODE_KEY);
    if (value && isCode(value)) return value;
  } catch { /* storage can be unavailable */ }
  const fresh = newCode();
  try { localStorage.setItem(CODE_KEY, fresh); } catch { /* not essential */ }
  return fresh;
}

function clock(seconds: number) {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60).toString().padStart(2, '0');
  const s = (seconds % 60).toString().padStart(2, '0');
  return h ? `${h}:${m}:${s}` : `${m}:${s}`;
}

function time(date: Date) {
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
}

const STATUS_TEXT: Record<BroadcastStatus, string> = {
  idle: 'Offline',
  opening: 'Opening pairing',
  waiting: 'Waiting for phone',
  connecting: 'Connecting',
  connected: 'Live on phone',
  error: 'Pairing failed',
};

function QrCode({ text }: { text: string }) {
  const path = useMemo(() => {
    const modules = encodeQr(text);
    let d = '';
    modules.forEach((row, y) => row.forEach((dark, x) => { if (dark) d += `M${x + 2} ${y + 2}h1v1h-1z`; }));
    return { d, size: modules.length + 4 };
  }, [text]);
  return (
    <svg className="qr" viewBox={`0 0 ${path.size} ${path.size}`} role="img" aria-label="QR code that opens this pairing code in the SkyCop app" shapeRendering="crispEdges">
      <rect width={path.size} height={path.size} fill="#fff" />
      <path d={path.d} fill="#000" />
    </svg>
  );
}

/** What a phone sees after scanning the QR code. */
function JoinPage({ code }: { code: string }) {
  const android = /android/i.test(navigator.userAgent);
  const deepLink = `skycop://join?code=${code}`;
  const href = android
    ? `intent://join?code=${code}#Intent;scheme=skycop;package=com.skycop.trafficmonitoring;end`
    : deepLink;
  return (
    <main className="join">
      <header className="join-brand">
        <img src={`${import.meta.env.BASE_URL}skycop-logo.svg`} alt="" width={36} height={22} />
        <span>SkyCop</span>
      </header>
      <h1>Open the camera in SkyCop</h1>
      <p className="join-lead">This phone will connect to the camera station with code</p>
      <p className="join-code num">{code}</p>
      <a className="button button-primary join-open" href={href}><Smartphone size={18} aria-hidden /> Open in the SkyCop app</a>
      <p className="join-note">Nothing happens? Open SkyCop yourself and type the code. Any network works: Wi-Fi or mobile data.</p>
    </main>
  );
}

export default function App() {
  const params = new URLSearchParams(location.search);
  const joinCode = normalizeCode(params.get('join') ?? '');
  if (isCode(joinCode)) return <JoinPage code={joinCode} />;
  return <Station />;
}

function Station() {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [facing, setFacing] = useState<Facing>('environment');
  const [quality, setQuality] = useState<Quality>('720');
  const [code, setCode] = useState(savedCode);
  const [cameraError, setCameraError] = useState('');
  const [starting, setStarting] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const [copied, setCopied] = useState(false);
  const broadcast = useCameraBroadcast(stream, code);
  const { status, stats, log } = broadcast;
  const joinUrl = `${location.origin}${import.meta.env.BASE_URL}?join=${code}`;

  useEffect(() => {
    if (videoRef.current) videoRef.current.srcObject = stream;
  }, [stream]);

  useEffect(() => {
    if (!stream) return undefined;
    setElapsed(0);
    const timer = window.setInterval(() => setElapsed((s) => s + 1), 1000);
    return () => window.clearInterval(timer);
  }, [stream]);

  useEffect(() => () => stream?.getTracks().forEach((track) => track.stop()), [stream]);

  async function startCamera() {
    setCameraError('');
    setStarting(true);
    try {
      if (!navigator.mediaDevices?.getUserMedia) throw new Error('Camera access needs HTTPS or localhost.');
      const height = quality === '1080' ? 1080 : 720;
      const media = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: { facingMode: { ideal: facing }, height: { ideal: height }, width: { ideal: (height * 16) / 9 }, frameRate: { ideal: 30 } },
      });
      // The headset gets a landscape (or square) picture even when this phone is held upright.
      setStream(landscapeStream(media));
      broadcast.note('Camera on');
    } catch (error) {
      const name = error instanceof DOMException ? error.name : '';
      setCameraError(name === 'NotAllowedError'
        ? 'Camera permission was denied. Allow the camera for this site in the browser settings.'
        : name === 'NotFoundError' ? 'No camera was found on this device.'
          : error instanceof Error ? error.message : 'The camera could not be started.');
    } finally {
      setStarting(false);
    }
  }

  // Pairing opens as soon as the camera is on; the phone can join at any time after that.
  useEffect(() => {
    if (stream && status === 'idle') void broadcast.start();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stream]);

  function stopCamera() {
    broadcast.stop();
    stream?.getTracks().forEach((track) => track.stop());
    setStream(null);
    broadcast.note('Camera off');
  }

  function rotateCode() {
    const fresh = newCode();
    try { localStorage.setItem(CODE_KEY, fresh); } catch { /* not essential */ }
    setCode(fresh);
  }

  async function copyLink() {
    try {
      await navigator.clipboard.writeText(joinUrl);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1600);
    } catch { /* clipboard blocked; the link is still visible */ }
  }

  const live = status === 'connected';
  const headline = !stream
    ? 'Start the camera, then connect the SkyCop app with the code.'
    : live
      ? `Streaming to the phone for ${clock(elapsed)}${stats?.height ? ` at ${stats.height}p` : ''}.`
      : status === 'connecting'
        ? 'The phone is joining.'
        : `Camera on. Waiting for the SkyCop app to join with ${code}.`;

  return (
    <div className="shell">
      <header className="topbar">
        <a className="wordmark" href={import.meta.env.BASE_URL}>
          <img src={`${import.meta.env.BASE_URL}skycop-logo.svg`} alt="" width={34} height={21} />
          SkyCop
        </a>
        <span className={`state state-${live ? 'live' : stream ? 'on' : 'off'}`}>
          <span className="state-dot" aria-hidden /> {STATUS_TEXT[status]}
        </span>
      </header>

      <main className="canvas">
        <div className="title-row">
          <div>
            <h1>Camera station</h1>
            <p className="finding" aria-live="polite">{headline}</p>
          </div>
          <div className="title-actions">
            <label className="select">
              <span className="visually-hidden">Camera</span>
              <select value={facing} onChange={(e) => setFacing(e.target.value as Facing)} disabled={Boolean(stream)}>
                <option value="environment">Rear camera</option>
                <option value="user">Front camera</option>
              </select>
            </label>
            <div className="segmented" role="radiogroup" aria-label="Video quality">
              {(['720', '1080'] as Quality[]).map((q) => (
                <button key={q} role="radio" aria-checked={quality === q} disabled={Boolean(stream)} onClick={() => setQuality(q)}>{q}p</button>
              ))}
            </div>
            {stream ? (
              <button className="button" onClick={stopCamera}><CameraOff size={16} aria-hidden /> Stop camera</button>
            ) : (
              <button className="button button-primary" onClick={() => void startCamera()} disabled={starting}>
                <Camera size={16} aria-hidden /> {starting ? 'Starting…' : 'Start camera'}
              </button>
            )}
          </div>
        </div>

        {(cameraError || broadcast.error) && (
          <p className="alert" role="alert">{cameraError || broadcast.error}</p>
        )}

        <div className="grid">
          <section className="panel feed" aria-label="Camera feed">
            <div className={`viewport ${stream ? 'viewport-on' : ''}`}>
              <video ref={videoRef} autoPlay muted playsInline aria-label="Camera preview" />
              {!stream && (
                <div className="viewport-empty">
                  <p>No camera running</p>
                  <button className="button button-primary" onClick={() => void startCamera()} disabled={starting}>
                    <Camera size={16} aria-hidden /> Start camera
                  </button>
                </div>
              )}
              {stream && <span className={`pill ${live ? 'pill-live' : 'pill-neutral'}`}>{live ? 'Live on phone' : 'Preview only'}</span>}
            </div>
            <dl className="readout">
              <div><dt>Resolution</dt><dd className="num">{stats?.width ? `${stats.width}×${stats.height}` : '—'}</dd></div>
              <div><dt>Frame rate</dt><dd className="num">{stats ? `${stats.fps}` : '—'}<small> fps</small></dd></div>
              <div><dt>Bitrate</dt><dd className="num">{stats?.kbps ? (stats.kbps / 1000).toFixed(1) : '—'}<small> Mb/s</small></dd></div>
              <div><dt>Delay</dt><dd className="num">{stats?.rttMs != null ? stats.rttMs : '—'}<small> ms</small></dd></div>
              <div><dt>Session</dt><dd className="num">{stream ? clock(elapsed) : '—'}</dd></div>
            </dl>
          </section>

          <aside className="side">
            <section className="panel pair" aria-labelledby="pair-title">
              <h2 id="pair-title">Connect the SkyCop app</h2>
              <div className="pair-body">
                <QrCode text={joinUrl} />
                <div className="pair-code">
                  <span className="label">Pairing code</span>
                  <span className="code num">{code}</span>
                  <div className="pair-actions">
                    <button className="button button-small" onClick={() => void copyLink()}>
                      {copied ? <Check size={14} aria-hidden /> : <Copy size={14} aria-hidden />} {copied ? 'Copied' : 'Copy link'}
                    </button>
                    <button className="button button-small" onClick={rotateCode} disabled={status === 'connected'}>
                      <RefreshCw size={14} aria-hidden /> New code
                    </button>
                  </div>
                </div>
              </div>
              <p className="pair-help">Scan with the phone's camera, or open SkyCop and type the code. Then tap <strong>Watch in VR</strong>. Any network works; on the same Wi-Fi the picture is sharpest.</p>
            </section>

            <section className="panel log" aria-labelledby="log-title">
              <h2 id="log-title">Session log</h2>
              {log.length === 0 ? (
                <ol className="timeline timeline-pending" aria-label="What happens next">
                  {['Camera on', 'Phone joins with the code', 'Video live on the phone', 'Phone in the headset'].map((step) => (
                    <li key={step}><time className="num">--:--:--</time><span>{step}</span></li>
                  ))}
                </ol>
              ) : (
                <ol className="timeline">
                  {log.slice(0, 9).map((entry, i) => (
                    <li key={`${entry.at.getTime()}-${i}`}><time className="num">{time(entry.at)}</time><span>{entry.text}</span></li>
                  ))}
                </ol>
              )}
            </section>
          </aside>
        </div>
      </main>

      <footer className="footer">
        <span>Everything that passes through the public relay is encrypted with the pairing code. On the same network the video goes directly between the devices.</span>
        <span>SkyCop 0.2</span>
      </footer>
    </div>
  );
}
