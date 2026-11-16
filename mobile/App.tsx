import { StatusBar } from 'expo-status-bar';
import { useEffect, useRef, useState } from 'react';
import { Image, Platform, Pressable, SafeAreaView, ScrollView, StatusBar as NativeStatusBar, StyleSheet, Text, TextInput, View } from 'react-native';
import type { MediaStream } from './webrtc';
import RemoteVideo from './RemoteVideo';
import { RTCPeerConnection, RTCSessionDescription } from './webrtc';

type ConnectionStatus = 'disconnected' | 'connecting' | 'waiting' | 'connected' | 'error';

function waitForIceGathering(peer: RTCPeerConnection): Promise<void> {
  if (peer.iceGatheringState === 'complete') return Promise.resolve();
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      peer.onicegatheringstatechange = null;
      reject(new Error('Network candidate gathering timed out. Try again.'));
    }, 15000);
    peer.onicegatheringstatechange = () => {
      if (peer.iceGatheringState !== 'complete') return;
      clearTimeout(timer);
      peer.onicegatheringstatechange = null;
      resolve();
    };
    if (peer.iceGatheringState === 'complete') {
      clearTimeout(timer);
      peer.onicegatheringstatechange = null;
      resolve();
    }
  });
}

export default function App() {
  const [meetingCode, setMeetingCode] = useState('');
  const [serverAddress, setServerAddress] = useState(process.env.EXPO_PUBLIC_SIGNALING_URL || 'ws://10.0.2.2:8787');
  const [connectionStatus, setConnectionStatus] = useState<ConnectionStatus>('disconnected');
  const [remoteVideoStream, setRemoteVideoStream] = useState<unknown>(null);
  const [connectionError, setConnectionError] = useState('');
  const peerRef = useRef<RTCPeerConnection | null>(null);
  const socketRef = useRef<WebSocket | null>(null);
  useEffect(() => () => {
    socketRef.current?.close();
    socketRef.current = null;
    peerRef.current?.close();
    peerRef.current = null;
  }, []);

  function disconnect() {
    const socket = socketRef.current;
    socketRef.current = null;
    socket?.close();
    const peer = peerRef.current;
    peerRef.current = null;
    peer?.close();
    setRemoteVideoStream(null);
    setConnectionError('');
    setConnectionStatus('disconnected');
  }

  async function acceptOffer(socket: WebSocket, sdp: string) {
    if (socketRef.current !== socket) return;
    peerRef.current?.close();
    const peer = new RTCPeerConnection({ iceServers: [] });
    peerRef.current = peer;
    try {
      peer.ontrack = (event) => {
        const [stream] = (event as unknown as { streams: MediaStream[] }).streams;
        if (stream && peerRef.current === peer) setRemoteVideoStream(stream);
      };
      peer.onconnectionstatechange = () => {
        if (peerRef.current !== peer) return;
        if (peer.connectionState === 'connected') setConnectionStatus('connected');
        if (peer.connectionState === 'failed' || peer.connectionState === 'disconnected') {
          setConnectionStatus('error');
          setConnectionError('Connection lost. Start a new session and check your network.');
        }
      };
      await peer.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp }));
      await peer.setLocalDescription(await peer.createAnswer());
      await waitForIceGathering(peer);
      if (peerRef.current !== peer || socketRef.current !== socket || socket.readyState !== WebSocket.OPEN) return;
      if (!peer.localDescription?.sdp) throw new Error('Could not create an answer.');
      socket.send(JSON.stringify({ type: 'answer', sdp: peer.localDescription.sdp }));
    } catch {
      if (peerRef.current !== peer) return;
      peerRef.current = null;
      peer.close();
      setConnectionError('Could not negotiate a video connection. Start a new session.');
      setConnectionStatus('error');
    }
  }

  function connectToWebsite() {
    if (socketRef.current) return;
    const code = meetingCode.trim().toLowerCase();
    if (!/^[a-hjkmnp-z2-9]{3}(-[a-hjkmnp-z2-9]{3}){2}$/.test(code)) {
      setConnectionError('Enter the meeting code shown on the website.');
      setConnectionStatus('error');
      return;
    }
    setConnectionError('');
    setConnectionStatus('connecting');
    try {
      const url = new URL(serverAddress.trim());
      if (!['ws:', 'wss:'].includes(url.protocol)) throw new Error('Enter a ws:// or wss:// signaling address.');
      const socket = new WebSocket(url.toString());
      socketRef.current = socket;
      socket.onopen = () => socket.send(JSON.stringify({ type: 'join', code }));
      socket.onmessage = (event) => {
        try {
          const message = JSON.parse(String(event.data)) as { type: string; sdp?: string; message?: string };
          if (message.type === 'joined') setConnectionStatus('connecting');
          else if (message.type === 'offer' && message.sdp) void acceptOffer(socket, message.sdp);
          else if (message.type === 'error') {
            setConnectionError(message.message ?? 'Could not join the meeting.');
            setConnectionStatus('error');
          } else if (message.type === 'peer-left') {
            setConnectionError('The website stopped sharing.');
            setConnectionStatus('error');
          }
        } catch {
          setConnectionError('Invalid response from the signaling host.');
          setConnectionStatus('error');
        }
      };
      socket.onerror = () => {
        if (socketRef.current === socket) {
          setConnectionError('Cannot reach the signaling host. Check the address and server.');
          setConnectionStatus('error');
        }
      };
      socket.onclose = () => {
        if (socketRef.current !== socket) return;
        socketRef.current = null;
        peerRef.current?.close();
        peerRef.current = null;
        setRemoteVideoStream(null);
        setConnectionStatus('error');
        setConnectionError('Signaling disconnected. Connect again.');
      };
    } catch (caught) {
      setConnectionError(caught instanceof Error ? caught.message : 'Invalid signaling address.');
      setConnectionStatus('error');
    }
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
                  <Text style={styles.emptyTitle}>Connect to website camera</Text>
                  <Text style={styles.emptyCaption}>Enter the meeting code from the website.</Text>
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
            accessibilityLabel="Meeting code"
            autoCapitalize="none"
            autoCorrect={false}
            value={meetingCode}
            onChangeText={setMeetingCode}
            editable={!socketRef.current}
            placeholder="abc-def-234"
            placeholderTextColor="#89938b"
            style={styles.serverInput}
          />
          <TextInput
            accessibilityLabel="Signaling host address"
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="url"
            value={serverAddress}
            onChangeText={setServerAddress}
            editable={!socketRef.current}
            placeholder="wss://signal.example.com"
            placeholderTextColor="#89938b"
            style={styles.serverInput}
          />
          <Pressable
            accessibilityRole="button"
            onPress={socketRef.current ? disconnect : connectToWebsite}
            style={({ pressed }) => [styles.connectButton, socketRef.current && styles.disconnectButton, pressed && styles.buttonPressed]}
          >
            <Text style={[styles.connectButtonText, socketRef.current && styles.disconnectButtonText]}>
              {socketRef.current ? 'Disconnect' : 'Join camera'}
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
    paddingTop: Platform.OS === 'android' ? (NativeStatusBar.currentHeight ?? 24) + 12 : 12,
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
    height: 48,
    marginTop: 12,
    paddingHorizontal: 11,
    borderWidth: 1,
    borderColor: '#d8dfd7',
    color: '#202a27',
    fontSize: 12,
    textAlignVertical: 'center',
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