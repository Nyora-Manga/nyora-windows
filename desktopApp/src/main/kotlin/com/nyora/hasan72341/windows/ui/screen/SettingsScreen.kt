package com.nyora.windows.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyora.windows.AppState
import com.nyora.windows.ai.AiMode
import com.nyora.windows.NavDest
import com.nyora.windows.ReaderMode
import com.nyora.windows.ui.theme.Accent
import com.nyora.windows.ui.theme.AppearanceMode
import com.nyora.windows.ui.theme.LocalNyoraAccent
import com.nyora.windows.ui.theme.NyoraButton
import com.nyora.windows.ui.theme.NyoraScrollContainer
import com.nyora.windows.ui.theme.NyoraShapes
import com.nyora.windows.ui.theme.NyoraTag
import com.nyora.windows.ui.theme.NyoraTone
import com.nyora.windows.ui.theme.NyoraTokens
import com.nyora.windows.ui.theme.nyoraDarkColorScheme
import com.nyora.windows.ui.theme.nyoraLightColorScheme
import com.nyora.windows.ui.theme.SystemTag
import com.nyora.windows.ui.theme.accentGradientSubtle
import com.nyora.windows.ui.theme.glassCard
import com.nyora.windows.ui.theme.glowBorder
import com.nyora.hasan72341.shared.repository.SqlDelightLibraryRepository

/** App version surfaced in the About section; no build system constant is wired yet. */
const val VERSION: String = "1.0.0"

// ── Dropdown option lists ──────────────────────────────────────────────────────────────

private val READER_MODE_OPTIONS = listOf(
    ReaderMode.PAGED    to "Paged",
    ReaderMode.WEBTOON  to "Webtoon",
    ReaderMode.VERTICAL to "Vertical",
)

private val HISTORY_SORT_OPTIONS = listOf(
    "recent" to "Recently Read",
    "alpha"  to "Alphabetical",
    "added"  to "Date Added",
)

private val TARGET_LANG_OPTIONS = listOf(
    "en" to "English",
    "es" to "Spanish",
    "fr" to "French",
    "de" to "German",
    "pt" to "Portuguese",
    "it" to "Italian",
    "ru" to "Russian",
    "zh" to "Chinese (Simplified)",
    "ko" to "Korean",
    "ar" to "Arabic",
)

// BCP-47 tags for the Windows OCR engine (Windows.Media.Ocr). The matching
// Windows OCR language pack must be installed for recognition to work.
private val OCR_LANG_OPTIONS = listOf(
    "auto"    to "Auto (source language)",
    "ja"      to "Japanese",
    "en"      to "English",
    "ko"      to "Korean",
    "zh-Hans" to "Chinese (Simplified)",
    "zh-Hant" to "Chinese (Traditional)",
)

private val AI_MODE_OPTIONS = listOf(
    AiMode.OFF     to "Off (machine translation only)",
    AiMode.WINDOWS to "Windows AI (Phi Silica, on-device)",
    AiMode.BYOK    to "Custom key (OpenAI-compatible)",
)

// ── Categories ──────────────────────────────────────────────────────────────────────
//
// Mac-parity settings: a left category sidebar + a detail pane that swaps per category
// (NSSplitView-style), replacing the old single long scroll. Network / Backup / Tracker
// open as full drill-in panels (keeping their own back button), and Downloads /
// Statistics route to their top-level screens.

private enum class SettingsCategory(val label: String, val icon: ImageVector) {
    APPEARANCE("Appearance", Icons.Rounded.Palette),
    READER("Reader", Icons.Rounded.AutoStories),
    LIBRARY("Library & History", Icons.Rounded.History),
    TRANSLATION("Translation", Icons.Rounded.Translate),
    NETWORK("Network", Icons.Rounded.Cloud),
    DOWNLOADS("Downloads", Icons.Rounded.Download),
    TRACKER("Tracker", Icons.Rounded.SyncAlt),
    SYNC("Nyora Sync", Icons.Rounded.Sync),
    BACKUP("Backup & Restore", Icons.Rounded.Storage),
    PARSERS("App Updates", Icons.Rounded.Update),
    PRIVACY("Privacy", Icons.Rounded.Lock),
    STATS("Statistics", Icons.Rounded.BarChart),
    ABOUT("About", Icons.Rounded.Info),
}

@Composable
fun SettingsScreen(state: AppState) {
    var category by remember { mutableStateOf(SettingsCategory.APPEARANCE) }
    // Full-screen drill-in panels (Network / Backup / Tracker) overlay the whole
    // sidebar+detail; their own back button clears this back to null.
    var panel by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(NyoraTokens.bg)) {
        when (panel) {
            "network" -> NetworkSettingsScreen(state) { panel = null }
            "backup"  -> BackupScreen(state) { panel = null }
            "tracker" -> TrackerScreen(state) { panel = null }
            else -> Row(modifier = Modifier.fillMaxSize()) {
                SettingsSidebar(selected = category) { cat ->
                    when (cat) {
                        SettingsCategory.NETWORK   -> panel = "network"
                        SettingsCategory.BACKUP    -> panel = "backup"
                        SettingsCategory.TRACKER   -> panel = "tracker"
                        SettingsCategory.DOWNLOADS -> { state.destination = NavDest.DOWNLOADS; state.loadDownloads() }
                        SettingsCategory.STATS     -> { state.destination = NavDest.STATS; state.loadStats() }
                        else -> category = cat
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    CategoryDetail(state, category)
                }
            }
        }
    }
}

// ── Category sidebar ──────────────────────────────────────────────────────────────────

@Composable
private fun SettingsSidebar(selected: SettingsCategory, onSelect: (SettingsCategory) -> Unit) {
    Column(
        modifier = Modifier
            .width(240.dp)
            .fillMaxHeight()
            .background(NyoraTokens.surface1)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            SystemTag(text = "Preferences")
            Spacer(Modifier.height(3.dp))
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                color = NyoraTokens.onSurfaceHigh,
            )
        }
        Spacer(Modifier.height(12.dp))
        SettingsCategory.entries.forEach { cat ->
            SidebarItem(cat = cat, isSelected = cat == selected, onClick = { onSelect(cat) })
        }
    }
}

@Composable
private fun SidebarItem(cat: SettingsCategory, isSelected: Boolean, onClick: () -> Unit) {
    val accent = LocalNyoraAccent.current.color
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (isSelected) Modifier.background(accent.copy(alpha = 0.14f)) else Modifier)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            cat.icon,
            contentDescription = null,
            tint = if (isSelected) accent else NyoraTokens.onSurfaceMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            cat.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) NyoraTokens.onSurfaceHigh else NyoraTokens.onSurfaceMuted,
        )
    }
}

/** Scrolling detail container — one category's worth of [SettingsSection]s. */
@Composable
private fun CategoryScroll(content: @Composable ColumnScope.() -> Unit) {
    val scrollState = rememberScrollState()
    NyoraScrollContainer(
        adapter = rememberScrollbarAdapter(scrollState),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            content = content,
        )
    }
}

@Composable
private fun CategoryDetail(state: AppState, category: SettingsCategory) {
    when (category) {
        SettingsCategory.APPEARANCE  -> AppearanceCategory(state)
        SettingsCategory.READER      -> ReaderCategory(state)
        SettingsCategory.LIBRARY     -> LibraryCategory(state)
        SettingsCategory.TRANSLATION -> TranslationCategory(state)
        SettingsCategory.SYNC        -> CategoryScroll { CloudSyncSection(state) }
        SettingsCategory.PARSERS     -> CategoryScroll { ParserUpdatesSection(state) }
        SettingsCategory.PRIVACY     -> PrivacyCategory(state)
        SettingsCategory.ABOUT       -> AboutCategory(state)
        // NETWORK / DOWNLOADS / TRACKER / BACKUP / STATS route via the sidebar's onSelect.
        else -> AppearanceCategory(state)
    }
}

// ── Category panels ───────────────────────────────────────────────────────────────────

@Composable
private fun AppearanceCategory(state: AppState) = CategoryScroll {
    SettingsSection(eyebrow = "Look & Feel", title = "Appearance", icon = Icons.Rounded.Palette) {
        // Theme — Dark / Light segmented control with accentGradientSubtle on selected
        SettingsRow("Theme") {
            AppearanceSegmented(
                selected  = state.appearance,
                onSelect  = { state.setAppearance(it) },
            )
        }
        HairlineDivider()
        // Accent swatches
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                "Color scheme",
                style = MaterialTheme.typography.bodyLarge,
                color = NyoraTokens.onSurfaceHigh,
            )
            Spacer(Modifier.height(14.dp))
            ColorSchemeRow(
                selected   = state.accent,
                appearance = state.appearance,
                onSelect   = { state.setAccent(it) },
            )
        }
    }
}

@Composable
private fun ReaderCategory(state: AppState) = CategoryScroll {
    SettingsSection(eyebrow = "Reading", title = "Reader", icon = Icons.Rounded.AutoStories) {
        SettingsRow("Default Reader Mode") {
            NyoraDropdown(
                selected = state.defaultReaderMode,
                options  = READER_MODE_OPTIONS,
                onSelect = {
                    state.defaultReaderMode = it
                    state.persistSettings()
                },
            )
        }
        HairlineDivider()
        SettingsToggle("Auto-Detect Reader Mode", state.autoDetectReaderMode) {
            state.autoDetectReaderMode = it
            state.persistSettings()
        }
        HairlineDivider()
        SettingsRow("Reader Background") {
            ThreeSegmented(
                options  = listOf("auto" to "Auto", "dark" to "Dark", "light" to "Light"),
                selected = state.readerBackground,
                onSelect = { state.setReaderBackground(it) },
            )
        }
        HairlineDivider()
        SettingsToggle("Show Zoom Buttons", state.showZoomButtons) {
            state.showZoomButtons = it
            state.persistSettings()
        }
        HairlineDivider()
        SettingsToggle("Two Pages in Landscape", state.twoPageLandscape) {
            state.twoPageLandscape = it
            state.persistSettings()
        }
        HairlineDivider()
        SettingsToggle("Auto-Hide Controls", state.autoHideControls) {
            state.autoHideControls = it
            state.persistSettings()
        }
        HairlineDivider()
        SettingsToggle("Keep Screen On", state.keepScreenOn) {
            state.keepScreenOn = it
            state.persistSettings()
        }
        HairlineDivider()
        SettingsToggle("Prefetch Next Pages", state.prefetchEnabled) { state.prefetchEnabled = it }
        HairlineDivider()
        SettingsToggle("Show Page Numbers", state.showPageNumbers) { state.showPageNumbers = it }
        HairlineDivider()
        SettingsToggle("Collapse Description by Default", state.descriptionCollapse) {
            state.descriptionCollapse = it
            state.persistSettings()
        }
        HairlineDivider()
        // Grid size slider 120-220
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Cover Grid Size",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NyoraTokens.onSurfaceHigh,
                )
                Text(
                    "${state.gridSize} dp",
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalNyoraAccent.current.color,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value       = state.gridSize.toFloat(),
                onValueChange = { state.gridSize = it.toInt() },
                onValueChangeFinished = { state.persistSettings() },
                valueRange  = 120f..220f,
                steps       = 9,
                colors      = SliderDefaults.colors(
                    thumbColor          = LocalNyoraAccent.current.color,
                    activeTrackColor    = LocalNyoraAccent.current.color,
                    inactiveTrackColor  = NyoraTokens.surface2,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("120", style = MaterialTheme.typography.labelSmall, color = NyoraTokens.onSurfaceFaint)
                Text("220", style = MaterialTheme.typography.labelSmall, color = NyoraTokens.onSurfaceFaint)
            }
        }
        HairlineDivider()
        InfoNote("Color correction lives in the reader — open any chapter and tap the palette.")
    }
}

@Composable
private fun LibraryCategory(state: AppState) = CategoryScroll {
    SettingsSection(eyebrow = "Library", title = "Library & History", icon = Icons.Rounded.History) {
        SettingsToggle("Hide NSFW Content", state.nsfwFilter) { state.nsfwFilter = it }
        HairlineDivider()
        SettingsToggle("Keep 18+ out of history", state.noNsfwHistory) { state.noNsfwHistory = it }
        HairlineDivider()
        SettingsToggle("Show 18+ Sources", !state.hideNsfwSources) {
            state.hideNsfwSources = !it
            state.persistSettings()
        }
        HairlineDivider()
        // Re-open the onboarding preferences step (languages + 18+) to reseed sources.
        SettingsRow("Content & language preferences") {
            Button(onClick = { state.showPreferences = true }) { Text("Re-run setup") }
        }
        HairlineDivider()
        // History retention slider 0-360 step 30
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "History Retention",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NyoraTokens.onSurfaceHigh,
                )
                Text(
                    if (state.historyRetentionDays == 0) "Forever"
                    else "${state.historyRetentionDays} days",
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalNyoraAccent.current.color,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value       = state.historyRetentionDays.toFloat(),
                onValueChange = {
                    val snapped = (Math.round(it / 30f) * 30).coerceIn(0, 360)
                    state.historyRetentionDays = snapped
                },
                onValueChangeFinished = { state.persistSettings() },
                valueRange  = 0f..360f,
                steps       = 11,
                colors      = SliderDefaults.colors(
                    thumbColor          = LocalNyoraAccent.current.color,
                    activeTrackColor    = LocalNyoraAccent.current.color,
                    inactiveTrackColor  = NyoraTokens.surface2,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Forever", style = MaterialTheme.typography.labelSmall, color = NyoraTokens.onSurfaceFaint)
                Text("360 days", style = MaterialTheme.typography.labelSmall, color = NyoraTokens.onSurfaceFaint)
            }
        }
        HairlineDivider()
        SettingsToggle("Group History by Date", state.groupHistoryByDate) {
            state.groupHistoryByDate = it
            state.persistSettings()
        }
        HairlineDivider()
        SettingsRow("History Sort Order") {
            NyoraDropdown(
                selected = state.historySortOrder,
                options  = HISTORY_SORT_OPTIONS,
                onSelect = {
                    state.historySortOrder = it
                    state.persistSettings()
                },
            )
        }
    }
}

@Composable
private fun TranslationCategory(state: AppState) = CategoryScroll {
    // On-device AI models — the web (nyora-web) ONNX vision + colorize stack, run
    // fully offline. Gated behind an explicit download so the reader never pulls
    // 100+ MB unprompted.
    SettingsSection(eyebrow = "On-device AI", title = "AI Models", icon = Icons.Rounded.Translate) {
        LaunchedEffect(Unit) { state.refreshOnnxReady() }
        state.onnxDownloadLabel?.let { InfoNote("Downloading — $it") }
        SettingsRow("Translation models") {
            if (state.onnxTranslateReady) NyoraTag("Ready", NyoraTone.Success)
            else NyoraButton(text = "Download", onClick = { state.downloadTranslateModels() })
        }
        InfoNote(
            "Manga-Bubble-YOLO bubble detector + on-device OCR for the selected source " +
                "language (manga-ocr for Japanese, PP-OCR for Chinese/English/Korean). " +
                "~20–130 MB; runs fully offline.",
        )
        HairlineDivider()
        SettingsToggle("Colorize Pages", state.colorizeEnabled) { state.toggleColorize() }
        SettingsRow("Colorizer model") {
            if (state.onnxColorizeReady) NyoraTag("Ready", NyoraTone.Success)
            else NyoraButton(text = "Download", onClick = { state.downloadColorizeModel() })
        }
        InfoNote("manga-colorization-v2 (~62 MB) — colours black-and-white pages on-device.")
        HairlineDivider()
        SettingsToggle("Fetch Series Context (character names)", state.translateFandom) {
            state.translateFandom = it
        }
        InfoNote("Looks up the series on MangaBaka/AniList/Fandom to keep character names accurate.")
    }
    SettingsSection(eyebrow = "OCR / MT", title = "Translation", icon = Icons.Rounded.Translate) {
        SettingsToggle("Enable In-Reader Translation", state.translateEnabled) {
            state.translateEnabled = it
        }
        HairlineDivider()
        SettingsToggle("Instant Translate on Chapter Open", state.instantTranslate) {
            state.instantTranslate = it
            state.persistSettings()
        }
        HairlineDivider()
        SettingsRow("Target Language") {
            NyoraDropdown(
                selected = state.translateTarget,
                options  = TARGET_LANG_OPTIONS,
                onSelect = { state.changeTranslateTarget(it) },
            )
        }
        HairlineDivider()
        SettingsRow("OCR Source Language") {
            NyoraDropdown(
                selected = state.translateLangs,
                options  = OCR_LANG_OPTIONS,
                onSelect = { state.changeTranslateLangs(it) },
            )
        }
        InfoNote(
            "OCR runs on the downloaded on-device models above (manga-ocr / PP-OCR). " +
                "Pick the language the page is written in; download the models under " +
                "AI Models if they aren't Ready yet.",
        )
        HairlineDivider()
        SettingsRow("AI Refinement") {
            NyoraDropdown(
                selected = state.aiMode,
                options  = AI_MODE_OPTIONS,
                onSelect = { state.changeAiMode(it) },
            )
        }
        when (state.aiMode) {
            AiMode.WINDOWS -> {
                LaunchedEffect(Unit) { state.refreshWindowsAi() }
                InfoNote(
                    when (state.windowsAiAvailable) {
                        true -> "Windows AI (Phi Silica) is available — polishing translations on-device, free and private."
                        false -> "Windows AI isn't available on this PC (needs a Copilot+ PC with the Windows App SDK runtime). Falling back to machine translation — or choose Custom key below."
                        null -> "Checking Windows AI availability…"
                    },
                )
            }
            AiMode.BYOK -> ByokFields(state)
            AiMode.OFF -> {}
        }
    }
}

@Composable
private fun PrivacyCategory(state: AppState) = CategoryScroll {
    SettingsSection(eyebrow = "Security", title = "Privacy", icon = Icons.Rounded.Lock) {
        SettingsToggle("Incognito Mode", state.incognito) {
            state.setIncognito(it)
        }
        HairlineDivider()
        InfoNote("Reading is not recorded to history while incognito.")
        HairlineDivider()
        SettingsToggle("Confirm Before Quitting", state.confirmBeforeQuit) {
            state.confirmBeforeQuit = it
            state.persistSettings()
        }
    }
}

@Composable
private fun AboutCategory(state: AppState) = CategoryScroll {
    SettingsSection(eyebrow = "Info", title = "About", icon = Icons.Rounded.Info) {
        SettingsRow("Build") {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Nyora • Windows",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = NyoraTokens.onSurfaceHigh,
                )
                Text(
                    "v$VERSION",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalNyoraAccent.current.color,
                )
            }
        }
        HairlineDivider()
        SettingsRow("Website") {
            Text(
                "nyora.xyz",
                style = MaterialTheme.typography.labelSmall,
                color = LocalNyoraAccent.current.color,
                modifier = Modifier.clickable { state.openExternalUrl("https://nyora.xyz") },
            )
        }
        HairlineDivider()
        SettingsRow("Source code") {
            Text(
                "Hasan72341/nyora-windows",
                style = MaterialTheme.typography.labelSmall,
                color = LocalNyoraAccent.current.color,
                modifier = Modifier.clickable { state.openExternalUrl("https://github.com/Nyora-Manga/nyora-windows") },
            )
        }
        HairlineDivider()
        SettingsRow("Platform") {
            Text(
                "${System.getProperty("os.name")} (${System.getProperty("os.arch")})",
                style = MaterialTheme.typography.labelSmall,
                color = NyoraTokens.onSurfaceMuted,
            )
        }
        HairlineDivider()
        SettingsRow("Database") {
            Text(
                text = SqlDelightLibraryRepository.defaultDatabasePath().toString(),
                style = MaterialTheme.typography.bodySmall,
                color = NyoraTokens.onSurfaceFaint,
            )
        }
        HairlineDivider()
        SettingsRow("Developer") {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Md Hasan Raza",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = NyoraTokens.onSurfaceHigh,
                )
                Text(
                    "Creator of Nyora",
                    style = MaterialTheme.typography.labelSmall,
                    color = NyoraTokens.onSurfaceMuted,
                )
            }
        }
        HairlineDivider()
        SettingsRow("Instagram") {
            Text(
                "@md_hasan_raza____",
                style = MaterialTheme.typography.labelSmall,
                color = LocalNyoraAccent.current.color,
                modifier = Modifier.clickable { state.openExternalUrl("https://www.instagram.com/md_hasan_raza____?igsh=MXZ6eTk2Y3FsNGs3aQ==") },
            )
        }
        HairlineDivider()
        SettingsRow("LinkedIn") {
            Text(
                "md-hasan-raza",
                style = MaterialTheme.typography.labelSmall,
                color = LocalNyoraAccent.current.color,
                modifier = Modifier.clickable { state.openExternalUrl("https://www.linkedin.com/in/md-hasan-raza-8817372a7/") },
            )
        }
        HairlineDivider()
        SettingsRow("GitHub") {
            Text(
                "Hasan72341",
                style = MaterialTheme.typography.labelSmall,
                color = LocalNyoraAccent.current.color,
                modifier = Modifier.clickable { state.openExternalUrl("https://github.com/Hasan72341") },
            )
        }
        HairlineDivider()
        SettingsRow("Email") {
            Text(
                "hasanraza96@outlook.com",
                style = MaterialTheme.typography.labelSmall,
                color = LocalNyoraAccent.current.color,
                modifier = Modifier.clickable { state.openExternalUrl("mailto:hasanraza96@outlook.com") },
            )
        }
        HairlineDivider()
        Text(
            "Nyora — your manga library, everywhere. Available on Android, Windows, macOS, Linux, iOS and the web.",
            style = MaterialTheme.typography.labelSmall,
            color = NyoraTokens.onSurfaceMuted,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}

// ── Nyora Sync + app updates (their own self-loading sections) ─────────────────────

@Composable
private fun CloudSyncSection(state: AppState) {
    LaunchedEffect(Unit) { state.refreshCloudSyncStatus() }
    val status = state.cloudSyncStatus
    val busy = state.cloudSyncBusy
    val statusText = when {
        status == null -> "Checking..."
        !status.isConfigured -> "Not configured"
        status.isAuthenticated -> "Signed in"
        else -> "Signed out"
    }
    SettingsSection(eyebrow = "Cloud", title = "Nyora Sync", icon = Icons.Rounded.Sync) {
        SettingsRow("Status") {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (status?.isAuthenticated == true) LocalNyoraAccent.current.color else NyoraTokens.onSurfaceMuted,
                )
                val subtitle = state.cloudEmail.takeIf { it.isNotBlank() && status?.isAuthenticated == true }
                    ?: status?.email?.takeIf { it.isNotBlank() }
                    ?: status?.userId?.takeIf { it.isNotBlank() }?.take(8)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = NyoraTokens.onSurfaceFaint,
                    )
                }
            }
        }
        HairlineDivider()
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (status?.isAuthenticated == true) {
                Button(onClick = { state.cloudSyncNow() }, enabled = !busy) {
                    Text(if (busy) "Working..." else "Sync Now")
                }
                OutlinedButton(onClick = { state.cloudRestoreFromCloud() }, enabled = !busy) {
                    Text("Restore From Cloud")
                }
                TextButton(onClick = { state.cloudSignOut() }, enabled = !busy) {
                    Text("Sign Out")
                }
            } else {
                var email by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                OutlinedTextField(value = email, onValueChange = { email = it; state.authMessage = null }, label = { Text("Email") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = password, onValueChange = { password = it; state.authMessage = null }, label = { Text("Password") }, singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(onClick = { state.cloudSignIn(email, password) }, enabled = !busy) {
                        Text(if (busy) "Working…" else "Sign in")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { state.cloudRegister(email, password) }, enabled = !busy) {
                        Text(if (busy) "Working…" else "Create account")
                    }
                }
                state.authMessage?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalNyoraAccent.current.color,
                    )
                }
            }
        }
    }
}

@Composable
private fun ParserUpdatesSection(state: AppState) {
    LaunchedEffect(Unit) { state.refreshOtaStatus() }
    val status = state.otaStatus
    val busy = state.otaBusy
    val statusText = when {
        status == null -> "Checking..."
        status.isActive -> "OTA v${status.otaVersion} active"
        else -> "Bundled v${status.bundledVersion}"
    }
    SettingsSection(eyebrow = "Parsers", title = "Parser Updates", icon = Icons.Rounded.Update) {
        SettingsRow("Version") {
            Text(
                statusText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (status?.isActive == true) LocalNyoraAccent.current.color else NyoraTokens.onSurfaceMuted,
            )
        }
        HairlineDivider()
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { state.otaCheckNow() }, enabled = !busy) {
                Text(if (busy) "Checking..." else "Check for Updates")
            }
            if (status?.isActive == true) {
                Text(
                    "Restart the app to apply newly downloaded parsers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NyoraTokens.onSurfaceMuted,
                )
            }
        }
    }

    // Source repository — the (signed) link that activates real sources. Kept here
    // so the demo library is what's front-and-centre until a valid repo is added.
    SettingsSection(eyebrow = "Sources", title = "Source repository", icon = Icons.Rounded.Storage) {
        if (state.repositoryActive) {
            SettingsRow("Status") {
                Text(
                    "Connected",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalNyoraAccent.current.color,
                )
            }
            HairlineDivider()
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    state.repositoryUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = NyoraTokens.onSurfaceMuted,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { state.removeSourceRepository() }) { Text("Remove repository") }
            }
        } else {
            var link by remember { mutableStateOf(state.repositoryUrl) }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Add a source repository link to load its sources.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NyoraTokens.onSurfaceMuted,
                )
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it; if (state.repoError != null) state.repoError = null },
                    label = { Text("Repository URL") },
                    singleLine = true,
                    enabled = !state.repoLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.repoError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = { state.addSourceRepository(link) }, enabled = !state.repoLoading) {
                        Text(if (state.repoLoading) "Adding…" else "Add repository")
                    }
                }
            }
        }
    }
}

// ── Section shell ─────────────────────────────────────────────────────────────────

@Composable
private fun SettingsSection(
    eyebrow: String,
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = LocalNyoraAccent.current.color,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                SystemTag(text = eyebrow)
                Spacer(Modifier.height(3.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp,
                    color = NyoraTokens.onSurfaceHigh,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassCard(shape = RoundedCornerShape(24.dp), fill = NyoraTokens.surface1)
                .padding(vertical = 4.dp),
            content = content,
        )
    }
}

// ── Generic rows ──────────────────────────────────────────────────────────────────

@Composable
private fun SettingsRow(label: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = NyoraTokens.onSurfaceHigh,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(16.dp))
        trailing()
    }
}

@Composable
private fun SettingsToggle(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    SettingsRow(label) {
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor   = Color.White,
                checkedTrackColor   = LocalNyoraAccent.current.color,
                uncheckedThumbColor = NyoraTokens.onSurfaceMuted,
                uncheckedTrackColor = NyoraTokens.surface1,
                uncheckedBorderColor = NyoraTokens.hairlineStrong,
            ),
        )
    }
}

@Composable
private fun HairlineDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 18.dp),
        color = NyoraTokens.hairlineFaint,
    )
}

// ── Appearance segmented control (Dark / Light) ──────────────────────────────────

@Composable
private fun AppearanceSegmented(
    selected: AppearanceMode,
    onSelect: (AppearanceMode) -> Unit,
) {
    val subtleBrush = accentGradientSubtle()
    val accent      = LocalNyoraAccent.current.color
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(NyoraTokens.surface1)
            .border(width = 1.dp, color = NyoraTokens.hairlineFaint, shape = RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppearanceMode.entries.forEach { mode ->
            val isSelected = mode == selected
            val label = when (mode) {
                AppearanceMode.DARK  -> "Dark"
                AppearanceMode.LIGHT -> "Light"
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .then(
                        if (isSelected) Modifier.background(subtleBrush)
                        else Modifier
                    )
                    .then(
                        if (isSelected) Modifier.border(
                            width = 1.dp,
                            color = accent.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(11.dp),
                        ) else Modifier
                    )
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text       = label,
                    style      = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color      = if (isSelected) accent else NyoraTokens.onSurfaceMuted,
                )
            }
        }
    }
}

// ── Three-option segmented control (e.g. reader background) ───────────────────────

@Composable
private fun ThreeSegmented(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val subtleBrush = accentGradientSubtle()
    val accent      = LocalNyoraAccent.current.color
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(NyoraTokens.surface1)
            .border(width = 1.dp, color = NyoraTokens.hairlineFaint, shape = RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .then(
                        if (isSelected) Modifier.background(subtleBrush)
                        else Modifier
                    )
                    .then(
                        if (isSelected) Modifier.border(
                            width = 1.dp,
                            color = accent.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(11.dp),
                        ) else Modifier
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text       = label,
                    style      = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color      = if (isSelected) accent else NyoraTokens.onSurfaceMuted,
                )
            }
        }
    }
}

/** A muted explanatory note rendered inside a settings section. */
@Composable
private fun InfoNote(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = NyoraTokens.onSurfaceMuted,
        )
    }
}

/**
 * Bring-your-own-key fields for the OpenAI-compatible refiner. Endpoint/model
 * preferences persist; the masked API key remains in memory for this session only.
 */
@Composable
private fun ByokFields(state: AppState) {
    var baseUrl by remember { mutableStateOf(state.byokBaseUrl) }
    var apiKey by remember { mutableStateOf(state.byokApiKey) }
    var model by remember { mutableStateOf(state.byokModel) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; state.setByok(it, apiKey, model) },
            label = { Text("Base URL") },
            placeholder = { Text("https://api.openai.com/v1") },
            singleLine = true,
            isError = state.byokEndpointError != null,
            modifier = Modifier.fillMaxWidth(),
        )
        state.byokEndpointError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; state.setByok(baseUrl, it, model) },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it; state.setByok(baseUrl, apiKey, it) },
            label = { Text("Model") },
            placeholder = { Text("gpt-4o-mini") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "OpenAI-compatible (OpenAI, OpenRouter, Groq, LM Studio, Ollama) OR Anthropic — " +
                "enter an api.anthropic.com base URL or a claude model and it switches automatically. " +
                "Your API key stays only in memory and is cleared when Nyora closes. " +
                "BYOK requests require HTTPS, except a local loopback server " +
                    "(localhost, 127.0.0.1, or [::1]). Redirects are never followed.",
            style = MaterialTheme.typography.bodySmall,
            color = NyoraTokens.onSurfaceMuted,
        )
    }
}

@Composable
private fun <T> NyoraDropdown(
    selected: T,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == selected }?.second ?: selected.toString()
    val accent = LocalNyoraAccent.current.color

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(NyoraTokens.surface2)
                .border(width = 1.dp, color = NyoraTokens.hairlineStrong, shape = RoundedCornerShape(12.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                currentLabel,
                style      = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color      = NyoraTokens.onSurfaceHigh,
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp),
            )
        }

        DropdownMenu(
            expanded         = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color      = if (isSelected) accent else NyoraTokens.onSurfaceHigh,
                        )
                    },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

// ── Color-scheme preview cards (android-style) ──────────────────────────────────────
//
// Mirrors nyora-android's item_color_scheme.xml: a horizontal scroll of themed mini
// cards, each showing "Abc" text + two secondary-tone bars + a primary swatch + a check
// when selected, with the scheme name beneath. Each card builds a mini Material scheme
// from its own primary (resolved for the current appearance) so it renders self-themed.

@Composable
private fun ColorSchemeRow(
    selected: Accent,
    appearance: AppearanceMode,
    onSelect: (Accent) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(Accent.entries) { scheme ->
            ColorSchemeCard(
                scheme     = scheme,
                appearance = appearance,
                isSelected = scheme == selected,
                onClick    = { onSelect(scheme) },
            )
        }
    }
}

@Composable
private fun ColorSchemeCard(
    scheme: Accent,
    appearance: AppearanceMode,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    // Derive a mini Material scheme from this card's own primary so it renders self-themed,
    // matching the spec note ("build a mini derived scheme from the primary").
    val primary = scheme.colorFor(appearance)
    val miniScheme = remember(scheme, appearance) {
        if (appearance == AppearanceMode.LIGHT) nyoraLightColorScheme(primary)
        else nyoraDarkColorScheme(primary)
    }
    val cardShape = NyoraShapes.medium

    Column(
        modifier = Modifier
            .clip(NyoraShapes.small)
            .clickable { onClick() }
            .padding(6.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .size(width = 72.dp, height = 96.dp)
                .clip(cardShape)
                .background(miniScheme.surface)
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) miniScheme.primary else miniScheme.outline,
                    shape = cardShape,
                ),
        ) {
            // "Abc" sample text (top-start)
            Text(
                "Abc",
                style = MaterialTheme.typography.bodyMedium,
                color = miniScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 6.dp, top = 8.dp),
            )
            // Two secondary-tone bars (bottom-start)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 28.dp, height = 6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(miniScheme.secondary),
                )
                Box(
                    modifier = Modifier
                        .size(width = 46.dp, height = 6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(miniScheme.secondary),
                )
            }
            // Primary swatch (bottom-end)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 6.dp)
                    .size(16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(miniScheme.primary),
            )
            // Check when selected (top-end)
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = miniScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            scheme.label,
            style = MaterialTheme.typography.titleSmall,
            color = if (isSelected) NyoraTokens.onSurfaceHigh else NyoraTokens.onSurfaceBody,
            maxLines = 1,
            modifier = Modifier.width(72.dp).padding(start = 2.dp),
        )
    }
}
