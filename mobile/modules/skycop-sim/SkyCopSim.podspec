Pod::Spec.new do |s|
  s.name           = 'SkyCopSim'
  s.version        = '1.0.0'
  s.summary        = 'Native Metal drone simulator and pairing crypto for SkyCop.'
  s.license        = 'MIT'
  s.author         = 'SkyCop'
  s.homepage       = 'https://github.com/AkramHossainApu/SkyCop---Autonomous-Drone-Based-Traffic-Monitoring'
  s.platforms      = { :ios => '15.1' }
  s.swift_version  = '5.9'
  s.source         = { git: '' }
  s.static_framework = true
  s.dependency 'ExpoModulesCore'
  # The live camera's frames come from react-native-webrtc's tracks (LiveFeed.swift).
  s.dependency 'JitsiWebRTC'
  s.frameworks     = 'Metal', 'MetalKit', 'GameController', 'CoreMotion', 'AVFoundation', 'CryptoKit'
  s.source_files   = 'ios/**/*.{swift,h,m}'
  # The Dhaka maps built by maps/build_maps.py from OpenStreetMap data.
  s.resource_bundles = { 'SkyCopMaps' => ['assets/maps/*.skymap'] }
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES' }
end
