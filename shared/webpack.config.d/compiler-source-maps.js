// Keep Kotlin's generated source-map options, but use the running compiler's
// webpack instance even when npm installs another copy in this test workspace.
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
