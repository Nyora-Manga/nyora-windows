import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    testImplementation(kotlin("test"))
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // material-icons-extended was deprecated + frozen at 1.7.3 (no 1.8.x); pin it
    // explicitly — the icon vectors are compatible with the 1.8.2 runtime.
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation(compose.components.resources)

    // Material You dynamic color for Compose Desktop: generates a full M3 tonal scheme
    // (secondary/tertiary/containers/surfaces) from the accent seed. androidx's
    // dynamicColorScheme is Android-only, so MaterialKolor provides the KMP equivalent.
    implementation("com.materialkolor:material-kolor:2.0.0")

    // On-device ONNX inference for the in-app manga colorizer + translation vision
    // stack (Manga-Bubble-YOLO detection, manga-ocr, PP-OCR), mirroring nyora-web's
    // onnxruntime-web pipeline. CPU EP (bundles win/linux/mac native libs).
    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Image loading — Coil3 with OkHttp on desktop
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Embedded Chromium (KCEF/JCEF) driving the in-app Cloudflare solver's WebView.
    // Pulls dev.datlag:kcef transitively. First run downloads a Chromium bundle.
    implementation("io.github.kevinnzou:compose-webview-multiplatform:1.9.40")

    // JNA — Windows-native chrome: DWM dark title bar + Mica backdrop, and registry
    // reads for the system light/dark theme + accent colour. Runtime use is guarded
    // to Windows; harmless on the macOS/Linux build host.
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // Force modern Skiko runtime to match the Compose version and prevent
    // UnsatisfiedLinkError. Windows x64 + ARM64 are the primary targets here;
    // the macOS/Linux runtimes are kept so the project still compiles + runs on
    // a non-Windows build host (e.g. CI dry-runs, local dev on macOS).
    val skikoVersion = "0.9.4.2"
    implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:$skikoVersion")
    implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-arm64:$skikoVersion")
    implementation("org.jetbrains.skiko:skiko-awt-runtime-macos-arm64:$skikoVersion")
    implementation("org.jetbrains.skiko:skiko-awt-runtime-macos-x64:$skikoVersion")
    implementation("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:$skikoVersion")
    implementation("org.jetbrains.skiko:skiko-awt-runtime-linux-arm64:$skikoVersion")
}

compose.desktop {
    application {
        mainClass = "com.nyora.windows.MainKt"

        nativeDistributions {
            val osName = System.getProperty("os.name").lowercase()
            if (osName.contains("linux")) {
                targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            } else if (osName.contains("mac") || osName.contains("darwin")) {
                targetFormats(TargetFormat.Dmg, TargetFormat.Pkg)
            } else if (osName.contains("win")) {
                targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            }

            packageName        = "nyora"
            packageVersion     = "2.1.0"
            description        = "Nyora — AI-powered manga reader"
            vendor             = "Nyora"
            copyright          = "© 2025 Nyora contributors"

            // Bundle the full JDK module set into the jpackage/jlink runtime.
            // Without this, jlink strips modules it can't statically detect — e.g.
            // java.sql (needed by SQLDelight's JDBC SQLite driver) and the TLS
            // crypto modules — and the packaged app crashes at runtime with
            // NoClassDefFoundError: java/sql/DriverManager.
            includeAllModules = true

            // Primary Windows packaging (MSI installer + portable EXE). The MSI
            // upgradeUuid MUST stay stable across releases so upgrades replace the
            // previous install instead of stacking side-by-side.
            windows {
                packageName    = "Nyora"
                menuGroup      = "Nyora"
                upgradeUuid    = "7E3B6C2A-1F44-4D58-9B0E-2C9A4F6D1E70"
                menu           = true
                perUserInstall = true
                dirChooser     = true
                console        = false
                iconFile.set(project.file("src/main/resources/nyora.ico"))
            }

            linux {
                packageName    = "nyora"
                debMaintainer  = "nyora-dev@nyora.app"
                menuGroup      = "Graphics"
                appCategory    = "Utility"
                // iconFile.set(project.file("src/main/resources/nyora.png"))
            }

            // Bundle a JRE so users don't need one pre-installed.
            // Requires JDK 17+ with jpackage on the build host.
            jvmArgs(
                // Allow GraalJS polyglot — needed when the sidecar runs in-process
                "--add-opens", "java.base/jdk.internal.module=ALL-UNNAMED",
                "-Xmx512m",
            )
        }

        buildTypes.release.proguard {
            isEnabled = false  // GraalJS reflection breaks with ProGuard
        }
    }
}

configurations.all {
    resolutionStrategy {
        eachDependency {
            if (requested.group.startsWith("org.jetbrains.compose") &&
                !requested.name.startsWith("material-icons")) {
                useVersion("1.8.2")
            }
            // JCEF (via compose-webview-multiplatform → KCEF) requests jogamp
            // gluegen-rt/jogl-all 2.5.0, which is ONLY on jogamp.org — and that
            // server is frequently down (build can't resolve it). Pin to 2.3.2,
            // which is on Maven Central. The app never calls JOGL directly.
            if (requested.group == "org.jogamp.gluegen" || requested.group == "org.jogamp.jogl") {
                useVersion("2.3.2")
            }
        }
    }
}

// The translation post-processing is pure and heavily regex-driven, so it is
// verified against the web reader's output rather than by inspection.
tasks.withType<Test>().configureEach { useJUnitPlatform() }
