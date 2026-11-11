const { withAndroidManifest, withGradleProperties } = require('@expo/config-plugins');

module.exports = function withLocalWebSocket(config) {
  config = withAndroidManifest(config, (config) => {
    const application = config.modResults.manifest.application?.[0];
    if (application) application.$['android:usesCleartextTraffic'] = 'true';
    return config;
  });

  return withGradleProperties(config, (config) => {
    const minifyProperty = config.modResults.find(
      (property) => property.type === 'property' && property.key === 'android.enableMinifyInReleaseBuilds',
    );
    if (minifyProperty) minifyProperty.value = 'false';
    else config.modResults.push({ type: 'property', key: 'android.enableMinifyInReleaseBuilds', value: 'false' });
    return config;
  });
};