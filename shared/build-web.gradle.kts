plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    explicitApi()
    js {
        outputModuleName = "livephoto-core"
        useEsModules()
        browser {
            testTask {
                useKarma { useChromeHeadless() }
                outputs.upToDateWhen { false }
                outputs.doNotCacheIf("Always execute common protocol regressions") { true }
            }
        }
        binaries.library()
        generateTypeScriptDefinitions()
    }
    sourceSets { commonTest.dependencies { implementation(libs.kotlin.test) } }
    // Pure Kotlin protocol tests are shared with Native. Desktop FFmpeg/fixture
    // tests live in desktopTest; Web does not invent their platform actuals.
}
