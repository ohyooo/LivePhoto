// KotlinKarma adds another source-map plugin after webpack.config.d is merged.
// Patch that final Karma config too; keep all options and real source maps.
// https://webpack.js.org/api/compiler-object/#webpack
config.webpack.plugins = config.webpack.plugins.map(plugin => {
    if (plugin?.constructor?.name !== 'SourceMapDevToolPlugin') return plugin;
    const options = plugin.options;
    return {
        apply(compiler) {
            new compiler.webpack.SourceMapDevToolPlugin(options).apply(compiler);
        }
    };
});
