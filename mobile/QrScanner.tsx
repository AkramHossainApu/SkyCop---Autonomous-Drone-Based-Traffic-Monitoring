import { CameraView, useCameraPermissions } from 'expo-camera';
import { useEffect, useRef, useState } from 'react';
import { Linking, Modal, Pressable, StyleSheet, Text, View } from 'react-native';
import { codeFromSkyCopQr } from './pairing';

type Props = {
  visible: boolean;
  onCode: (code: string) => void;
  onClose: () => void;
};

/** A four-corner scan glyph drawn with views, so no icon font is needed. */
export function ScanGlyph({ size = 22, color = '#ffffff' }: { size?: number; color?: string }) {
  const arm = size * 0.34;
  const stroke = 2;
  const corner = (position: object) => <View style={[{ position: 'absolute', width: arm, height: arm, borderColor: color }, position]} />;
  return (
    <View style={{ width: size, height: size }}>
      {corner({ top: 0, left: 0, borderTopWidth: stroke, borderLeftWidth: stroke, borderTopLeftRadius: 3 })}
      {corner({ top: 0, right: 0, borderTopWidth: stroke, borderRightWidth: stroke, borderTopRightRadius: 3 })}
      {corner({ bottom: 0, left: 0, borderBottomWidth: stroke, borderLeftWidth: stroke, borderBottomLeftRadius: 3 })}
      {corner({ bottom: 0, right: 0, borderBottomWidth: stroke, borderRightWidth: stroke, borderBottomRightRadius: 3 })}
      <View style={{ position: 'absolute', left: size * 0.2, right: size * 0.2, top: size / 2 - 1, height: stroke, backgroundColor: color }} />
    </View>
  );
}

/**
 * Full-screen camera that reads only the pairing QR code shown on the SkyCop website. Any other QR
 * code (or barcode) is ignored, with a short note so the person knows why nothing happened.
 */
export default function QrScanner({ visible, onCode, onClose }: Props) {
  const [permission, requestPermission] = useCameraPermissions();
  const [note, setNote] = useState('');
  const done = useRef(false);
  const lastForeign = useRef('');

  useEffect(() => {
    if (!visible) return;
    done.current = false;
    lastForeign.current = '';
    setNote('');
    if (permission && !permission.granted && permission.canAskAgain) void requestPermission();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible]);

  useEffect(() => {
    if (!note) return undefined;
    const timer = setTimeout(() => setNote(''), 2500);
    return () => clearTimeout(timer);
  }, [note]);

  function scanned(data: string) {
    if (done.current) return;
    const code = codeFromSkyCopQr(data);
    if (code) {
      done.current = true;
      onCode(code);
      return;
    }
    if (data !== lastForeign.current) {
      lastForeign.current = data;
      setNote('That is not a SkyCop code. Scan the QR code on the SkyCop website.');
    }
  }

  const granted = permission?.granted === true;

  return (
    <Modal visible={visible} animationType="slide" presentationStyle="fullScreen" onRequestClose={onClose} supportedOrientations={['portrait']} statusBarTranslucent navigationBarTranslucent>
      <View style={styles.root}>
        {granted && visible ? (
          <CameraView
            style={StyleSheet.absoluteFill}
            facing="back"
            barcodeScannerSettings={{ barcodeTypes: ['qr'] }}
            onBarcodeScanned={({ data }) => scanned(data)}
          />
        ) : null}

        {granted ? (
          <View style={styles.overlay} pointerEvents="none">
            <Text style={styles.title}>Scan the SkyCop code</Text>
            <Text style={styles.lead}>Point the camera at the QR code on the SkyCop website.</Text>
            <View style={styles.frame}>
              <View style={[styles.corner, styles.tl]} />
              <View style={[styles.corner, styles.tr]} />
              <View style={[styles.corner, styles.bl]} />
              <View style={[styles.corner, styles.br]} />
            </View>
            <View style={styles.noteSlot}>{note ? <Text style={styles.note}>{note}</Text> : null}</View>
          </View>
        ) : (
          <View style={styles.ask}>
            <Text style={styles.title}>Camera access needed</Text>
            <Text style={styles.lead}>
              SkyCop uses the camera only to read the pairing QR code on the SkyCop website.
            </Text>
            {permission && !permission.canAskAgain ? (
              <Pressable accessibilityRole="button" onPress={() => void Linking.openSettings()} style={({ pressed }) => [styles.primary, pressed && styles.pressed]}>
                <Text style={styles.primaryText}>Open Settings</Text>
              </Pressable>
            ) : (
              <Pressable accessibilityRole="button" onPress={() => void requestPermission()} style={({ pressed }) => [styles.primary, pressed && styles.pressed]}>
                <Text style={styles.primaryText}>Allow camera</Text>
              </Pressable>
            )}
          </View>
        )}

        <Pressable accessibilityRole="button" onPress={onClose} style={({ pressed }) => [styles.close, pressed && styles.pressed]}>
          <Text style={styles.closeText}>Cancel</Text>
        </Pressable>
      </View>
    </Modal>
  );
}

const FRAME = 250;
const ARM = 36;

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#000' },
  overlay: { ...StyleSheet.absoluteFill, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 24, backgroundColor: 'rgba(0,0,0,0.25)' },
  ask: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 32, backgroundColor: '#171719' },
  title: { color: '#fff', fontSize: 22, fontWeight: '700', textAlign: 'center' },
  lead: { color: '#d4d5d8', fontSize: 15, lineHeight: 21, textAlign: 'center', marginTop: 8, marginBottom: 28, maxWidth: 320 },
  frame: { width: FRAME, height: FRAME },
  corner: { position: 'absolute', width: ARM, height: ARM, borderColor: '#fff' },
  tl: { top: 0, left: 0, borderTopWidth: 4, borderLeftWidth: 4, borderTopLeftRadius: 14 },
  tr: { top: 0, right: 0, borderTopWidth: 4, borderRightWidth: 4, borderTopRightRadius: 14 },
  bl: { bottom: 0, left: 0, borderBottomWidth: 4, borderLeftWidth: 4, borderBottomLeftRadius: 14 },
  br: { bottom: 0, right: 0, borderBottomWidth: 4, borderRightWidth: 4, borderBottomRightRadius: 14 },
  noteSlot: { minHeight: 64, marginTop: 24, justifyContent: 'center' },
  note: { color: '#fff', backgroundColor: 'rgba(44,26,32,0.92)', fontSize: 14, lineHeight: 20, textAlign: 'center', paddingHorizontal: 14, paddingVertical: 10, borderRadius: 8, overflow: 'hidden', maxWidth: 320 },
  primary: { height: 44, paddingHorizontal: 24, borderRadius: 22, backgroundColor: '#fff', alignItems: 'center', justifyContent: 'center' },
  primaryText: { color: '#000', fontSize: 15, fontWeight: '600' },
  close: { position: 'absolute', bottom: 48, alignSelf: 'center', height: 44, paddingHorizontal: 28, borderRadius: 22, borderWidth: 1, borderColor: 'rgba(255,255,255,0.6)', backgroundColor: 'rgba(0,0,0,0.55)', justifyContent: 'center' },
  closeText: { color: '#fff', fontSize: 15, fontWeight: '600' },
  pressed: { opacity: 0.75 },
});
