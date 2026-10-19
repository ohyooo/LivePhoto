// Kotlin's generated config can resolve webpack from the UI workspace while
// webpack-cli loads its hoisted copy. Preserve source maps, but construct their
// plugin from the running compiler so Compilation classes cannot be mixed.
// https://webpack.js.org/api/compiler-object/#webpack
config.plugins = (config.plugins || []).map(plugin => {
    if (plugin?.constructor?.name !== 'SourceMapDevToolPlugin') return plugin;
    const options = plugin.options;
    return {
        apply(compiler) {
            new compiler.webpack.SourceMapDevToolPlugin(options).apply(compiler);
        }
    };
});
