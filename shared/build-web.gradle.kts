plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    explicitApi()
    js {
        outputModuleName = "livephoto-core"
        useEsModules()
        browser()
        binaries.library()
        generateTypeScriptDefinitions()
    }
    // Desktop test hosts require FFmpeg and filesystem fixtures. This first Web
    // target tests the production export in a real browser instead of inventing
    // fake actual implementations or skipping those backend-dependent tests.
}
