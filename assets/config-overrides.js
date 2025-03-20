// config-overrides.js

module.exports = function override(config, env) {
  if (env === 'development') {
    console.log("config-overrides.js: Disabling build minimizer in development");
    config.mode = "development";
    config.optimization.minimize = false;
    config.optimization.minimizer = [];

    // Inline source maps in dev
    console.log("config-overrides.js: Using inline-source-map in development");
    config.devtool = "inline-source-map";

    // Suppress known source map warnings
    config.ignoreWarnings = [/Failed to parse source map/];
  }
  else {
    // Production build optimizations
    console.log("config-overrides.js: Enhancing production optimizations");

    // Enable code splitting
    config.optimization.splitChunks = {
      chunks: 'all',
      maxInitialRequests: Infinity,
      minSize: 20000,
      cacheGroups: {
        vendor: {
          test: /[\\/]node_modules[\\/]/,
          name(module) {
            // Get the name of the npm package
            const packageName = module.context.match(/[\\/]node_modules[\\/](.*?)([\\/]|$)/)[1];

            // Create separate chunks for large libraries
            if (packageName.includes('cytoscape') ||
              packageName.includes('react') ||
              packageName.includes('react-dom')) {
              return `npm.${packageName.replace('@', '')}`;
            }

            // Group smaller packages
            return 'npm.vendors';
          },
        },
      },
    };

    // Ensure tree shaking works properly
    config.optimization.usedExports = true;

    // Safely configure TerserPlugin to drop console logs if it exists
    if (config.optimization.minimizer &&
      Array.isArray(config.optimization.minimizer) &&
      config.optimization.minimizer.length > 0) {

      const terserPlugin = config.optimization.minimizer.find(
        plugin => plugin.constructor && plugin.constructor.name === 'TerserPlugin'
      );

      if (terserPlugin && terserPlugin.options && terserPlugin.options.terserOptions) {
        if (!terserPlugin.options.terserOptions.compress) {
          terserPlugin.options.terserOptions.compress = {};
        }
        terserPlugin.options.terserOptions.compress.drop_console = true;
      }
    }
  }

  return config;
};
