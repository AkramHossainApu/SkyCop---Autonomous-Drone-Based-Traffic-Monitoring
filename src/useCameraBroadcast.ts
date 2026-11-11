import { useEffect, useRef, useState } from 'react';

type SignalMessage = { type: string; role?: string; payload?: unknown; message?: string; iceServers?: RTCIceServer[] };
export type BroadcastStatus = 'idle' | 'connecting' | 'waiting' | 'connected' | 'error';

function createPairingCode() {
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

export function useCameraBroadcast(stream: MediaStream | null) {
  const socketRef = useRef<WebSocket | null>(null);
  const peerRef = useRef<RTCPeerConnection | null>(null);
  const pendingCandidatesRef = useRef<RTCIceCandidateInit[]>([]);
  const iceServersRef = useRef<RTCIceServer[]>([{ urls: 'stun:stun.l.google.com:19302' }]);
  const [status, setStatus] = useState<BroadcastStatus>('idle');
  const [error, setError] = useState('');
  const [pairingCode, setPairingCode] = useState('');

  function closePeer() {
    peerRef.current?.close();
    peerRef.current = null;
    pendingCandidatesRef.current = [];
  }

  useEffect(() => () => {
    socketRef.current?.close();
    closePeer();
  }, []);

  async function createPeer() {
    if (!stream) return;
    closePeer();

    const peer = new RTCPeerConnection({
      iceServers: iceServersRef.current,
    });
    peerRef.current = peer;
    stream.getTracks().forEach((track) => peer.addTrack(track, stream));
    peer.onicecandidate = (event) => {
      if (event.candidate && socketRef.current?.readyState === WebSocket.OPEN) {
        socketRef.current.send(JSON.stringify({ type: 'candidate', payload: event.candidate.toJSON() }));
      }
    };
    peer.onconnectionstatechange = () => {
      if (peer.connectionState === 'connected') {
        setStatus('connected');
        setError('');
      }
      if (peer.connectionState === 'failed' || peer.connectionState === 'disconnected') {
        setStatus('error');
        setError('The viewer connection was interrupted. Check both devices are on the same network.');
      }
    };

    const offer = await peer.createOffer();
    await peer.setLocalDescription(offer);
    if (socketRef.current?.readyState === WebSocket.OPEN) {
      socketRef.current.send(JSON.stringify({
        type: 'offer',
        payload: { type: peer.localDescription?.type, sdp: peer.localDescription?.sdp },
      }));
    }
  }

  async function handleSignal(message: SignalMessage) {
    if (message.type === 'joined') {
      iceServersRef.current = message.iceServers ?? [{ urls: 'stun:stun.l.google.com:19302' }];
      setStatus('waiting');
      return;
    }
    if (message.type === 'peer-joined' && message.role === 'viewer') {
      setStatus('connecting');
      await createPeer();
      return;
    }
    if (message.type === 'peer-left') {
      closePeer();
      setStatus(socketRef.current ? 'waiting' : 'idle');
      return;
    }
    if (message.type === 'error') {
      setError(message.message ?? 'The signaling server rejected the connection.');
      setStatus('error');
      return;
    }
    if (message.type === 'answer' && message.payload && peerRef.current) {
      await peerRef.current.setRemoteDescription(new RTCSessionDescription(message.payload as RTCSessionDescriptionInit));
      for (const candidate of pendingCandidatesRef.current) await peerRef.current.addIceCandidate(candidate);
      pendingCandidatesRef.current = [];
      return;
    }
    if (message.type === 'candidate' && message.payload) {
      const candidate = message.payload as RTCIceCandidateInit;
      if (peerRef.current?.remoteDescription) {
        await peerRef.current.addIceCandidate(candidate);
      } else {
        pendingCandidatesRef.current.push(candidate);
      }
    }
  }

  function start() {
    if (!stream) {
      setError('Start the website camera preview before connecting the Android viewer.');
      setStatus('error');
      return;
    }

    setError('');
    setStatus('connecting');
    const code = createPairingCode();
    setPairingCode(code);
    try {
      const localHost = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';
      const signalingUrl = import.meta.env.VITE_SIGNALING_URL || (localHost ? `ws://${window.location.hostname}:8787` : '');
      if (!signalingUrl) throw new Error('Public signaling is not configured. Set VITE_SIGNALING_URL to your wss:// service in GitHub Actions and redeploy. The pairing code is shown but is not active yet.');
      if (window.location.protocol === 'https:' && signalingUrl.startsWith('ws://')) {
        throw new Error('This website uses HTTPS. Set VITE_SIGNALING_URL to a secure wss:// signaling service.');
      }

      const socket = new WebSocket(signalingUrl);
      socketRef.current = socket;
      socket.onopen = () => socket.send(JSON.stringify({ type: 'join', room: code, role: 'sender' }));
      socket.onmessage = (event) => {
        try {
          void handleSignal(JSON.parse(String(event.data)) as SignalMessage).catch(() => {
            setError('Could not negotiate a video connection.');
            setStatus('error');
          });
        } catch {
          setError('Received an unreadable signaling message.');
          setStatus('error');
        }
      };
      socket.onerror = () => {
        setError('Cannot reach the signaling service. Check that the configured server is online and allows this website origin.');
        setStatus('error');
      };
      socket.onclose = () => {
        if (socketRef.current !== socket) return;
        socketRef.current = null;
        closePeer();
        setStatus('idle');
      };
    } catch (error) {
      setError(error instanceof Error ? error.message : 'Could not open the signaling connection.');
      setStatus('error');
    }
  }

  function stop() {
    const socket = socketRef.current;
    socketRef.current = null;
    socket?.close();
    closePeer();
    setStatus('idle');
    setError('');
    setPairingCode('');
  }

  return { status, error, pairingCode, start, stop };
}