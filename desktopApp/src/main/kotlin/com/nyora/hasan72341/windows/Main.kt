package com.nyora.windows

import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nyora.windows.ui.App
import com.nyora.windows.ui.theme.AppearanceMode
import com.nyora.windows.ui.theme.WindowsNative
import com.nyora.hasan72341.shared.HelperMain
import com.nyora.hasan72341.shared.scrobbling.DesktopOAuthCallbacks
import com.nyora.hasan72341.shared.scrobbling.DesktopUrlScheme
import com.nyora.hasan72341.shared.data.ExtensionInstaller
import com.nyora.hasan72341.shared.data.SourceCatalogClient
import com.nyora.hasan72341.shared.proxy.NyoraRestServer
import com.nyora.hasan72341.shared.reader.PageImageLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive

fun main(args: Array<String>) {
    // Register the nyora:// URL scheme + single-instance dispatcher for tracker
    // OAuth callbacks. If this launch was only the OS delivering a nyora:// URL to
    // an already-running instance, it forwards the URL and we exit before booting.
    if (DesktopUrlScheme.install(args) { url -> DesktopOAuthCallbacks.complete(url) }) return

    // GraalVM 24.x ships no Truffle native attach library for Windows ARM64, so the
    // optimizing runtime fails to initialise (NoSuchFileException for
    // META-INF/.../libtruffleattach/windows/aarch64) and every JS parser dies with an
    // uncaught InternalError — browsing a source just hangs. Force the interpreter-only
    // Truffle runtime there; it needs no native library. (x64 keeps its JIT.) This MUST
    // run before any GraalVM/Truffle class is loaded.
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val osArch = System.getProperty("os.arch").orEmpty().lowercase()
    if (osName.contains("win") && (osArch.contains("aarch64") || osArch.contains("arm"))) {
        System.setProperty("truffle.TruffleRuntime", "com.oracle.truffle.api.impl.DefaultTruffleRuntime")
    }

    // Bootstrap the shared logic: DB, migrations, source seeding, Supabase sync, and
    // network config. Crucially this wires repository.supabaseSync (without it cloud
    // sign-in fails with "Supabase sync unavailable"). Matches the mac helper, the
    // linux desktop app, and the deployable web server — one bootstrap everywhere.
    val boot = HelperMain.bootstrap()
    val facade = boot.facade
    val networkConfig = boot.networkConfig

    // Start the REST server in-process. The image proxy endpoint requires it,
    // and keeping the same HTTP surface means future CLI / remote clients work too.
    // Source availability gate: the local helper serves NO sources/content until
    // a signed source-repository index is added (see AppState.addSourceRepository).
    // Starts closed so a fresh install / direct localhost probe exposes nothing.
    val sourcesGate = java.util.concurrent.atomic.AtomicBoolean(false)
    val server = NyoraRestServer(
        facade = facade,
        catalog = SourceCatalogClient(networkConfig = networkConfig),
        installer = ExtensionInstaller(networkConfig = networkConfig),
        pageLoader = PageImageLoader(networkConfig = networkConfig),
        downloads = boot.downloads,
        networkConfig = networkConfig,
    )
    val baseUrl = server.start()

    val appState = AppState(facade = facade, imageBaseUrl = baseUrl, sourcesGate = sourcesGate)

    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })

    application {
        // ── (1) Confirm-before-quit ───────────────────────────────────────────
        // showQuitDialog tracks whether the confirmation AlertDialog is visible.
        var showQuitDialog by remember { mutableStateOf(false) }

        // Native window placement: restore the last size + maximized/snapped state,
        // and persist changes so the app reopens exactly where you left it.
        // Clamp the saved/default size to the current display so the window never
        // opens larger than the screen (small laptops, HiDPI, a smaller monitor).
        val screen = remember { java.awt.Toolkit.getDefaultToolkit().screenSize }
        val windowState = rememberWindowState(
            width = appState.windowWidth.coerceAtMost(screen.width - 40).dp,
            height = appState.windowHeight.coerceAtMost(screen.height - 60).dp,
            position = WindowPosition(Alignment.Center),
            placement = if (appState.windowMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
        )

        Window(
            onCloseRequest = {
                if (appState.confirmBeforeQuit) {
                    // Don't exit immediately — ask first.
                    showQuitDialog = true
                } else {
                    exitApplication()
                }
            },
            title = "Nyora",
            icon = painterResource("nyora.png"),
            state = windowState,
            resizable = true,
        ) {
            // Native minimum size so Windows snap / drag-resize respects a usable floor.
            LaunchedEffect(Unit) {
                window.minimumSize = java.awt.Dimension(
                    minOf(900, screen.width - 40),
                    minOf(600, screen.height - 60),
                )
            }
            // Native chrome: dark title bar matching the app theme + Mica backdrop (Win11).
            LaunchedEffect(appState.appearance) {
                WindowsNative.applyChrome(window, dark = appState.appearance != AppearanceMode.LIGHT)
            }
            // Remember size + maximized across launches; collectLatest + delay debounces
            // the rapid stream of values produced while dragging the window edge.
            LaunchedEffect(windowState) {
                snapshotFlow {
                    Triple(
                        windowState.size.width.value.toInt(),
                        windowState.size.height.value.toInt(),
                        windowState.placement == WindowPlacement.Maximized,
                    )
                }
                    .distinctUntilChanged()
                    .collectLatest { (w, h, max) ->
                        delay(400)
                        if (w > 0 && h > 0) appState.saveWindowPlacement(w, h, max)
                    }
            }
            App(state = appState)

            // Confirmation dialog rendered inside the window's composition scope.
            if (showQuitDialog) {
                AlertDialog(
                    onDismissRequest = { showQuitDialog = false },
                    title   = { Text("Quit Nyora?") },
                    text    = { Text("Are you sure you want to quit?") },
                    confirmButton = {
                        Button(onClick = { showQuitDialog = false; exitApplication() }) {
                            Text("Quit")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showQuitDialog = false }) {
                            Text("Cancel")
                        }
                    },
                )
            }

            // ── (2) Keep-screen-on heartbeat ──────────────────────────────────
            // While the reader is open and keepScreenOn is enabled, nudge the
            // mouse pointer by ±1 px every 30 s to prevent the OS screensaver /
            // power manager from activating. Wrapped in runCatching so it is a
            // complete no-op when java.awt.Robot is unavailable (headless, Wayland
            // without XWayland, container environments, etc.).
            val keepActive = appState.keepScreenOn && appState.showReader
            LaunchedEffect(keepActive) {
                if (!keepActive) return@LaunchedEffect
                runCatching {
                    val robot = java.awt.Robot()
                    while (isActive) {
                        delay(30_000L)
                        if (!isActive) break
                        runCatching {
                            val pos = java.awt.MouseInfo.getPointerInfo().location
                            robot.mouseMove(pos.x + 1, pos.y)
                            robot.mouseMove(pos.x,     pos.y)
                        }
                    }
                }
            }
        }
    }
}
