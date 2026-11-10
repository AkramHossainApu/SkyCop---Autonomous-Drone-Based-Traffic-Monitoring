import { useEffect, useRef, useState } from 'react';
import {
  ArrowUpRight,
  Camera,
  CameraOff,
  ChevronDown,
  CircleHelp,
  Crosshair,
  MapPin,
  RotateCcw,
  ShieldCheck,
  Video,
  X,
} from 'lucide-react';

type FacingMode = 'environment' | 'user';

function formatDuration(seconds: number) {
  const minutes = Math.floor(seconds / 60).toString().padStart(2, '0');
  const remainder = (seconds % 60).toString().padStart(2, '0');
  return `${minutes}:${remainder}`;
}

export default function App() {
  const videoRef = useRef<HTMLVideoElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [facingMode, setFacingMode] = useState<FacingMode>('environment');
  const [cameraError, setCameraError] = useState('');
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [isStarting, setIsStarting] = useState(false);

  useEffect(() => {
    if (videoRef.current) videoRef.current.srcObject = stream;
  }, [stream]);

  useEffect(() => {
    if (!stream) return;
    const timer = window.setInterval(() => setElapsedSeconds((seconds) => seconds + 1), 1000);
    return () => window.clearInterval(timer);
  }, [stream]);

  useEffect(() => () => {
    streamRef.current?.getTracks().forEach((track) => track.stop());
  }, []);

  useEffect(() => {
    if (!stream) return;
    function handleEscape(event: KeyboardEvent) {
      if (event.key === 'Escape') stopSession();
    }
    window.addEventListener('keydown', handleEscape);
    return () => window.removeEventListener('keydown', handleEscape);
  }, [stream]);

  async function startSession() {
    setCameraError('');
    setIsStarting(true);
    try {
      if (!navigator.mediaDevices?.getUserMedia) {
        throw new Error('Camera access needs a secure connection. Open this page on localhost or HTTPS.');
      }
      const cameraStream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: {
          facingMode: { ideal: facingMode },
          width: { ideal: 1920 },
          height: { ideal: 1080 },
        },
      });
      streamRef.current = cameraStream;
      setStream(cameraStream);
      setElapsedSeconds(0);
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Camera could not be started.';
      setCameraError(message === 'Permission denied' ? 'Camera permission was denied. Allow camera access in your browser settings.' : message);
    } finally {
      setIsStarting(false);
    }
  }

  function stopSession() {
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    setStream(null);
    setElapsedSeconds(0);
    setCameraError('');
  }

  return (
    <main className="app-shell">
      <header className="topbar">
        <a className="brand" href="#home" aria-label="SkyCop home">
          <img src={`${import.meta.env.BASE_URL}skycop-logo.svg`} alt="SkyCop logo" className="brand-logo" />
          <span className="brand-name">SKYCOP<span className="brand-period">.</span></span>
        </a>
        <div className="topbar-center"><span className="topbar-line" /> FIELD STATION <span className="topbar-divider">/</span> CAMERA LIVE</div>
        <div className="system-status"><span className="status-dot" /> SYSTEM READY</div>
      </header>

      <section className="intro" id="home">
        <div className="intro-copy-wrap">
          <div className="eyebrow"><span className="eyebrow-marker" /> DRONE TRAFFIC CONTROL</div>
          <h1>Autonomous Drone-Based<br />Traffic Monitoring<span className="title-period">.</span></h1>
          <p className="intro-copy">VR Police Control</p>
        </div>
      </section>

      <section className="workspace" aria-label="Camera station">
        <div className="camera-column">
          <div className="section-heading">
            <div>
              <span className="section-kicker">01 / CAMERA INPUT</span>
              <h2>Live camera</h2>
            </div>
            <span className={`connection-pill ${stream ? 'connection-pill-live' : ''}`}>
              <span className="pill-dot" /> {stream ? 'LOCAL PREVIEW' : 'NO SIGNAL'}
            </span>
          </div>

          <div className={`preview-frame ${stream ? 'preview-active' : ''}`}>
            <video ref={videoRef} className="camera-video" autoPlay muted playsInline aria-label="Local camera preview" />
            {!stream && (
              <div className="preview-empty">
                <div className="preview-crosshair"><Crosshair size={24} strokeWidth={1.2} /></div>
                <span className="empty-title">Your camera feed starts here</span>
                <span className="empty-caption">Allow camera access to open a local preview.</span>
              </div>
            )}
            <div className="frame-corner corner-tl" /><div className="frame-corner corner-tr" />
            <div className="frame-corner corner-bl" /><div className="frame-corner corner-br" />
            <div className="preview-topline">
              <span className="feed-label"><Video size={13} /> {stream ? 'DEVICE CAMERA' : 'CAMERA INPUT'}</span>
              {stream && <span className="recording-label"><span /> PREVIEWING</span>}
            </div>
            <div className="preview-bottomline">
              <span><MapPin size={12} /> LOCAL DEVICE</span>
              {stream && <span>{formatDuration(elapsedSeconds)}</span>}
            </div>
          </div>

          {cameraError && <div className="camera-error" role="alert"><CircleHelp size={15} /> <span>{cameraError}</span><button aria-label="Dismiss error" onClick={() => setCameraError('')}><X size={15} /></button></div>}

          <div className="camera-controls">
            <label className="camera-select-wrap" htmlFor="camera-facing">
              <Camera size={16} />
              <select id="camera-facing" value={facingMode} onChange={(event) => setFacingMode(event.target.value as FacingMode)} disabled={Boolean(stream)}>
                <option value="environment">Rear camera</option>
                <option value="user">Front camera</option>
              </select>
              <ChevronDown className="select-chevron" size={14} />
            </label>
            {stream ? (
              <button className="session-button session-stop" onClick={stopSession}>
                <CameraOff size={16} /> Stop preview <span className="button-key">ESC</span>
              </button>
            ) : (
              <button className="session-button" onClick={startSession} disabled={isStarting}>
                {isStarting ? <RotateCcw className="spin-icon" size={16} /> : <Camera size={16} />}
                {isStarting ? 'Connecting camera…' : 'Start local preview'}
                {!isStarting && <ArrowUpRight size={15} />}
              </button>
            )}
          </div>
          <div className="local-only-note"><ShieldCheck size={14} /> Camera stays on this device until the remote stream is connected.</div>
        </div>
      </section>

      <footer className="bottom-bar">
        <div className="bottom-text">SKYCOP / LIVE CAMERA</div>
        <div className="version-label">BUILD 0.1.0 <span>·</span> <ArrowUpRight size={13} /></div>
      </footer>
    </main>
  );
}