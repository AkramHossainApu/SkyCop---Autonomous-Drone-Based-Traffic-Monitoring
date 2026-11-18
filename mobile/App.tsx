import 'react-native-url-polyfill/auto';
import { StatusBar } from 'expo-status-bar';
import * as ScreenOrientation from 'expo-screen-orientation';
import { setVisibilityAsync } from 'expo-navigation-bar';
import { useGamepad } from 'react-native-earl-gamepad';
import { useEffect, useRef, useState } from 'react';
import { Image, Linking, Platform, Pressable, SafeAreaView, ScrollView, StatusBar as NativeStatusBar, StyleSheet, Text, TextInput, View } from 'react-native';
import type { MediaStream } from './webrtc';
import FlightSimulation, { type FlightControls } from './FlightSimulation';
import RemoteVideo from './RemoteVideo';
import { RTCPeerConnection, RTCSessionDescription } from './webrtc';

type ConnectionStatus = 'disconnected' | 'connecting' | 'waiting' | 'connected' | 'error';

function parseInvitation(value: string): { server: string; code: string } | null {
  try {
    const invitation = new URL(value.trim());
    if (invitation.protocol !== 'skycop:' || invitation.hostname !== 'join') return null;
    const server = invitation.searchParams.get('server');
    const code = invitation.searchParams.get('code')?.toLowerCase();
    if (!server || !code || !/^[a-hjkmnp-z2-9]{3}(-[a-hjkmnp-z2-9]{3}){2}$/.test(code)) return null;
    const endpoint = new URL(server);
    if (!['ws:', 'wss:'].includes(endpoint.protocol) || endpoint.username || endpoint.password) return null;
    return { server: endpoint.toString(), code };
  } catch {
    return null;
  }
}

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
  const [isVrMode, setIsVrMode] = useState(false);
  const [screen, setScreen] = useState<'receiver' | 'simulation'>('receiver');
  const [vrHudVisible, setVrHudVisible] = useState(true);
  const [vrHudPulse, setVrHudPulse] = useState(0);
  const [simulationStartHidden, setSimulationStartHidden] = useState(false);
  const peerRef = useRef<RTCPeerConnection | null>(null);
  const socketRef = useRef<WebSocket | null>(null);
  const previousXRef = useRef(false);
  const gamepad = useGamepad({ axisThreshold: 0.12 });
  const flightControls: FlightControls = {
    yaw: gamepad.axes.leftX ?? 0,
    throttle: gamepad.axes.leftY ?? 0,
    lookX: gamepad.axes.rightX ?? 0,
    lookY: gamepad.axes.rightY ?? 0,
    climb: (gamepad.buttonValues.rt ?? 0) - (gamepad.buttonValues.lt ?? 0),
  };
  useEffect(() => {
    void ScreenOrientation.lockAsync(ScreenOrientation.OrientationLock.PORTRAIT_UP).catch(() => undefined);
    return () => {
      void ScreenOrientation.lockAsync(ScreenOrientation.OrientationLock.PORTRAIT_UP).catch(() => undefined);
      void setVisibilityAsync('visible').catch(() => undefined);
    };
  }, []);
  useEffect(() => {
    const pressed = gamepad.isPressed('x');
    if (pressed && !previousXRef.current) {
      if (screen === 'simulation') {
        if (connectionStatus === 'connected' && remoteVideoStream) {
          setScreen('receiver');
          setIsVrMode(true);
          setVrHudVisible(false);
          void setVisibilityAsync('hidden').catch(() => undefined);
        }
      } else if (remoteVideoStream && connectionStatus === 'connected') {
        setSimulationStartHidden(true);
        void enterSimulation(true);
      }
    }
    previousXRef.current = pressed;
  }, [gamepad.pressedButtons, screen, connectionStatus, remoteVideoStream]);
  useEffect(() => {
    if (!isVrMode || !vrHudVisible) return undefined;
    const timeout = setTimeout(() => setVrHudVisible(false), 3500);
    return () => clearTimeout(timeout);
  }, [isVrMode, vrHudVisible, vrHudPulse]);
  useEffect(() => {
    let active = true;
    function receiveInvitation(url: string) {
      if (!active) return;
      if (socketRef.current) {
        setConnectionError('Disconnect from the current camera before opening another invitation.');
        return;
      }
      const invitation = parseInvitation(url);
      if (!invitation) {
        setConnectionError('Invalid SkyCop invitation. Ask the website to copy a new one.');
        return;
      }
      setMeetingCode(invitation.code);
      setServerAddress(invitation.server);
      setConnectionError('');
      setConnectionStatus('disconnected');
    }
    const subscription = Linking.addEventListener('url', (event) => receiveInvitation(event.url));
    void Linking.getInitialURL().then((url) => { if (url) receiveInvitation(url); }).catch(() => {
      if (active) setConnectionError('Could not open the SkyCop invitation.');
    });
    return () => { active = false; subscription.remove(); };
  }, []);
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

  async function enterVrMode(quiet = false) {
    if (!remoteVideoStream) return;
    try {
      await ScreenOrientation.lockAsync(ScreenOrientation.OrientationLock.LANDSCAPE);
      setIsVrMode(true);
      setVrHudVisible(!quiet);
      void setVisibilityAsync('hidden').catch(() => undefined);
    } catch {
      setConnectionError('Could not rotate the viewer. Check that the device allows screen rotation.');
    }
  }

  async function exitVrMode() {
    setIsVrMode(false);
    void setVisibilityAsync('visible').catch(() => undefined);
    try {
      await ScreenOrientation.lockAsync(ScreenOrientation.OrientationLock.PORTRAIT_UP);
    } catch {
      setConnectionError('Could not restore portrait orientation. Rotate the device manually.');
    }
  }

  async function enterSimulation(quiet = false) {
    setIsVrMode(false);
    setSimulationStartHidden(quiet);
    setScreen('simulation');
    void setVisibilityAsync('hidden').catch(() => undefined);
    try {
      await ScreenOrientation.lockAsync(ScreenOrientation.OrientationLock.LANDSCAPE);
    } catch {
      setScreen('receiver');
      void setVisibilityAsync('visible').catch(() => undefined);
      setConnectionError('Could not rotate to landscape. Check that the device allows screen rotation.');
    }
  }

  async function exitSimulation() {
    setScreen('receiver');
    await setVisibilityAsync('visible').catch(() => undefined);
    await ScreenOrientation.lockAsync(ScreenOrientation.OrientationLock.PORTRAIT_UP).catch(() => undefined);
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
      let opened = false;
      let serverError = '';
      socket.onopen = () => {
        opened = true;
        socket.send(JSON.stringify({ type: 'join', code }));
      };
      socket.onmessage = (event) => {
        try {
          const message = JSON.parse(String(event.data)) as { type: string; sdp?: string; message?: string };
          if (message.type === 'joined') setConnectionStatus('connecting');
          else if (message.type === 'offer' && message.sdp) void acceptOffer(socket, message.sdp);
          else if (message.type === 'error') {
            serverError = message.message ?? 'Could not join the meeting.';
            setConnectionError(serverError);
            setConnectionStatus('error');
          } else if (message.type === 'peer-left') {
            serverError = 'The website stopped sharing.';
            setConnectionError(serverError);
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
      socket.onclose = (event) => {
        if (socketRef.current !== socket) return;
        socketRef.current = null;
        peerRef.current?.close();
        peerRef.current = null;
        setRemoteVideoStream(null);
        setConnectionStatus('error');
        setConnectionError(serverError || (event.code === 1008
          ? 'The host rejected the connection. Wait a minute or ask the host to check its settings.'
          : opened
            ? 'The host or tunnel stopped. Ask for a new invitation.'
            : 'Cannot reach the host. Ask for a new invitation and check the connection.'));
      };
    } catch (caught) {
      setConnectionError(caught instanceof Error ? caught.message : 'Invalid signaling address.');
      setConnectionStatus('error');
    }
  }

  const isConnected = connectionStatus === 'connected';
  const statusLabel = isConnected ? 'LIVE' : connectionStatus === 'connecting' ? 'CONNECTING' : connectionStatus === 'error' ? 'CONNECTION ERROR' : connectionStatus === 'waiting' ? 'WAITING FOR SENDER' : 'SYSTEM READY';
  const feedLabel = isConnected ? 'LIVE' : connectionStatus === 'waiting' ? 'WAITING' : 'NO SIGNAL';

  if (screen === 'simulation') return (
    <View style={styles.appRoot}>
      <View pointerEvents="none" style={styles.gamepadLayer}>{gamepad.bridge}</View>
      <FlightSimulation onExit={() => void exitSimulation()} controls={flightControls} startHidden={simulationStartHidden} />
    </View>
  );

  if (isVrMode && remoteVideoStream) {
    return (
      <View style={styles.appRoot}>
        <View pointerEvents="none" style={styles.gamepadLayer}>{gamepad.bridge}</View>
        <View style={styles.vrRoot} onTouchStart={() => { setVrHudVisible(true); setVrHudPulse((pulse) => pulse + 1); }}>
          <StatusBar hidden />
          <View style={styles.vrEyes}>
            <View style={styles.vrEye}><RemoteVideo stream={remoteVideoStream} /></View>
            <View style={styles.vrEye}><RemoteVideo stream={remoteVideoStream} /></View>
            <View pointerEvents="none" style={styles.vrDivider} />
          </View>
          {vrHudVisible && <Pressable accessibilityRole="button" accessibilityLabel="Exit VR full-screen view" onPress={() => void exitVrMode()} style={styles.vrExitButton}>
            <Text style={styles.vrExitText}>EXIT VR</Text>
          </Pressable>}
        </View>
      </View>
    );
  }

  return (
    <View style={styles.appRoot}>
      <View pointerEvents="none" style={styles.gamepadLayer}>{gamepad.bridge}</View>
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
          <Pressable accessibilityRole="button" onPress={() => void enterSimulation()} style={({ pressed }) => [styles.simulationButton, pressed && styles.buttonPressed]}>
            <Text style={styles.simulationButtonText}>OPEN 3D VR SIMULATION</Text>
          </Pressable>
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
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Enter VR full-screen view"
              disabled={!remoteVideoStream}
              onPress={() => void enterVrMode()}
              style={({ pressed }) => [styles.vrButton, !remoteVideoStream && styles.vrButtonDisabled, pressed && styles.buttonPressed]}
            >
              <Text style={styles.vrButtonIcon}>□</Text>
              <Text style={styles.vrButtonText}>VR FULL SCREEN</Text>
            </Pressable>
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
            accessibilityLabel="Meeting code or invitation"
            autoCapitalize="none"
            autoCorrect={false}
            value={meetingCode}
            onChangeText={(value) => {
              if (!value.trim().startsWith('skycop://')) {
                setMeetingCode(value);
                return;
              }
              const invitation = parseInvitation(value);
              if (!invitation) {
                setConnectionError('Invalid SkyCop invitation. Ask the website to copy a new one.');
                return;
              }
              setMeetingCode(invitation.code);
              setServerAddress(invitation.server);
              setConnectionError('');
            }}
            editable={!socketRef.current}
            placeholder="Meeting code or SkyCop invitation"
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
    </View>
  );
}

const styles = StyleSheet.create({
  appRoot: {
    flex: 1,
    width: '100%',
    height: '100%',
    position: 'relative',
  },
  gamepadLayer: {
    position: 'absolute',
    top: 0,
    left: 0,
    width: 1,
    height: 1,
    opacity: 0,
    zIndex: -1,
  },
  safeArea: {
    flex: 1,
    width: '100%',
    height: '100%',
    backgroundColor: '#f2f4ef',
  },
  vrRoot: {
    flex: 1,
    backgroundColor: '#000000',
  },
  vrEyes: {
    flex: 1,
    flexDirection: 'row',
    backgroundColor: '#000000',
  },
  vrEye: {
    flex: 1,
    position: 'relative',
    overflow: 'hidden',
    backgroundColor: '#000000',
  },
  vrDivider: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    left: '50%',
    width: 2,
    marginLeft: -1,
    backgroundColor: '#000000',
  },
  vrExitButton: {
    position: 'absolute',
    top: 12,
    right: 12,
    minWidth: 68,
    minHeight: 34,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 10,
    borderWidth: 1,
    borderColor: '#ffffff50',
    backgroundColor: '#111111a8',
  },
  vrExitText: {
    color: '#ffffff',
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 0.6,
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
  simulationButton: {
    alignSelf: 'flex-start',
    minHeight: 40,
    justifyContent: 'center',
    marginTop: 16,
    paddingHorizontal: 13,
    borderWidth: 1,
    borderColor: '#202a27',
    backgroundColor: '#202a27',
  },
  simulationButtonText: {
    color: '#f5f5ef',
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 0.5,
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
  vrButton: {
    position: 'absolute',
    zIndex: 5,
    top: 38,
    right: 12,
    minHeight: 31,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 9,
    borderWidth: 1,
    borderColor: '#ffffff88',
    backgroundColor: '#202a27c9',
  },
  vrButtonDisabled: {
    opacity: 0.48,
  },
  vrButtonIcon: {
    color: '#f5f5ef',
    fontSize: 16,
    lineHeight: 18,
  },
  vrButtonText: {
    color: '#f5f5ef',
    fontSize: 8,
    fontWeight: '700',
    letterSpacing: 0.55,
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