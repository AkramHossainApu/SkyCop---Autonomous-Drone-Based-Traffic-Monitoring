const { withGradleProperties } = require('@expo/config-plugins');

// Keeps R8 minification off for release builds.
module.exports = function withReleaseBuild(config) {
  return withGradleProperties(config, (config) => {
    const minifyProperty = config.modResults.find(
      (property) => property.type === 'property' && property.key === 'android.enableMinifyInReleaseBuilds',
    );
    if (minifyProperty) minifyProperty.value = 'false';
    else config.modResults.push({ type: 'property', key: 'android.enableMinifyInReleaseBuilds', value: 'false' });
    return config;
  });
};