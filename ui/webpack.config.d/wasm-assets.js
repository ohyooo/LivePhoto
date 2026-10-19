// Keep module names as well as content hashes: a graphics engine's Wasm must
// never be mistaken for the Kotlin Compose/Core application in release proof.
config.output.assetModuleFilename = '[name].[contenthash][ext]';
