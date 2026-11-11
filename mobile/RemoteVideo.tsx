import { RTCView, type MediaStream } from 'react-native-webrtc';
import { StyleSheet } from 'react-native';

type RemoteVideoProps = { stream: unknown };

export default function RemoteVideo({ stream }: RemoteVideoProps) {
  return <RTCView streamURL={(stream as MediaStream).toURL()} style={StyleSheet.absoluteFill} objectFit="cover" />;
}