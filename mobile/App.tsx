import { StatusBar } from 'expo-status-bar';
import { useEffect, useRef, useState } from 'react';
import { Image, Pressable, SafeAreaView, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import type { MediaStream } from './webrtc';
import RemoteVideo from './RemoteVideo';
import { RTCIceCandidate, RTCPeerConnection, RTCSessionDescription } from './webrtc';

type SignalMessage = {
  type: string;
  role?: string;
  payload?: any;
  message?: string;
  iceServers?: IceServer[];
};

type ConnectionStatus = 'disconnected' | 'connecting' | 'waiting' | 'connected' | 'error';
type IceServer = { urls: string | string[]; username?: string; credential?: string };

export default function App() {
  const [serverAddress, setServerAddress] = useState(process.env.EXPO_PUBLIC_SIGNALING_URL ?? 'ws://10.0.2.2:8787');
  const [pairingCode, setPairingCode] = useState('');
  const [connectionStatus, setConnectionStatus] = useState<ConnectionStatus>('disconnected');
  const [remoteVideoStream, setRemoteVideoStream] = useState<unknown>(null);
  const [connectionError, setConnectionError] = useState('');
  const [isSocketOpen, setIsSocketOpen] = useState(false);
  const socketRef = useRef<WebSocket | null>(null);
  const peerRef = useRef<RTCPeerConnection | null>(null);
  const pendingCandidatesRef = useRef<any[]>([]);
  const iceServersRef = useRef<IceServer[]>([{ urls: 'stun:stun.l.google.com:19302' }]);

  useEffect(() => () => {
    const socket = socketRef.current;
    socketRef.current = null;
    socket?.close();
    peerRef.current?.close();
    peerRef.current = null;
  }, []);

  function sendSignal(type: string, payload?: unknown) {
    const socket = socketRef.current;
    if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type, payload }));
  }

  function closePeer() {
    peerRef.current?.close();
    peerRef.current = null;
    pendingCandidatesRef.current = [];
    setRemoteVideoStream(null);
  }

  async function handleSignal(message: SignalMessage) {
    if (message.type === 'joined') {
      if (message.iceServers?.length) iceServersRef.current = message.iceServers;
      setConnectionStatus('waiting');
      return;
    }
    if (message.type === 'peer-joined' && message.role === 'sender') {
      setConnectionStatus('waiting');
      return;
    }
    if (message.type === 'peer-left') {
      closePeer();
      setConnectionStatus(socketRef.current ? 'waiting' : 'disconnected');
      return;
    }
    if (message.type === 'error') {
      setConnectionError(message.message ?? 'The signaling server rejected the connection.');
      setConnectionStatus('error');
      return;
    }
    if (message.type === 'offer' && message.payload) {
      closePeer();
      const peer = new RTCPeerConnection({
        iceServers: iceServersRef.current,
      });
      peerRef.current = peer;
      peer.ontrack = (event) => {
        const [stream] = (event as unknown as { streams: MediaStream[] }).streams;
        if (stream) {
          setRemoteVideoStream(stream);
          setConnectionStatus('connected');
        }
      };
      peer.onicecandidate = (event) => {
        const candidate = (event as unknown as { candidate: RTCIceCandidate | null }).candidate;
        if (candidate) sendSignal('candidate', candidate.toJSON());
      };
      peer.onconnectionstatechange = () => {
        if (peer.connectionState === 'connected') setConnectionStatus('connected');
        if (peer.connectionState === 'failed' || peer.connectionState === 'disconnected') {
          setConnectionStatus('error');
          setConnectionError('The video connection was interrupted. Check the signaling and TURN server configuration.');
        }
      };

      await peer.setRemoteDescription(new RTCSessionDescription(message.payload));
      for (const candidate of pendingCandidatesRef.current) {
        await peer.addIceCandidate(new RTCIceCandidate(candidate));
      }
      pendingCandidatesRef.current = [];
      const answer = await peer.createAnswer();
      await peer.setLocalDescription(answer);
      sendSignal('answer', { type: peer.localDescription?.type, sdp: peer.localDescription?.sdp });
      return;
    }
    if (message.type === 'candidate' && message.payload) {
      const peer = peerRef.current;
      if (peer?.remoteDescription) {
        await peer.addIceCandidate(new RTCIceCandidate(message.payload));
      } else {
        pendingCandidatesRef.current.push(message.payload);
      }
    }
  }

  function connectToWebsite() {
    setConnectionError('');
    const room = pairingCode.trim().toLowerCase();
    if (!/^[a-f0-9]{32}$/.test(room)) {
      setConnectionError('Enter the 32-character pairing code shown by the website camera station.');
      setConnectionStatus('error');
      return;
    }
    setConnectionStatus('connecting');
    try {
      const socket = new WebSocket(serverAddress.trim());
      socketRef.current = socket;
      socket.onopen = () => {
        setIsSocketOpen(true);
        socket.send(JSON.stringify({ type: 'join', room, role: 'viewer' }));
      };
      socket.onmessage = (event) => {
        try {
          void handleSignal(JSON.parse(String(event.data)) as SignalMessage);
        } catch {
          setConnectionError('Received an unreadable signaling message.');
          setConnectionStatus('error');
        }
      };
      socket.onerror = () => {
        setConnectionError('Cannot reach the signaling server. Check its address and that it is running.');
        setConnectionStatus('error');
      };
      socket.onclose = () => {
        if (socketRef.current !== socket) return;
        socketRef.current = null;
        setIsSocketOpen(false);
        closePeer();
        setConnectionStatus('disconnected');
      };
    } catch {
      setConnectionError('Enter a valid ws:// development or wss:// hosted signaling address.');
      setConnectionStatus('error');
    }
  }

  function disconnect() {
    const socket = socketRef.current;
    socketRef.current = null;
    setIsSocketOpen(false);
    closePeer();
    socket?.close();
    setConnectionError('');
    setConnectionStatus('disconnected');
  }

  const isConnected = connectionStatus === 'connected';
  const statusLabel = isConnected ? 'LIVE' : connectionStatus === 'connecting' ? 'CONNECTING' : connectionStatus === 'error' ? 'CONNECTION ERROR' : connectionStatus === 'waiting' ? 'WAITING FOR SENDER' : 'SYSTEM READY';
  const feedLabel = isConnected ? 'LIVE' : connectionStatus === 'waiting' ? 'WAITING' : 'NO SIGNAL';

  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar style="dark" />
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.topbar}>
          <View style={styles.brand}>
            <Image source={require('./assets/skycop-logo.png')} style={styles.brandLogo} resizeMode="contain" />
            <Text style={styles.brandName}>SKYCOP<Text style={styles.brandPeriod}>.</Text></Text>
          </View>
          <View style={styles.systemStatus}>
            <View style={[styles.statusDot, isConnected && styles.statusDotLive]} />
            <Text style={styles.systemStatusText}>{statusLabel}</Text>
          </View>
        </View>

        <View style={styles.intro}>
          <View style={styles.eyebrow}>
            <View style={styles.eyebrowMarker} />
            <Text style={styles.eyebrowText}>DRONE TRAFFIC CONTROL</Text>
          </View>
          <Text style={styles.title}>Autonomous Drone-Based Traffic Monitoring<Text style={styles.titlePeriod}>.</Text></Text>
          <Text style={styles.subtitle}>VR Police Control</Text>
        </View>

        <View style={styles.workspace}>
          <View style={styles.sectionHeading}>
            <View>
              <Text style={styles.sectionKicker}>01 / CAMERA INPUT</Text>
              <Text style={styles.sectionTitle}>Live camera</Text>
            </View>
            <View style={styles.connectionStatus}>
              <View style={styles.signalDot} />
              <Text style={styles.connectionText}>{feedLabel}</Text>
            </View>
          </View>

          <View style={styles.previewFrame}>
            {remoteVideoStream ? <RemoteVideo stream={remoteVideoStream} /> : <View style={styles.previewGrid} />}
            <View style={styles.previewTopline}>
              <Text style={styles.feedLabel}>REMOTE CAMERA FEED</Text>
              <Text style={styles.feedLabel}>{feedLabel}</Text>
            </View>
            <View style={styles.previewCenter}>
              {!remoteVideoStream && (
                <>
                  <View style={styles.crosshairOuter}>
                    <View style={styles.crosshairHorizontal} />
                    <View style={styles.crosshairVertical} />
                  </View>
                  <Text style={styles.emptyTitle}>{connectionStatus === 'waiting' ? 'Waiting for website camera' : 'Connect to website camera'}</Text>
                  <Text style={styles.emptyCaption}>{connectionStatus === 'waiting' ? 'Start broadcasting from the website camera station.' : 'Join the local viewing room to receive the live feed.'}</Text>
                </>
              )}
            </View>
            <View style={styles.frameCornerTopLeft} />
            <View style={styles.frameCornerTopRight} />
            <View style={styles.frameCornerBottomLeft} />
            <View style={styles.frameCornerBottomRight} />
            <View style={styles.previewBottomline}>
              <Text style={styles.feedLabel}>SKYCOP WEB STATION</Text>
              <Text style={styles.feedLabel}>{isConnected ? 'LIVE' : connectionStatus === 'waiting' ? 'WAITING' : 'OFFLINE'}</Text>
            </View>
          </View>

          <TextInput
            accessibilityLabel="Website pairing code"
            autoCapitalize="none"
            autoCorrect={false}
            value={pairingCode}
            onChangeText={setPairingCode}
            editable={!isSocketOpen}
            placeholder="32-character code from website"
            placeholderTextColor="#89938b"
            style={styles.serverInput}
          />
          <TextInput
            accessibilityLabel="Signaling server address"
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="url"
            value={serverAddress}
            onChangeText={setServerAddress}
            editable={!isSocketOpen}
            placeholder="wss://signal.example.com"
            placeholderTextColor="#89938b"
            style={styles.serverInput}
          />
          <Pressable
            accessibilityRole="button"
            onPress={isSocketOpen ? disconnect : connectToWebsite}
            style={({ pressed }) => [styles.connectButton, isSocketOpen && styles.disconnectButton, pressed && styles.buttonPressed]}
          >
            <Text style={[styles.connectButtonText, isSocketOpen && styles.disconnectButtonText]}>
              {isSocketOpen ? 'Disconnect from website' : 'Connect to website camera'}
            </Text>
          </Pressable>
          {connectionError ? <Text style={styles.connectionError}>{connectionError}</Text> : null}

          <View style={styles.infoNote}>
            <View style={styles.infoMark}><Text style={styles.infoMarkText}>i</Text></View>
            <Text style={styles.infoText}>This viewer receives the website feed over WebRTC. It never opens this phone's camera.</Text>
          </View>
        </View>

        <View style={styles.footer}>
          <Text style={styles.footerLabel}>SKYCOP / LIVE CAMERA</Text>
          <Text style={styles.footerVersion}>BUILD 0.1.0</Text>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#f2f4ef',
  },
  content: {
    flexGrow: 1,
    width: '100%',
    maxWidth: 760,
    alignSelf: 'center',
    paddingHorizontal: 24,
    paddingTop: 12,
    paddingBottom: 20,
  },
  topbar: {
    minHeight: 55,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#dce2da',
  },
  brand: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 9,
  },
  brandLogo: {
    width: 44,
    height: 27,
  },
  brandName: {
    color: '#202a27',
    fontSize: 14,
    fontWeight: '700',
    letterSpacing: 0.8,
  },
  brandPeriod: {
    color: '#e87654',
  },
  systemStatus: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  statusDot: {
    width: 7,
    height: 7,
    borderRadius: 4,
    backgroundColor: '#6f9c73',
  },
  statusDotLive: {
    backgroundColor: '#6f9c73',
  },
  systemStatusText: {
    color: '#69756e',
    fontSize: 9,
    fontWeight: '500',
    letterSpacing: 0.6,
  },
  intro: {
    paddingTop: 30,
    paddingBottom: 24,
  },
  eyebrow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  eyebrowMarker: {
    width: 7,
    height: 7,
    backgroundColor: '#e87654',
    transform: [{ rotate: '45deg' }],
  },
  eyebrowText: {
    color: '#727d75',
    fontSize: 9,
    fontWeight: '500',
    letterSpacing: 0.6,
  },
  title: {
    marginTop: 15,
    color: '#202a27',
    fontSize: 34,
    fontWeight: '500',
    lineHeight: 39,
  },
  titlePeriod: {
    color: '#e87654',
  },
  subtitle: {
    marginTop: 8,
    color: '#727b74',
    fontSize: 15,
    fontWeight: '500',
  },
  workspace: {
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#dce2da',
    paddingTop: 19,
  },
  sectionHeading: {
    minHeight: 51,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
  },
  sectionKicker: {
    color: '#89938b',
    fontSize: 9,
    fontWeight: '500',
    letterSpacing: 0.5,
  },
  sectionTitle: {
    marginTop: 5,
    color: '#202a27',
    fontSize: 18,
    fontWeight: '500',
  },
  connectionStatus: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
    paddingTop: 6,
  },
  signalDot: {
    width: 6,
    height: 6,
    borderRadius: 3,
    backgroundColor: '#adb6ae',
  },
  connectionText: {
    color: '#838d85',
    fontSize: 9,
    fontWeight: '500',
    letterSpacing: 0.4,
  },
  previewFrame: {
    position: 'relative',
    width: '100%',
    aspectRatio: 16 / 10,
    overflow: 'hidden',
    borderWidth: 1,
    borderColor: '#d8dfd7',
    backgroundColor: '#e3e8e1',
  },
  serverInput: {
    minHeight: 42,
    marginTop: 12,
    paddingHorizontal: 11,
    borderWidth: 1,
    borderColor: '#d8dfd7',
    color: '#202a27',
    fontSize: 12,
  },
  connectButton: {
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 9,
    paddingHorizontal: 14,
    backgroundColor: '#202a27',
  },
  connectButtonText: {
    color: '#f5f5ef',
    fontSize: 12,
    fontWeight: '600',
  },
  disconnectButton: {
    borderWidth: 1,
    borderColor: '#d8dfd7',
    backgroundColor: 'transparent',
  },
  disconnectButtonText: {
    color: '#39453f',
  },
  connectionError: {
    marginTop: 8,
    color: '#9b4e39',
    fontSize: 11,
    lineHeight: 16,
  },
  buttonPressed: {
    opacity: 0.78,
  },
  previewGrid: {
    ...StyleSheet.absoluteFill,
    opacity: 0.4,
    borderColor: '#c4cec5',
    borderTopWidth: StyleSheet.hairlineWidth,
    borderLeftWidth: StyleSheet.hairlineWidth,
  },
  previewTopline: {
    position: 'absolute',
    top: 14,
    left: 16,
    right: 16,
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  feedLabel: {
    color: '#68766d',
    fontSize: 8,
    fontWeight: '500',
    letterSpacing: 0.4,
  },
  previewCenter: {
    ...StyleSheet.absoluteFill,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 18,
  },
  crosshairOuter: {
    width: 49,
    height: 49,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 14,
    borderWidth: 1,
    borderColor: '#c3cdc4',
    borderRadius: 25,
  },
  crosshairHorizontal: {
    position: 'absolute',
    width: 18,
    height: 1,
    backgroundColor: '#748078',
  },
  crosshairVertical: {
    position: 'absolute',
    width: 1,
    height: 18,
    backgroundColor: '#748078',
  },
  emptyTitle: {
    color: '#3e4a43',
    fontSize: 13,
    fontWeight: '500',
    textAlign: 'center',
  },
  emptyCaption: {
    marginTop: 6,
    color: '#838e85',
    fontSize: 11,
    textAlign: 'center',
    lineHeight: 16,
  },
  frameCornerTopLeft: {
    position: 'absolute',
    top: 11,
    left: 11,
    width: 12,
    height: 12,
    borderTopWidth: 1,
    borderLeftWidth: 1,
    borderColor: '#9aa99b',
  },
  frameCornerTopRight: {
    position: 'absolute',
    top: 11,
    right: 11,
    width: 12,
    height: 12,
    borderTopWidth: 1,
    borderRightWidth: 1,
    borderColor: '#9aa99b',
  },
  frameCornerBottomLeft: {
    position: 'absolute',
    bottom: 11,
    left: 11,
    width: 12,
    height: 12,
    borderBottomWidth: 1,
    borderLeftWidth: 1,
    borderColor: '#9aa99b',
  },
  frameCornerBottomRight: {
    position: 'absolute',
    right: 11,
    bottom: 11,
    width: 12,
    height: 12,
    borderRightWidth: 1,
    borderBottomWidth: 1,
    borderColor: '#9aa99b',
  },
  previewBottomline: {
    position: 'absolute',
    right: 16,
    bottom: 14,
    left: 16,
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  infoNote: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: 8,
    marginTop: 13,
  },
  infoMark: {
    width: 15,
    height: 15,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: '#9baa9d',
    borderRadius: 8,
  },
  infoMarkText: {
    color: '#719174',
    fontSize: 10,
    fontWeight: '600',
  },
  infoText: {
    flex: 1,
    color: '#78837b',
    fontSize: 10,
    lineHeight: 15,
  },
  footer: {
    minHeight: 51,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: 'auto',
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#dce2da',
  },
  footerLabel: {
    color: '#79837b',
    fontSize: 8,
    fontWeight: '500',
    letterSpacing: 0.5,
  },
  footerVersion: {
    color: '#879189',
    fontSize: 8,
    letterSpacing: 0.5,
  },
});