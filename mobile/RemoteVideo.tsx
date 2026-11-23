import { RTCView, type MediaStream } from 'react-native-webrtc';
import { StyleSheet } from 'react-native';

export default function RemoteVideo({ stream, fit = 'cover' }: { stream: MediaStream; fit?: 'cover' | 'contain' }) {
  return <RTCView streamURL={stream.toURL()} style={StyleSheet.absoluteFill} objectFit={fit} />;
}
