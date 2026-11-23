import 'react-native-url-polyfill/auto';
import { StatusBar } from 'expo-status-bar';
import * as ScreenOrientation from 'expo-screen-orientation';
import { setVisibilityAsync } from 'expo-navigation-bar';
import { useEffect, useRef, useState } from 'react';
import { Image, Linking, Platform, Pressable, ScrollView, StatusBar as NativeStatusBar, StyleSheet, Text, TextInput, View } from 'react-native';
import SkyCopSim, { type ControllerButton } from './modules/skycop-sim';
import QrScanner, { ScanGlyph } from './QrScanner';
import RemoteVideo from './RemoteVideo';
import { CODE_PATTERN, formatTyping, normalizeCode } from './pairing';
import { useCameraLink, type LinkStatus } from './useCameraLink';

const CODE_KEY = 'pairing-code';

const color = {
  ground: '#171719',
  surface: '#131217',
  raised: '#212023',
  border: '#2a2a2e',
  control: '#3a3a3e',
  text: '#ffffff',
  muted: '#a3a5ab',
  faint: '#8a8c92',
  accent: '#4a8ad6',
  positive: '#5fc39c',
  positiveBg: '#1b2828',
  warning: '#d9a441',
  negative: '#f0788c',
  negativeBg: '#2c1a20',
};

const STATUS: Record<LinkStatus, { label: string; dot: string }> = {
  idle: { label: 'Not connected', dot: color.faint },
  searching: { label: 'Looking for the camera', dot: color.warning },
  connecting: { label: 'Connecting', dot: color.warning },
  live: { label: 'Live', dot: color.positive },
  error: { label: 'Pairing failed', dot: color.negative },
};

async function lock(orientation: ScreenOrientation.OrientationLock) {
  await ScreenOrientation.lockAsync(orientation).catch(() => undefined);
}

export default function App() {
  const link = useCameraLink();
  const [typed, setTyped] = useState(() => SkyCopSim?.loadValue(CODE_KEY) ?? '');
  const [vr, setVr] = useState(false);
  const [hint, setHint] = useState(true);
  const [notice, setNotice] = useState('');
  const [scanning, setScanning] = useState(false);
  const simulationOpen = useRef(false);
  const leaveRect = useRef<{ x: number; y: number; width: number; height: number } | null>(null);
  const hintShownAt = useRef(0);
  const live = link.status === 'live' && link.stream !== null;
  const liveRef = useRef(live);
  liveRef.current = live;
  const codeValid = CODE_PATTERN.test(typed);

  function join(code: string) {
    const normalized = normalizeCode(code);
    if (!CODE_PATTERN.test(normalized)) {
      setNotice('That code does not look right. It has nine letters and numbers, like abc-def-ghj.');
      return;
    }
    setNotice('');
    setTyped(normalized);
    SkyCopSim?.saveValue(CODE_KEY, normalized);
    link.connect(normalized);
  }

  // Scanning the website's QR code opens skycop://join?code=... and connects straight away.
  useEffect(() => {
    const open = (url: string | null | undefined) => { if (url && /code=|join=/.test(url)) join(url); };
    const subscription = Linking.addEventListener('url', (event) => open(event.url));
    void Linking.getInitialURL().then(open).catch(() => undefined);
    return () => subscription.remove();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    void lock(ScreenOrientation.OrientationLock.PORTRAIT_UP);
    // Rejoin the last camera straight away; a scanned link replaces it if one opened the app.
    const saved = SkyCopSim?.loadValue(CODE_KEY);
    if (saved && CODE_PATTERN.test(saved)) link.connect(saved);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function enterVr() {
    if (!liveRef.current) return;
    await lock(ScreenOrientation.OrientationLock.LANDSCAPE);
    setVr(true);
    setHint(true);
    void setVisibilityAsync('hidden').catch(() => undefined);
  }

  async function exitVr() {
    setVr(false);
    void setVisibilityAsync('visible').catch(() => undefined);
    await lock(ScreenOrientation.OrientationLock.PORTRAIT_UP);
  }

  async function openSimulation() {
    if (!SkyCopSim || simulationOpen.current) return;
    simulationOpen.current = true;
    setNotice('');
    try {
      // iOS only rotates a presented screen when the app itself allows landscape.
      if (Platform.OS === 'ios') await ScreenOrientation.unlockAsync().catch(() => undefined);
      const result = await SkyCopSim.openSimulation(liveRef.current);
      if (result === 'live' && liveRef.current) {
        await enterVr();
        return;
      }
      await exitVr();
      if (result === 'error') setNotice('The 3D simulation could not start its graphics on this device.');
    } catch {
      await exitVr();
      setNotice('Could not open the simulation. Try again.');
    } finally {
      simulationOpen.current = false;
    }
  }

  // Gamepad X switches between live video and the simulation; B leaves the headset view.
  const buttonRef = useRef<(button: ControllerButton) => void>(() => undefined);
  buttonRef.current = (button) => {
    if (button === 'x') void openSimulation();
    if (button === 'b' && vr) void exitVr();
  };
  useEffect(() => {
    if (!SkyCopSim) return undefined;
    const subscription = SkyCopSim.addListener('onControllerButton', ({ button }) => buttonRef.current(button));
    void SkyCopSim.watchControllerButtons().catch(() => undefined);
    return () => subscription.remove();
  }, []);

  useEffect(() => {
    if (!vr || !hint) return undefined;
    const timer = setTimeout(() => setHint(false), 3500);
    return () => clearTimeout(timer);
  }, [vr, hint]);

  useEffect(() => {
    if (vr && !live) void exitVr();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live]);

  if (vr && link.stream) {
    return (
      <View
        style={styles.vr}
        onTouchStart={() => {
          if (!hint) hintShownAt.current = Date.now();
          setHint(true);
        }}
        onTouchEnd={(event) => {
          // Hit-test the button here as well: on iOS the native video layers can keep a landscape
          // Pressable from receiving the press.
          const rect = leaveRect.current;
          const { pageX, pageY } = event.nativeEvent;
          if (hint && rect && Date.now() - hintShownAt.current > 250 &&
            pageX >= rect.x && pageX <= rect.x + rect.width && pageY >= rect.y && pageY <= rect.y + rect.height) {
            void exitVr();
          }
        }}
      >
        <StatusBar hidden />
        {/* Native video views would otherwise take the touches meant for the controls. */}
        <View style={styles.vrEye} pointerEvents="none"><RemoteVideo stream={link.stream} fit="contain" /></View>
        <View style={styles.vrDivider} pointerEvents="none" />
        <View style={styles.vrEye} pointerEvents="none"><RemoteVideo stream={link.stream} fit="contain" /></View>
        {hint && (
          <View style={styles.vrHint} pointerEvents="box-none">
            <Text style={styles.vrHintText}>Gamepad X: simulation · B: leave</Text>
            <Pressable
              accessibilityRole="button"
              onPress={() => void exitVr()}
              onLayout={(event) => {
                const target = event.target as unknown as { measureInWindow?: (callback: (x: number, y: number, w: number, h: number) => void) => void };
                target.measureInWindow?.((x, y, width, height) => { leaveRect.current = { x, y, width, height }; });
              }}
              style={styles.vrExit}
            >
              <Text style={styles.vrExitText}>Leave VR</Text>
            </Pressable>
          </View>
        )}
      </View>
    );
  }

  const status = STATUS[link.status];
  const finding = live
    ? 'The camera is streaming. Put the phone in the headset to watch.'
    : link.status === 'searching'
      ? `Waiting for the camera station with code ${link.code}. Start its camera if it is off.`
      : link.status === 'connecting'
        ? 'Found the camera. Setting up the video.'
        : 'Type the code from the SkyCop website, or tap the scan button and point it at the QR code.';

  return (
    <View style={styles.root}>
      <StatusBar style="light" />
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <View style={styles.topbar}>
          <View style={styles.brand}>
            <Image source={require('./assets/skycop-logo.png')} style={styles.logo} resizeMode="contain" />
            <Text style={styles.brandName}>SkyCop</Text>
          </View>
          <View style={styles.state}>
            <View style={[styles.dot, { backgroundColor: status.dot }]} />
            <Text style={[styles.stateText, live && styles.stateLive]}>{status.label}</Text>
          </View>
        </View>

        <Text style={styles.h1} accessibilityRole="header">Live camera</Text>
        <Text style={styles.finding}>{finding}</Text>

        <View style={styles.panel}>
          <View style={styles.viewport}>
            {link.stream ? <RemoteVideo stream={link.stream} fit="cover" /> : (
              <View style={styles.viewportEmpty}>
                <Text style={styles.viewportText}>{link.status === 'idle' ? 'No camera connected' : 'Waiting for video…'}</Text>
              </View>
            )}
            {live && <View style={styles.pill}><Text style={styles.pillText}>Live</Text></View>}
          </View>
          <View style={styles.panelFoot}>
            <Pressable
              accessibilityRole="button"
              disabled={!live}
              onPress={() => void enterVr()}
              style={({ pressed }) => [styles.button, styles.buttonPrimary, !live && styles.disabled, pressed && styles.pressed]}
            >
              <Text style={[styles.buttonText, styles.buttonPrimaryText]}>Watch in VR</Text>
            </Pressable>
          </View>
        </View>

        <View style={[styles.panel, styles.panelPad]}>
          <Text style={styles.h2} accessibilityRole="header">Connect to a camera</Text>
          <Text style={styles.label}>Pairing code</Text>
          <View style={styles.codeRow}>
            <TextInput
              accessibilityLabel="Pairing code"
              value={typed}
              onChangeText={(value) => setTyped(value.includes('/') || value.includes('=') ? normalizeCode(value) : formatTyping(value))}
              onSubmitEditing={() => join(typed)}
              editable={link.status === 'idle' || link.status === 'error'}
              autoCapitalize="none"
              autoCorrect={false}
              placeholder="abc-def-ghj"
              placeholderTextColor={color.faint}
              returnKeyType="go"
              style={styles.codeInput}
            />
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Scan the QR code"
              onPress={() => { setNotice(''); setScanning(true); }}
              style={({ pressed }) => [styles.scanButton, pressed && styles.pressed]}
            >
              <ScanGlyph />
            </Pressable>
          </View>
          {link.status === 'idle' || link.status === 'error' ? (
            <Pressable
              accessibilityRole="button"
              disabled={!codeValid}
              onPress={() => join(typed)}
              style={({ pressed }) => [styles.button, styles.buttonPrimary, styles.wide, !codeValid && styles.disabled, pressed && styles.pressed]}
            >
              <Text style={[styles.buttonText, styles.buttonPrimaryText]}>Connect</Text>
            </Pressable>
          ) : (
            <Pressable accessibilityRole="button" onPress={link.disconnect} style={({ pressed }) => [styles.button, styles.wide, pressed && styles.pressed]}>
              <Text style={styles.buttonText}>Disconnect</Text>
            </Pressable>
          )}
          {(notice || link.error) ? <Text style={styles.error}>{notice || link.error}</Text> : null}
          <Text style={styles.help}>On the camera device, open the SkyCop website and tap Start camera. Both devices need the same Wi-Fi.</Text>
        </View>

        <View style={[styles.panel, styles.panelPad, styles.simPanel]}>
          <View style={styles.simText}>
            <Text style={styles.h2} accessibilityRole="header">Flight simulation</Text>
            <Text style={styles.help}>Fly the drone through the city in VR with a gamepad.</Text>
          </View>
          <Pressable accessibilityRole="button" onPress={() => void openSimulation()} style={({ pressed }) => [styles.button, pressed && styles.pressed]}>
            <Text style={styles.buttonText}>Open</Text>
          </Pressable>
        </View>

        <Text style={styles.footer}>Video goes straight from the camera to this phone. This phone's camera is used only to scan the pairing QR code.</Text>
      </ScrollView>
      <QrScanner
        visible={scanning}
        onClose={() => setScanning(false)}
        onCode={(code) => {
          setScanning(false);
          join(code);
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: color.ground },
  content: {
    paddingHorizontal: 16,
    paddingTop: Platform.OS === 'android' ? (NativeStatusBar.currentHeight ?? 24) + 8 : 60,
    paddingBottom: 40,
    width: '100%',
    maxWidth: 640,
    alignSelf: 'center',
  },
  topbar: { height: 48, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20 },
  brand: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  logo: { width: 34, height: 21, tintColor: '#ffffff' },
  brandName: { color: color.text, fontSize: 17, fontWeight: '700' },
  state: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  dot: { width: 8, height: 8, borderRadius: 4 },
  stateText: { color: color.muted, fontSize: 13 },
  stateLive: { color: color.text },
  h1: { color: color.text, fontSize: 26, lineHeight: 32, fontWeight: '700' },
  finding: { color: color.muted, fontSize: 15, lineHeight: 21, marginTop: 6, marginBottom: 16 },
  panel: { backgroundColor: color.surface, borderRadius: 6, marginBottom: 16, overflow: 'hidden' },
  panelPad: { padding: 20 },
  viewport: { aspectRatio: 16 / 9, backgroundColor: '#0b0b0d' },
  viewportEmpty: { ...StyleSheet.absoluteFill, alignItems: 'center', justifyContent: 'center' },
  viewportText: { color: color.faint, fontSize: 14 },
  pill: { position: 'absolute', top: 12, left: 12, height: 24, paddingHorizontal: 10, borderRadius: 12, backgroundColor: color.positiveBg, justifyContent: 'center' },
  pillText: { color: color.positive, fontSize: 12, fontWeight: '600' },
  panelFoot: { padding: 16 },
  h2: { color: color.text, fontSize: 16, lineHeight: 24, fontWeight: '600' },
  label: { color: color.faint, fontSize: 12, marginTop: 16, marginBottom: 6 },
  codeRow: { flexDirection: 'row', gap: 8 },
  scanButton: { width: 56, height: 56, borderRadius: 6, backgroundColor: color.raised, alignItems: 'center', justifyContent: 'center' },
  codeInput: {
    flex: 1,
    height: 56,
    paddingHorizontal: 16,
    borderRadius: 6,
    backgroundColor: color.raised,
    color: color.text,
    fontSize: 26,
    fontWeight: '700',
    letterSpacing: 1,
    fontVariant: ['tabular-nums'],
  },
  button: {
    height: 44,
    paddingHorizontal: 20,
    borderRadius: 22,
    borderWidth: 1,
    borderColor: color.control,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonPrimary: { backgroundColor: '#ffffff', borderColor: '#ffffff' },
  buttonText: { color: color.text, fontSize: 15, fontWeight: '600' },
  buttonPrimaryText: { color: '#000000' },
  wide: { marginTop: 12 },
  disabled: { opacity: 0.4 },
  pressed: { opacity: 0.75 },
  error: { color: color.negative, backgroundColor: color.negativeBg, borderRadius: 6, padding: 12, marginTop: 12, fontSize: 13, lineHeight: 19 },
  help: { color: color.muted, fontSize: 13, lineHeight: 19, marginTop: 12 },
  simPanel: { flexDirection: 'row', alignItems: 'center', gap: 16 },
  simText: { flex: 1 },
  footer: { color: color.faint, fontSize: 12, lineHeight: 17, marginTop: 8 },
  vr: { flex: 1, flexDirection: 'row', backgroundColor: '#000' },
  vrEye: { flex: 1, overflow: 'hidden' },
  vrDivider: { width: 2, backgroundColor: '#000' },
  vrHint: { position: 'absolute', top: 16, left: 0, right: 0, alignItems: 'center', gap: 10, zIndex: 10, elevation: 10 },
  vrHintText: { color: '#fff', fontSize: 13, backgroundColor: 'rgba(0,0,0,0.6)', paddingHorizontal: 12, paddingVertical: 6, borderRadius: 14, overflow: 'hidden' },
  vrExit: { height: 36, paddingHorizontal: 16, borderRadius: 18, borderWidth: 1, borderColor: 'rgba(255,255,255,0.5)', backgroundColor: 'rgba(0,0,0,0.6)', justifyContent: 'center' },
  vrExitText: { color: '#fff', fontSize: 13, fontWeight: '600' },
});
