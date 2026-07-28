package com.nyora.windows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nyora.windows.bridge.CatalogEntry
import com.nyora.windows.bridge.CatalogResponse
import com.nyora.windows.bridge.CategoriesResponse
import com.nyora.windows.bridge.CategoryDto
import com.nyora.windows.bridge.DownloadDto
import com.nyora.windows.bridge.DownloadResponse
import com.nyora.windows.bridge.DownloadsResponse
import com.nyora.windows.bridge.GlobalSearchGroup
import com.nyora.windows.bridge.GlobalSearchResponse
import com.nyora.windows.bridge.LocalCbzEntry
import com.nyora.windows.bridge.LocalChapterResponse
import com.nyora.windows.bridge.LocalScanResponse
import com.nyora.windows.bridge.NyoraHttpClient
import com.nyora.windows.bridge.AlternativesResponse
import com.nyora.windows.bridge.AniListFeedMedia
import com.nyora.windows.bridge.AniListSearchResponse
import com.nyora.windows.bridge.MangaBakaSearchResponse
import com.nyora.windows.bridge.mbPopularity
import com.nyora.windows.bridge.mbUsable
import com.nyora.windows.bridge.toFeedMedia
import com.nyora.windows.bridge.DownloadSettingsDto
import com.nyora.windows.bridge.DownloadSettingsResponse
import com.nyora.windows.bridge.MangaPrefsDto
import com.nyora.windows.bridge.NetworkSettingsDto
import com.nyora.windows.bridge.NetworkSettingsResponse
import com.nyora.windows.bridge.StatsResponse
import com.nyora.windows.bridge.OtaStatusResponse
import com.nyora.windows.bridge.SupabaseLocalDataResponse
import com.nyora.windows.bridge.SupabaseOkResponse
import com.nyora.windows.bridge.SupabaseStatusResponse
import com.nyora.windows.bridge.SuggestionsResponse
import com.nyora.windows.bridge.SyncSignInResponse
import com.nyora.windows.ai.AiMode
import com.nyora.windows.ai.AiEndpointPolicy
import com.nyora.windows.ai.AiRefinement
import com.nyora.windows.ai.WindowsAiRefiner
import com.nyora.windows.translate.ColorizePageResult
import com.nyora.windows.translate.MangaTranslator
import com.nyora.windows.translate.PageTranslation
import com.nyora.windows.ui.theme.AppearanceMode
import com.nyora.windows.ui.theme.Accent
import com.nyora.windows.ui.theme.WindowsNative
import com.nyora.hasan72341.shared.NyoraFacade
import com.nyora.hasan72341.shared.extension.MangaDetails
import com.nyora.hasan72341.shared.model.Manga
import com.nyora.hasan72341.shared.model.MangaChapter
import com.nyora.hasan72341.shared.model.MangaPage
import com.nyora.hasan72341.shared.model.MangaSource
import com.nyora.hasan72341.shared.repository.BookmarkRow
import com.nyora.hasan72341.shared.repository.HistoryRow
import com.nyora.hasan72341.shared.scrobbling.ScrobblerManga
import com.nyora.hasan72341.shared.scrobbling.ScrobblerOAuth
import com.nyora.hasan72341.shared.scrobbling.ScrobblerRepository
import com.nyora.hasan72341.shared.scrobbling.ScrobblerService
import com.nyora.hasan72341.shared.scrobbling.ScrobblingStatus
import com.nyora.hasan72341.shared.repository.UpdateRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.awt.Desktop
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import com.sun.net.httpserver.HttpServer

enum class NavDest {
    EXPLORE, FAVOURITES, HISTORY, BOOKMARKS, UPDATES, LOCAL_FILES, DOWNLOADS, SETTINGS, STATS, SUGGESTIONS
}

enum class ReaderMode { PAGED, WEBTOON, VERTICAL }

enum class ExploreMode { POPULAR, LATEST, SEARCH }

class AppState(
    val facade: NyoraFacade,
    val imageBaseUrl: String,
    // Shared with the local helper server: keeps its source/content endpoints
    // gated in lock-step with repositoryActive (airtight — even direct probes).
    private val sourcesGate: java.util.concurrent.atomic.AtomicBoolean? = null,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val http = NyoraHttpClient(imageBaseUrl)
    // Per-page colorized PNGs are transient app data, kept below Nyora's
    // user-owned configuration directory rather than the shared system temp dir.
    private val translator = MangaTranslator(File(configDir(), "cache/colorized-pages"))
    private data class ReaderAiJob(val id: Long, val job: Job)

    // ── Navigation ────────────────────────────────────────────────────────────
    var destination by mutableStateOf(NavDest.EXPLORE)
    var showDetails  by mutableStateOf(false)
    var showReader   by mutableStateOf(false)
    var showCatalog  by mutableStateOf(false)
    var showGlobalSearch by mutableStateOf(false)

    // ── Source repository (Windows) ─────────────────────────────────────────────
    // No sources are active until a source-repository INDEX is added. The index is
    // fetched from a user-supplied URL and its Ed25519 signature is verified against
    // INDEX_PUBLIC_KEY before any source is exposed; the index itself declares which
    // sources exist. An unsigned/forged index is rejected.
    private val INDEX_PUBLIC_KEY =
        "MCowBQYDK2VwAyEA8pus7Do8tcNQXYqb+sZQZh2XJ70Iz3Zi/iE25USROT0="
    // Remote master switch (OTA). On launch the app fetches this signed config; if
    // it says enabled, sources auto-activate for everyone — so the build can ship
    // with sources off (demo only) for Store review and be enabled remotely later
    // just by re-signing + updating this file. Signature verified with the same key.
    private val REMOTE_CONFIG_URL =
        "https://hasan72341.github.io/nyora-windows-parser/config.json"
    var repositoryActive by mutableStateOf(false)
        private set
    var repositoryUrl    by mutableStateOf("")
        private set
    var repoLoading      by mutableStateOf(false)
        private set
    var repoError        by mutableStateOf<String?>(null)
    private var repositorySourceIds: List<String> = emptyList()

    // ── Explore ───────────────────────────────────────────────────────────────
    var sources          by mutableStateOf<List<MangaSource>>(emptyList())
    var activeSource     by mutableStateOf<MangaSource?>(null)
    var exploreManga     by mutableStateOf<List<Manga>>(emptyList())
    var exploreHasNext   by mutableStateOf(false)
    var explorePage      by mutableStateOf(1)
    var exploreLoading   by mutableStateOf(false)
    var exploreError     by mutableStateOf<String?>(null)
    var searchQuery      by mutableStateOf("")
    var exploreMode      by mutableStateOf(ExploreMode.POPULAR)

    // ── Catalog ───────────────────────────────────────────────────────────────
    var catalogEntries   by mutableStateOf<List<CatalogEntry>>(emptyList())
    var catalogLoading   by mutableStateOf(false)
    var catalogSearch    by mutableStateOf("")
    var catalogLang      by mutableStateOf("all")

    // ── Global Search ─────────────────────────────────────────────────────────
    var globalQuery       by mutableStateOf("")
    var globalResults     by mutableStateOf<List<GlobalSearchGroup>>(emptyList())
    var globalSearching   by mutableStateOf(false)
    var globalSearchError by mutableStateOf<String?>(null)

    // ── Details ───────────────────────────────────────────────────────────────
    var selectedManga   by mutableStateOf<Manga?>(null)
    var detailsLoading  by mutableStateOf(false)
    var detailsError    by mutableStateOf<String?>(null)
    var isFavourited    by mutableStateOf(false)
    var mangaCategories by mutableStateOf<List<CategoryDto>>(emptyList())

    // ── Reader ────────────────────────────────────────────────────────────────
    var readerManga       by mutableStateOf<Manga?>(null)
    var readerChapter     by mutableStateOf<MangaChapter?>(null)
    var readerPages       by mutableStateOf<List<MangaPage>>(emptyList())
    var readerCurrentPage by mutableStateOf(0)
    // Page-index groupings for the paged reader. Each inner list is one on-screen slot:
    // a single page normally, or two pages in landscape double-page mode. Maintained by
    // PagedReader (which knows the window orientation) and read by navigateEdgeAware so a
    // click/key/swipe steps a whole spread. Empty ⇒ single-page ±1 stepping.
    var readerSlots       by mutableStateOf<List<List<Int>>>(emptyList())
    var readerLoading     by mutableStateOf(false)
    var rtlReading        by mutableStateOf(false)
    var readerMode        by mutableStateOf(ReaderMode.PAGED)
    var currentPageBookmarked by mutableStateOf(false)

    // ── Per-manga reader prefs (colour correction + per-manga reader mode) ──────
    // These reflect the prefs of the *currently open* reader manga. They are
    // loaded in openChapter() and saved (debounced) via saveMangaPrefs().
    var readerBrightness  by mutableStateOf(0.0)   // -1.0 .. 1.0 (0 = none)
    var readerContrast    by mutableStateOf(1.0)   // 0 .. 2 (1 = none)
    var readerSaturation  by mutableStateOf(1.0)   // 0 .. 2 (1 = none)
    var readerHue         by mutableStateOf(0.0)   // -180 .. 180 degrees (0 = none)
    var readerPalette     by mutableStateOf("")    // "" = none

    // ── In-image translation (OCR + machine translation, per page index) ────────
    // Translations are keyed by page index within the *current* chapter and are
    // reset whenever the chapter changes. Everything fails soft: when the Windows
    // OCR engine can't be reached it flips [translateUnavailable] and never
    // crashes the reader. [translateLangs] is the OCR *source* language as a
    // BCP-47 tag (e.g. "ja", "zh-Hans", "ko"), matching Windows.Media.Ocr.
    var translateEnabled     by mutableStateOf(false)
    var translateTarget      by mutableStateOf("en")
    var translateLangs       by mutableStateOf("ja")
    var translateBusy        by mutableStateOf(false)
    var translateUnavailable by mutableStateOf(false)
    var pageTranslations     by mutableStateOf<Map<Int, PageTranslation>>(emptyMap())
    // "Fetch series context" — MangaBaka/AniList/Fandom character-name glossary (web engine.js).
    var translateFandom      by mutableStateOf(false)
    private var readerSessionGeneration = 0L
    private var translationGeneration = 0L
    private var nextReaderAiJobId = 0L
    private var chapterTranslateJob: Job? = null
    private val translationPageJobs = mutableMapOf<Int, ReaderAiJob>()

    // ── On-device colorizer (web core/colorize) ─────────────────────────────────
    var colorizeEnabled      by mutableStateOf(false)
    var colorizeBusy         by mutableStateOf(false)
    // reader page index → local file path of the coloured PNG (Coil renders it).
    var colorizedPages       by mutableStateOf<Map<Int, String>>(emptyMap())
    private var colorizeGeneration = 0L
    private val colorizePageJobs = mutableMapOf<Int, ReaderAiJob>()
    private var colorizeToggleCheckInFlight = false
    private var colorizeModelDownloadJob: Job? = null
    private var colorizeModelDownloadGeneration = 0L

    // ── AI refinement (polish the machine-translation draft) ────────────────────
    // Prefers on-device Windows AI (Phi Silica) when available, else a
    // bring-your-own-key OpenAI-compatible endpoint. [windowsAiAvailable] is
    // probed lazily (see refreshWindowsAi) so the Settings UI can show real state.
    var aiMode             by mutableStateOf(AiMode.OFF)
    var byokBaseUrl        by mutableStateOf("https://api.openai.com/v1")
    var byokApiKey         by mutableStateOf("")
    var byokModel          by mutableStateOf("gpt-4o-mini")
    /** Non-secret validation feedback shown beside the custom provider fields. */
    var byokEndpointError  by mutableStateOf<String?>(null)
    var windowsAiAvailable by mutableStateOf<Boolean?>(null) // null = not yet probed

    // ── Library ───────────────────────────────────────────────────────────────
    var favourites  by mutableStateOf<List<Manga>>(emptyList())
    var history     by mutableStateOf<List<HistoryRow>>(emptyList())
    var bookmarks   by mutableStateOf<List<BookmarkRow>>(emptyList())
    var updates     by mutableStateOf<List<UpdateRow>>(emptyList())

    // ── Categories ────────────────────────────────────────────────────────────
    var categories        by mutableStateOf<List<CategoryDto>>(emptyList())
    var activeCategoryId  by mutableStateOf<Long?>(null) // null = All

    // ── Downloads ─────────────────────────────────────────────────────────────
    var downloads       by mutableStateOf<List<DownloadDto>>(emptyList())
    var downloadsPolling by mutableStateOf(false)

    // ── Local Files ───────────────────────────────────────────────────────────
    var localFiles       by mutableStateOf<List<LocalCbzEntry>>(emptyList())
    var localChapter     by mutableStateOf<LocalChapterResponse?>(null)
    var localFolder      by mutableStateOf<String?>(null)

    // ── Settings ──────────────────────────────────────────────────────────────
    var prefetchEnabled by mutableStateOf(true)
    var nsfwFilter      by mutableStateOf(true)
    var noNsfwHistory   by mutableStateOf(false)  // keep 18+ out of reading history
    var showPageNumbers by mutableStateOf(true)

    // ── Settings: Reader ────────────────────────────────────────────────────────
    // Global reader defaults. Persisted via savePrefs(); the per-manga reader
    // prefs (loadMangaPrefs) still override readerMode per title.
    var defaultReaderMode     by mutableStateOf(ReaderMode.PAGED)
    var autoDetectReaderMode  by mutableStateOf(false)
    private var _readerBackground by mutableStateOf("auto") // "auto"|"dark"|"light"
    val readerBackground: String get() = _readerBackground
    var showZoomButtons       by mutableStateOf(true)
    var twoPageLandscape      by mutableStateOf(false)
    var webtoonSidePadding    by mutableStateOf(0.0)
    var autoHideControls      by mutableStateOf(true)

    // Auto-scroll (matches nyora-mac). `autoScrollOn` is a per-session toggle;
    // the 1..10 speed level persists. Webtoon = px/sec, paged = seconds/page.
    var autoScrollOn    by mutableStateOf(false)
    var autoScrollLevel by mutableStateOf(4)
    fun toggleAutoScroll() { autoScrollOn = !autoScrollOn }
    /** Webtoon continuous speed: 24 .. 258 px/sec across levels 1..10. */
    val autoScrollPxPerSec: Double get() = 24 + (autoScrollLevel - 1) * 26.0
    /** Paged auto-advance delay: ~8.7s .. 1.7s across levels 1..10. */
    val autoScrollPagedDelay: Double get() = maxOf(1.5, 9.5 - autoScrollLevel * 0.78)
    var keepScreenOn          by mutableStateOf(false)
    var descriptionCollapse   by mutableStateOf(true)
    var gridSize              by mutableStateOf(160)

    // ── Settings: Library / History ──────────────────────────────────────────────
    var historyRetentionDays  by mutableStateOf(0)   // 0 = keep forever
    var groupHistoryByDate    by mutableStateOf(true)
    var historySortOrder      by mutableStateOf("recent") // "recent"|"alpha"|"added"
    var hideNsfwSources       by mutableStateOf(true)

    // ── Settings: Translation ─────────────────────────────────────────────────────
    var instantTranslate      by mutableStateOf(false)

    // ── Settings: Privacy ──────────────────────────────────────────────────────────
    // incognito has a side-effect on history recording (see openChapter /
    // recordReaderPage) so it is funneled through setIncognito().
    private var _incognito by mutableStateOf(false)
    val incognito: Boolean get() = _incognito
    var confirmBeforeQuit     by mutableStateOf(false)

    // ── Settings: Advanced ──────────────────────────────────────────────────────────
    var appLocale             by mutableStateOf("auto")

    // ── Appearance / accent (persisted to a small JSON file) ────────────────────
    // Backed by private state; mutate via setAppearance()/setAccent() so changes
    // persist. The public getters let composables observe the values.
    private var _appearance by mutableStateOf(AppearanceMode.DARK)
    private var _accent     by mutableStateOf(Accent.SYSTEM)
    val appearance: AppearanceMode get() = _appearance
    val accent: Accent get() = _accent

    // ── Stats / Suggestions / Alternatives ──────────────────────────────────────
    var stats                by mutableStateOf<StatsResponse?>(null)
    var statsLoading         by mutableStateOf(false)
    var suggestions          by mutableStateOf<List<Manga>>(emptyList())
    var suggestionsLoading   by mutableStateOf(false)
    var alternatives         by mutableStateOf<List<Manga>>(emptyList())
    var alternativesLoading  by mutableStateOf(false)

    // ── Network / download settings ─────────────────────────────────────────────
    var networkSettings   by mutableStateOf<NetworkSettingsDto?>(null)
    var downloadSettings  by mutableStateOf<DownloadSettingsDto?>(null)

    // ── Trackers / sync (tokens persisted alongside appearance prefs) ───────────
    // Token backed by private state; mutate via setAnilistToken() to persist.
    private var _anilistToken by mutableStateOf("")
    val anilistToken: String get() = _anilistToken
    var anilistResult   by mutableStateOf<AniListSearchResponse?>(null)
    var anilistLoading  by mutableStateOf(false)
    var syncToken       by mutableStateOf("")
    var syncServerUrl   by mutableStateOf("")
    var cloudSyncStatus by mutableStateOf<SupabaseStatusResponse?>(null)
    var cloudSyncBusy   by mutableStateOf(false)
    // Persistent auth feedback shown inline in the sign-in / create-account panels.
    // Unlike the transient global snackbar (statusMessage), this is NOT auto-cleared
    // and it renders on the first-run Welcome screen, which sits above the snackbar
    // host — so create-account/sign-in outcomes are always visible. Cleared when the
    // user edits a field (see WelcomeScreen / SettingsScreen).
    var authMessage     by mutableStateOf<String?>(null)
    // The email the user signed in with — persisted so Settings can clearly show
    // the account (the self-hosted JWT carries no email claim, so the server
    // status can't supply it).
    var cloudEmail      by mutableStateOf("")
    // Set after auth when this device already has library data — the Welcome screen
    // then asks the user to Merge (push+pull) or Replace (restore-from-cloud), instead
    // of silently merging. Mirrors nyora-mac's "Local Data Detected" dialog.
    var cloudConflictPending by mutableStateOf(false)

    /** First-run start page gate — true until the user finishes onboarding (signs in
     *  or taps "Continue as guest"), tracked by a marker file under the config dir. */
    var showWelcome by mutableStateOf(!File(configDir(), ".onboarded").exists())

    /** When true, the content & language preferences step is re-opened as an
     *  overlay (from Settings ▸ "Re-run setup") so the user can re-pick languages /
     *  the 18+ preference and reseed their sources after onboarding. */
    var showPreferences by mutableStateOf(false)

    // Native window placement — restored on launch, persisted on change so the app
    // remembers its size + maximized/snapped state like a native Windows app.
    private val winProps = readWindowProps()
    var windowWidth     by mutableStateOf(winProps.getProperty("width")?.toIntOrNull() ?: 1280)
    var windowHeight    by mutableStateOf(winProps.getProperty("height")?.toIntOrNull() ?: 800)
    var windowMaximized by mutableStateOf(winProps.getProperty("maximized")?.toBoolean() ?: false)
    var otaStatus       by mutableStateOf<OtaStatusResponse?>(null)
    var otaBusy         by mutableStateOf(false)

    // ── "For You" feed (AniList trending, DIRECT external API) ───────────────────
    var anilistFeed        by mutableStateOf<List<AniListFeedMedia>>(emptyList())
    var anilistFeedLoading by mutableStateOf(false)

    // ── Multi-service trackers (AniList / MyAnimeList / Kitsu / Shikimori) ────────
    // Real OAuth via the shared jvmMain scrobbler abstraction (TS-008/011): a
    // loopback authorization-code flow for AniList/MAL/Shikimori and a password
    // grant for Kitsu. Tokens persist to disk so logins survive an app restart.
    private val scrobblerRepo by lazy { ScrobblerRepository.persistent() }
    /** Slugs of the services the user is currently signed in to. */
    var trackerAuthorized by mutableStateOf<Set<String>>(emptySet())
        private set
    /** Slug of the service whose login / search is in flight (null = idle). */
    var trackerBusy by mutableStateOf<String?>(null)
        private set
    /** Per-service search results, keyed by service slug. */
    var trackerResults by mutableStateOf<Map<String, List<ScrobblerManga>>>(emptyMap())
        private set
    /** The most recent consent URL, surfaced so the user can open it manually if
     *  the browser opened off-screen (multi-display) or is unavailable. */
    var trackerLoginUrl by mutableStateOf<String?>(null)
        private set

    // Per-manga tracker links: mangaId -> (serviceSlug -> remote media id). Persisted
    // to trackerLinks.json. Lets the reader scrobble progress to the right remote entry
    // without re-searching. Mirrors nyora-mac's TrackerSettings link map.
    var trackerLinks by mutableStateOf<Map<String, Map<String, Long>>>(emptyMap())
        private set
    /** Push chapter progress to linked trackers when a chapter is opened (Mac parity). */
    var scrobbleOnRead by mutableStateOf(true)

    @kotlinx.serialization.Serializable
    private data class TrackerLinksDto(val links: Map<String, Map<String, Long>> = emptyMap())

    private fun trackerLinksFile() = File(configDir(), "trackerLinks.json")

    private fun loadTrackerLinks() {
        runCatching {
            val f = trackerLinksFile()
            if (f.exists()) trackerLinks = prefsJson.decodeFromString(TrackerLinksDto.serializer(), f.readText()).links
        }
    }

    private fun saveTrackerLinks() {
        val snapshot = trackerLinks
        scope.launch(Dispatchers.IO) {
            runCatching {
                val d = configDir(); if (!d.exists()) d.mkdirs()
                trackerLinksFile().writeText(prefsJson.encodeToString(TrackerLinksDto.serializer(), TrackerLinksDto(snapshot)))
            }
        }
    }

    /** Remote-id links for [mangaId], keyed by service slug. */
    fun linkedTrackers(mangaId: String): Map<String, Long> = trackerLinks[mangaId].orEmpty()

    /** Link [mangaId] to a remote entry [remoteId] on [slug], and scrobble the current page. */
    fun linkTracker(mangaId: String, slug: String, remoteId: Long) {
        trackerLinks = trackerLinks + (mangaId to (linkedTrackers(mangaId) + (slug to remoteId)))
        saveTrackerLinks()
        showStatus("Tracking linked.")
    }

    fun unlinkTracker(mangaId: String, slug: String) {
        val next = linkedTrackers(mangaId) - slug
        trackerLinks = if (next.isEmpty()) trackerLinks - mangaId else trackerLinks + (mangaId to next)
        saveTrackerLinks()
    }

    /** Push [chapter] as read progress to every tracker linked for [mangaId]. */
    fun scrobbleChapter(mangaId: String, chapter: Int) {
        if (!scrobbleOnRead || chapter <= 0) return
        val links = linkedTrackers(mangaId)
        if (links.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            links.forEach { (slug, remoteId) ->
                val scrobbler = scrobblerRepo.bySlug(slug) ?: return@forEach
                if (!runCatching { scrobbler.isAuthorized }.getOrDefault(false)) return@forEach
                runCatching { scrobbler.updateProgress(remoteId, chapter, ScrobblingStatus.READING, null, null) }
            }
        }
    }

    // ── Status banner ─────────────────────────────────────────────────────────
    var statusMessage by mutableStateOf<String?>(null)

    // Debounce job for saveMangaPrefs()
    private var prefsSaveJob: Job? = null

    init {
        loadPrefs()
        loadTrackerLinks()
        loadSources()
        refreshLibrary()
        checkRemoteActivation()
    }

    /**
     * Fetch the signed remote config and auto-activate sources if it says enabled.
     * Lets a shipped build stay locked (demo only) for Store review, then be enabled
     * for everyone later just by re-signing + updating config.json — no app update.
     * The config is Ed25519-verified against [INDEX_PUBLIC_KEY]; a missing / invalid
     * / disabled config leaves the app locked. Never force-disables an already-active
     * (manually-added) repository.
     */
    private fun checkRemoteActivation() {
        if (repositoryActive) return
        scope.launch {
            val ids: List<String>? = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = java.net.URI(REMOTE_CONFIG_URL).toURL().openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 8_000
                    conn.readTimeout = 8_000
                    conn.setRequestProperty("Accept", "application/json")
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val cfg = prefsJson.parseToJsonElement(text).jsonObject
                    val dataB64 = cfg["data"]?.jsonPrimitive?.content ?: return@runCatching null
                    val sigB64  = cfg["sig"]?.jsonPrimitive?.content ?: return@runCatching null
                    val payloadBytes = java.util.Base64.getDecoder().decode(dataB64)
                    if (!verifyIndexSignature(payloadBytes, sigB64)) return@runCatching null
                    val payload = prefsJson.parseToJsonElement(String(payloadBytes, Charsets.UTF_8)).jsonObject
                    val enabled = payload["enabled"]?.jsonPrimitive?.let {
                        it.content == "true" || it.content == "1"
                    } == true
                    if (!enabled) return@runCatching null
                    (payload["sources"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.content }
                        ?.takeIf { it.isNotEmpty() } ?: listOf("*")
                }.getOrNull()
            }
            if (ids != null && !repositoryActive) {
                repositorySourceIds = ids
                repositoryUrl = REMOTE_CONFIG_URL
                repositoryActive = true
                sourcesGate?.set(true)
                savePrefs()
                loadSources()
                loadCatalog()
            }
        }
    }

    // ── Source management ─────────────────────────────────────────────────────

    fun loadSources() {
        // No repository → no sources. Otherwise expose only the sources the signed
        // index declared ("*" = the full built-in set).
        if (!repositoryActive) { sources = emptyList(); return }
        scope.launch {
            val all = withContext(Dispatchers.IO) { facade.listSources() }
            sources = if (repositorySourceIds.contains("*")) all
                      else all.filter { it.id in repositorySourceIds }
        }
    }

    fun loadCatalog() {
        if (!repositoryActive) { catalogEntries = emptyList(); catalogLoading = false; return }
        catalogLoading = true
        scope.launch {
            runCatching {
                val body = http.get("/sources/catalog")
                val all = http.parse<CatalogResponse>(body).entries
                catalogEntries = if (repositorySourceIds.contains("*")) all
                                 else all.filter { it.id in repositorySourceIds }
            }.onFailure { showStatus("Catalog load failed: ${it.message}") }
            catalogLoading = false
        }
    }

    /**
     * Add a source repository by its index URL. The URL is fetched and must return
     * a signed index — `{"data": <base64 payload>, "sig": <base64 Ed25519 sig>}` —
     * whose signature verifies against [INDEX_PUBLIC_KEY]. The payload declares the
     * available sources (`{"v":1,"sources":[...]}`). Only a correctly-signed index
     * activates sources; an unsigned or tampered index is rejected.
     */
    fun addSourceRepository(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) { repoError = "Enter a repository link."; return }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            repoError = "Enter a valid http(s) link."; return
        }
        repoLoading = true
        repoError = null
        scope.launch {
            val ids: List<String>? = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = java.net.URI(trimmed).toURL().openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 12_000
                    conn.readTimeout = 12_000
                    conn.setRequestProperty("Accept", "application/json")
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val index = prefsJson.parseToJsonElement(text).jsonObject
                    val dataB64 = index["data"]?.jsonPrimitive?.content ?: return@runCatching null
                    val sigB64  = index["sig"]?.jsonPrimitive?.content ?: return@runCatching null
                    val payloadBytes = java.util.Base64.getDecoder().decode(dataB64)
                    if (!verifyIndexSignature(payloadBytes, sigB64)) return@runCatching null
                    val payload = prefsJson.parseToJsonElement(String(payloadBytes, Charsets.UTF_8)).jsonObject
                    (payload["sources"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.content }
                        ?.takeIf { it.isNotEmpty() }
                }.getOrNull()
            }
            repoLoading = false
            if (ids != null) {
                repositorySourceIds = ids
                repositoryUrl = trimmed
                repositoryActive = true
                sourcesGate?.set(true)   // open the helper's source endpoints
                savePrefs()
                loadSources()
                loadCatalog()
                showStatus("Sources enabled.")
            } else {
                repoError = "Couldn't verify that repository link."
            }
        }
    }

    /** Ed25519-verify [sigB64] over [data] using the embedded public key. */
    private fun verifyIndexSignature(data: ByteArray, sigB64: String): Boolean = runCatching {
        val keyBytes = java.util.Base64.getDecoder().decode(INDEX_PUBLIC_KEY)
        val pub = java.security.KeyFactory.getInstance("Ed25519")
            .generatePublic(java.security.spec.X509EncodedKeySpec(keyBytes))
        val sig = java.security.Signature.getInstance("Ed25519")
        sig.initVerify(pub)
        sig.update(data)
        sig.verify(java.util.Base64.getDecoder().decode(sigB64))
    }.getOrDefault(false)

    /** Remove the repository (sources go inactive; the link can be re-added). */
    fun removeSourceRepository() {
        repositoryActive = false
        sourcesGate?.set(false)   // close the helper's source endpoints
        repositorySourceIds = emptyList()
        repositoryUrl = ""
        sources = emptyList()
        catalogEntries = emptyList()
        activeSource = null
        savePrefs()
    }

    fun installSource(id: String) {
        scope.launch {
            runCatching {
                http.post("/sources/install?id=${URLEncoder.encode(id, "UTF-8")}")
                loadSources()
                catalogEntries = catalogEntries.map {
                    if (it.id == id) it.copy(isInstalled = true) else it
                }
                showStatus("Installed.")
            }.onFailure { showStatus("Install failed: ${it.message}") }
        }
    }

    fun uninstallSource(id: String) {
        scope.launch {
            runCatching {
                http.delete("/sources/uninstall?id=${URLEncoder.encode(id, "UTF-8")}")
                loadSources()
                if (activeSource?.id == id) { activeSource = null; exploreManga = emptyList() }
                catalogEntries = catalogEntries.map {
                    if (it.id == id) it.copy(isInstalled = false) else it
                }
                showStatus("Uninstalled.")
            }.onFailure { showStatus("Uninstall failed: ${it.message}") }
        }
    }

    /**
     * Seed the installed shelf from the onboarding / preferences step: install
     * every catalog [ids] entry that isn't already installed, in one coroutine,
     * then refresh once. Best-effort — a failed install is skipped so one bad
     * source can't abort the whole seed. Never leaves the shelf empty because the
     * caller falls back to the 18+-only set when the language filter matches none.
     */
    fun seedSources(ids: List<String>) {
        if (ids.isEmpty()) return
        scope.launch {
            val alreadyInstalled = sources.filter { it.isInstalled }.map { it.id }.toSet()
            val todo = ids.filter { it !in alreadyInstalled }
            withContext(Dispatchers.IO) {
                todo.forEach { id ->
                    runCatching { http.post("/sources/install?id=${URLEncoder.encode(id, "UTF-8")}") }
                }
            }
            loadSources()
            val want = ids.toSet()
            catalogEntries = catalogEntries.map {
                if (it.id in want) it.copy(isInstalled = true) else it
            }
            if (todo.isNotEmpty()) showStatus("Added ${todo.size} source${if (todo.size == 1) "" else "s"}.")
        }
    }

    fun togglePin(id: String) {
        scope.launch {
            runCatching {
                http.post("/sources/pin?id=${URLEncoder.encode(id, "UTF-8")}")
                loadSources()
            }
        }
    }

    fun refreshSources() {
        scope.launch {
            runCatching {
                http.post("/sources/refresh")
                loadSources()
                showStatus("Sources refreshed.")
            }.onFailure { showStatus("Refresh failed: ${it.message}") }
        }
    }

    // ── Browse ────────────────────────────────────────────────────────────────

    // ── Cloudflare (manual clearance) ────────────────────────────────────────────
    // The in-process parser engine throws "Cloudflare challenge: <host>" when a site
    // returns a JS challenge we can't run. We ask the user to paste a cf_clearance
    // cookie from their browser, inject it into the shared OkHttp jar, and re-run the
    // failed operation.

    /** Host of a pending Cloudflare challenge; non-null shows the paste dialog. */
    var cloudflareHost by mutableStateOf<String?>(null)
        private set
    private var cloudflareRetry: (() -> Unit)? = null

    private fun cloudflareHostFromError(t: Throwable): String? =
        generateSequence(t as Throwable?) { it.cause }
            .mapNotNull { it.message }
            .firstOrNull { it.startsWith("Cloudflare challenge: ") }
            ?.removePrefix("Cloudflare challenge: ")
            ?.trim()
            ?.ifBlank { null }

    /** If [t] is a Cloudflare challenge, open the paste dialog (with a [retry]) and return true. */
    private fun handleCloudflare(t: Throwable, retry: () -> Unit): Boolean {
        val host = cloudflareHostFromError(t) ?: return false
        cloudflareHost = host
        cloudflareRetry = retry
        showStatus("Cloudflare challenge on $host — paste cf_clearance to continue")
        return true
    }

    /** Inject the pasted cookie (`cf_clearance=…` or a full Cookie header) and re-run the op. */
    fun applyCloudflareCookie(cookieHeader: String) {
        val host = cloudflareHost ?: return
        val retry = cloudflareRetry
        val cookie = cookieHeader.trim()
        if (cookie.isNotEmpty()) {
            // Allow pasting just the bare value — prepend the cookie name if missing.
            val normalized = if (cookie.contains("=")) cookie else "cf_clearance=$cookie"
            com.nyora.hasan72341.shared.net.injectClearanceCookies(host, normalized)
            showStatus("Applied clearance for $host — retrying")
        }
        dismissCloudflare()
        retry?.invoke()
    }

    fun dismissCloudflare() {
        cloudflareHost = null
        cloudflareRetry = null
    }

    fun browseManga(source: MangaSource, page: Int = 1) {
        activeSource = source
        explorePage = page
        exploreError = null
        exploreLoading = true
        scope.launch {
            runCatching {
                val svc = withContext(Dispatchers.IO) { facade.openExtension(source) }
                val result = withContext(Dispatchers.IO) {
                    when (exploreMode) {
                        ExploreMode.LATEST -> svc.getLatest(page)
                        ExploreMode.SEARCH ->
                            if (searchQuery.isBlank()) svc.getPopular(page) else svc.search(searchQuery, page)
                        ExploreMode.POPULAR -> svc.getPopular(page)
                    }
                }
                exploreManga = if (page == 1) result.entries else exploreManga + result.entries
                exploreHasNext = result.hasNextPage
            }.onFailure { t ->
                if (!handleCloudflare(t) { browseManga(source, page) }) exploreError = t.message
            }
            exploreLoading = false
        }
    }

    // ── Global search ─────────────────────────────────────────────────────────

    // In-flight global-search coroutine; cancelled when a new query starts so a
    // slow source from a previous query cannot append stale groups.
    private var globalSearchJob: Job? = null

    fun globalSearch(query: String) {
        if (query.isBlank()) return
        // Cancel any previous in-flight search so its slow sources cannot append
        // stale groups after this query has reset the list.
        globalSearchJob?.cancel()
        globalQuery = query
        globalResults = emptyList()
        globalSearching = true
        globalSearchError = null

        // STREAMING global search: instead of one /search/global REST call that
        // does awaitAll() server-side (all-or-nothing — the UI showed a single
        // spinner until EVERY source finished), fan out across installed sources
        // here and append each source's GlobalSearchGroup the moment it returns.
        // The GlobalSearchScreen list is keyed by sourceId, so each group renders
        // as it arrives; per-source covers then load incrementally via the /image
        // proxy + Coil (already concurrent, not gated behind the JS lock).
        //
        // Prerequisite: the GraalVM single-thread-confinement fix in
        // JavaScriptExtensionService — different sources run their JS in parallel
        // on their own owning threads, so this concurrent fan-out is now safe.
        val targets = sources.filter { it.isInstalled }
        if (targets.isEmpty()) {
            globalSearching = false
            return
        }
        // Bound parallelism (mirrors the proxy's gate pattern at a desktop-suited
        // bound) so we don't open a connection per source all at once.
        val gate = Semaphore(8)
        val perSourceLimit = 5

        globalSearchJob = scope.launch {
            coroutineScope {
                targets.forEach { src ->
                    launch {
                        val group = gate.withPermit {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    // Per-source timeout (mirrors the server's 15s cap)
                                    // so one hung source can't stall the rest.
                                    withTimeoutOrNull(15_000L) {
                                        val svc = facade.openExtension(src)
                                        val page = svc.search(query, 1)
                                        GlobalSearchGroup(
                                            sourceId = src.id,
                                            sourceName = src.name,
                                            entries = page.entries.take(perSourceLimit),
                                            error = null,
                                        )
                                    }
                                }.getOrNull()
                            }
                        }
                        // Append on the Main dispatcher (Compose snapshot state).
                        // Only surface sources that returned hits, matching the old
                        // server behaviour. Reassign the list (not in-place mutate)
                        // so Compose observes the change.
                        if (group != null && group.entries.isNotEmpty()) {
                            globalResults = globalResults + group
                        }
                        // Drop the all-sources spinner as soon as the first source
                        // settles, so results stream in rather than blocking on all.
                        globalSearching = false
                    }
                }
            }
            // All sources settled. Ensure the spinner is cleared even if every
            // source returned empty/failed; GlobalSearchScreen shows its "no
            // results" empty state when the list is empty and the query non-blank.
            globalSearching = false
        }
    }

    // ── Details ───────────────────────────────────────────────────────────────

    fun resumeManga(manga: Manga, source: MangaSource? = null, chapterId: String? = null) {
        // BUGFIX: opening from History/Updates/Bookmarks may pass a null/stale
        // source; resolve from the installed sources by the manga's source ref
        // so resume never silently no-ops.
        val src = source ?: sourceFor(manga) ?: run {
            showStatus("Source not installed"); return
        }
        detailsLoading = true
        scope.launch {
            runCatching {
                val svc = withContext(Dispatchers.IO) { facade.openExtension(src) }
                val details = withContext(Dispatchers.IO) { svc.getDetails(manga.url) }
                val full = details.manga.copy(chapters = details.chapters)
                withContext(Dispatchers.IO) { facade.upsertManga(full) }

                val chapter = if (chapterId != null) {
                    full.chapters.firstOrNull { it.id == chapterId }
                } else {
                    full.chapters.firstOrNull() // Latest
                }

                if (chapter != null) {
                    openChapter(full, chapter, src)
                } else {
                    // Fallback to details if chapter not found
                    selectedManga = full
                    showDetails = true
                }
            }.onFailure { t ->
                if (!handleCloudflare(t) { resumeManga(manga, src, chapterId) }) {
                    showStatus("Failed to resume: ${t.message}")
                    openDetails(manga, src)
                }
            }
            detailsLoading = false
        }
    }

    fun openDetails(manga: Manga, source: MangaSource? = null) {
        // BUGFIX: resolve a fallback source so opening details from
        // History/Updates/Bookmarks (stale/null activeSource) still works.
        val src = source ?: sourceFor(manga) ?: run {
            showStatus("Source not installed"); return
        }
        activeSource = src
        selectedManga = manga
        showDetails = true
        detailsLoading = true
        detailsError = null
        scope.launch {
            isFavourited = withContext(Dispatchers.IO) { facade.isFavourited(manga.id) }
            mangaCategories = loadCategoriesForManga(manga.id)
            runCatching {
                val svc = withContext(Dispatchers.IO) { facade.openExtension(src) }
                val details: MangaDetails = withContext(Dispatchers.IO) { svc.getDetails(manga.url) }
                // Asura (and some parsers) return no cover from getDetails — keep the
                // list/grid cover the user clicked so the details hero doesn't go blank.
                val full = details.manga.copy(
                    chapters = details.chapters,
                    coverUrl = details.manga.coverUrl.ifBlank { manga.coverUrl },
                )
                withContext(Dispatchers.IO) { facade.upsertManga(full) }
                selectedManga = full
            }.onFailure { t ->
                if (!handleCloudflare(t) { openDetails(manga, src) }) detailsError = t.message
            }
            detailsLoading = false
        }
    }

    // ── Reader ────────────────────────────────────────────────────────────────

    fun openChapter(manga: Manga, chapter: MangaChapter, source: MangaSource? = null) {
        val src = source ?: sourceFor(manga) ?: run {
            showStatus("Source not installed"); return
        }
        // Invalidate all reader-AI work before changing the chapter identity.
        // A completed OCR/colorizer task may otherwise attach an old page result
        // to the same index in the newly opened chapter.
        beginReaderAiSession()
        activeSource = src
        readerManga = manga
        readerChapter = chapter
        readerPages = emptyList()
        readerCurrentPage = 0
        readerLoading = true
        showReader = true
        // Translation/colorizer toggles remain enabled across chapters, while the
        // per-page results were cleared above with their in-flight work.
        // Apply any persisted per-manga reader prefs (mode + colour correction)
        // before the pages load so the reader paints with the right look.
        loadMangaPrefs(manga.id)
        // Scrobble this chapter's progress to any linked trackers (Mac parity).
        runCatching { scrobbleChapter(manga.id, chapter.number.toInt()) }
        scope.launch {
            // Restore last position
            val saved = withContext(Dispatchers.IO) {
                facade.history(200).firstOrNull { it.manga.id == manga.id && it.chapterId == chapter.id }
            }
            readerCurrentPage = saved?.page ?: 0
            runCatching {
                val cached = withContext(Dispatchers.IO) { facade.cachedPages(chapter.url) }
                val pages = if (cached != null) cached else {
                    val svc = withContext(Dispatchers.IO) { facade.openExtension(src) }
                    val loaded = withContext(Dispatchers.IO) { svc.getPageList(chapter) }
                    facade.cachePages(chapter.url, manga.id, loaded)
                    loaded
                }
                readerPages = pages
                readerLoading = false
                checkPageBookmark()
                // Best-effort prefetch of the following chapter.
                prefetchNextChapter()
            }.onFailure { t ->
                readerLoading = false; checkPageBookmark()
                handleCloudflare(t) { openChapter(manga, chapter, src) }
            }
        }
        if (!incognito && !(noNsfwHistory && (manga.isNsfw || src.isNsfw))) {
            scope.launch(Dispatchers.IO) {
                facade.recordHistory(manga.id, src.id, chapter.id, chapter.title, 0, 0f)
            }
        }
    }

    /**
     * Loads the per-manga reader prefs from the backend and applies [readerMode]
     * plus the colour-correction filter values. Falls back to global defaults
     * when no prefs row exists (present = false) or on any failure.
     */
    private fun loadMangaPrefs(mangaId: String) {
        scope.launch {
            runCatching {
                val body = http.get("/manga/prefs?mangaId=${URLEncoder.encode(mangaId, "UTF-8")}")
                val dto = http.parse<MangaPrefsDto>(body)
                if (dto.present) {
                    readerBrightness = dto.brightness
                    readerContrast   = dto.contrast
                    readerSaturation = dto.saturation
                    readerHue        = dto.hue
                    readerPalette    = dto.palette
                    // Apply per-manga reader mode if it was set; otherwise keep
                    // whatever the global default already is.
                    parseReaderMode(dto.readerMode)?.let { readerMode = it }
                } else {
                    // No saved prefs: reset colour correction to neutral. Leave
                    // readerMode at the current global default.
                    readerBrightness = 0.0
                    readerContrast = 1.0
                    readerSaturation = 1.0
                    readerHue = 0.0
                    readerPalette = ""
                }
            }
        }
    }

    private fun parseReaderMode(raw: String): ReaderMode? = when (raw.uppercase()) {
        "PAGED" -> ReaderMode.PAGED
        "WEBTOON" -> ReaderMode.WEBTOON
        "VERTICAL" -> ReaderMode.VERTICAL
        else -> null
    }

    /**
     * Persists the current reader manga's prefs (reader mode + colour correction).
     * Debounced ~600ms so dragging sliders does not spam the backend.
     */
    fun saveMangaPrefs() {
        val mangaId = readerManga?.id ?: return
        prefsSaveJob?.cancel()
        prefsSaveJob = scope.launch {
            delay(600)
            runCatching {
                val params = buildString {
                    append("mangaId=${URLEncoder.encode(mangaId, "UTF-8")}")
                    append("&readerMode=${URLEncoder.encode(readerMode.name, "UTF-8")}")
                    append("&brightness=$readerBrightness")
                    append("&contrast=$readerContrast")
                    append("&saturation=$readerSaturation")
                    append("&hue=$readerHue")
                    append("&palette=${URLEncoder.encode(readerPalette, "UTF-8")}")
                }
                http.post("/manga/prefs/save?$params")
            }.onFailure { showStatus("Failed to save reader prefs: ${it.message}") }
        }
    }

    /**
     * Best-effort warm of the chapter that follows [readerChapter] in
     * [readerManga].chapters. Runs on IO and swallows all errors. No-op unless
     * [prefetchEnabled].
     */
    fun prefetchNextChapter() {
        if (!prefetchEnabled) return
        val manga = readerManga ?: return
        val current = readerChapter ?: return
        val src = activeSource ?: sourceFor(manga) ?: return
        val chapters = manga.chapters
        val idx = chapters.indexOfFirst { it.id == current.id }
        if (idx < 0) return
        // "Next" respects the source's chapter ordering (see chapterNextDelta).
        val step = if (chapters.size < 2) 1 else {
            val a = chapters.first().number; val b = chapters.last().number
            if (a != b && a >= b) -1 else 1
        }
        val nextIdx = idx + step
        if (nextIdx !in chapters.indices) return
        val next = chapters[nextIdx]
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (facade.cachedPages(next.url) == null) {
                    val svc = facade.openExtension(src)
                    val loaded = svc.getPageList(next)
                    facade.cachePages(next.url, manga.id, loaded)
                }
            }
        }
    }

    fun recordReaderPage(page: Int) {
        readerCurrentPage = page
        checkPageBookmark()
        // Kick off translation / colorization of the new page on demand when enabled.
        if (translateEnabled) translatePage(page)
        if (colorizeEnabled) colorizePage(page)
        if (incognito) return
        val m = readerManga ?: return
        val c = readerChapter ?: return
        val percent = if (readerPages.isEmpty()) 0f else page.toFloat() / readerPages.size
        if (noNsfwHistory && (m.isNsfw || activeSource?.isNsfw == true)) return
        scope.launch(Dispatchers.IO) {
            facade.recordHistory(m.id, activeSource?.id ?: "", c.id, c.title, page, percent)
        }
    }

    fun togglePageBookmark() {
        val m = readerManga ?: return
        val c = readerChapter ?: return
        scope.launch(Dispatchers.IO) {
            if (facade.isPageBookmarked(m.id, c.id, readerCurrentPage)) {
                facade.removeBookmarkForPage(m.id, c.id, readerCurrentPage)
            } else {
                facade.addBookmark(m.id, c.id, c.title, readerCurrentPage, "")
            }
            withContext(Dispatchers.Main) {
                checkPageBookmark()
                bookmarks = facade.bookmarks()
            }
        }
    }

    private fun checkPageBookmark() {
        val m = readerManga ?: return
        val c = readerChapter ?: return
        scope.launch(Dispatchers.IO) {
            val isMarked = facade.isPageBookmarked(m.id, c.id, readerCurrentPage)
            withContext(Dispatchers.Main) { currentPageBookmarked = isMarked }
        }
    }

    private fun isCurrentReaderPage(
        session: Long,
        chapterId: String,
        index: Int,
        page: MangaPage,
    ): Boolean =
        readerSessionGeneration == session &&
            readerChapter?.id == chapterId &&
            readerPages.getOrNull(index) == page

    /** Cancel old chapter work before replacing reader identity or closing it. */
    private fun beginReaderAiSession() {
        readerSessionGeneration++
        invalidateTranslationWork(clearCachedPages = true)
        invalidateColorizeWork(clearCachedPages = true)
    }

    /**
     * Close the reader through one path so queued OCR/colorizer work cannot keep
     * downloading, inferencing, or publish a result after the reader is gone.
     */
    fun closeReader() {
        if (!showReader) return
        beginReaderAiSession()
        showReader = false
    }

    private fun invalidateTranslationWork(clearCachedPages: Boolean) {
        translationGeneration++
        chapterTranslateJob?.cancel()
        chapterTranslateJob = null
        translationPageJobs.values.forEach { it.job.cancel() }
        translationPageJobs.clear()
        translateBusy = false
        if (clearCachedPages) pageTranslations = emptyMap()
    }

    private fun invalidateColorizeWork(clearCachedPages: Boolean) {
        colorizeGeneration++
        colorizePageJobs.values.forEach { it.job.cancel() }
        colorizePageJobs.clear()
        colorizeBusy = false
        if (clearCachedPages) {
            colorizedPages = emptyMap()
            translator.clearColorizedPageCache()
        }
    }

    // ── In-image translation ────────────────────────────────────────────────────
    //
    // OCR + machine translation of a single reader page. Results are cached by
    // page index for the current chapter (see [pageTranslations]) so re-visiting
    // a page is instant. Everything fails soft: any error is swallowed and the
    // reader keeps working untranslated.

    /**
     * OCR-translate the page at [index] (a position within [readerPages]) and
     * store the result in [pageTranslations]. No-op when the index is out of
     * range or already translated. If the Windows OCR engine can't be reached it
     * flips [translateUnavailable] and surfaces a hint instead of crashing.
     */
    fun translatePage(index: Int) {
        val page = readerPages.getOrNull(index) ?: return
        val chapterId = readerChapter?.id ?: return
        if (pageTranslations.containsKey(index)) return
        if (translationPageJobs[index]?.job?.isActive == true) return

        val readerSession = readerSessionGeneration
        val translationSession = translationGeneration
        val source = translateLangs
        val target = translateTarget
        val fandom = translateFandom
        val title = selectedManga?.title.orEmpty()
        // A saved/session key must not silently opt a reader into remote AI while
        // the user selected "Off" or on-device Windows AI. Invalid endpoints are
        // also excluded here, in addition to the client-side policy check.
        val refineCfg = byokApiKey.takeIf {
            aiMode == AiMode.BYOK && it.isNotBlank() && byokEndpointError == null
        }?.let { apiKey ->
            com.nyora.windows.ai.onnx.MangaMt.RefineCfg(
                provider = if (byokBaseUrl.contains("anthropic", true) || byokModel.startsWith("claude", true)) "anthropic" else "openai",
                endpoint = byokBaseUrl,
                apiKey = apiKey,
                model = byokModel,
                context = null,
            )
        }
        val requestUrl = proxyUrl(page)
        val jobId = ++nextReaderAiJobId
        translateBusy = true
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val res = withContext(Dispatchers.IO) {
                    translator.translatePageImageOnnx(
                        requestUrl, source, target, refineCfg, title, fandom,
                    )
                }
                if (
                    !translateEnabled ||
                    translationGeneration != translationSession ||
                    !isCurrentReaderPage(readerSession, chapterId, index, page)
                ) return@launch
                if (!res.ocrAvailable) {
                    translateUnavailable = true
                    showStatus("Translation models not downloaded — get them in Settings ▸ Translation")
                } else {
                    pageTranslations = pageTranslations + (index to res)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // OCR/translation is optional. Keep the reader usable on failures.
            } finally {
                if (translationPageJobs[index]?.id == jobId) {
                    translationPageJobs.remove(index)
                    translateBusy = translationPageJobs.isNotEmpty()
                }
            }
        }
        translationPageJobs[index] = ReaderAiJob(jobId, job)
        job.start()
    }

    /** Flips translation on/off; translates the current page when turning on. */
    fun toggleTranslate() {
        translateEnabled = !translateEnabled
        if (translateEnabled) translateWholeChapter() else invalidateTranslationWork(clearCachedPages = false)
    }

    /**
     * Proactively OCR-translate every page of the open chapter in the background
     * (current page first, one at a time to stay light) so flipping through a
     * translated chapter is instant — parity with nyora-mac ChapterTranslator.
     * Reuses [translatePage]'s per-index cache, so nothing is translated twice.
     */
    fun translateWholeChapter() {
        chapterTranslateJob?.cancel()
        val total = readerPages.size
        if (total == 0) return
        val start = readerCurrentPage.coerceIn(0, total - 1)
        val order = (start until total).toList() + (0 until start).toList()
        val translationSession = translationGeneration
        val readerSession = readerSessionGeneration
        chapterTranslateJob = scope.launch {
            for (idx in order) {
                if (!translateEnabled || translationGeneration != translationSession || readerSessionGeneration != readerSession) break
                if (pageTranslations.containsKey(idx)) continue
                translatePage(idx)
                var waited = 0
                while (
                    !pageTranslations.containsKey(idx) &&
                    translateEnabled &&
                    translationGeneration == translationSession &&
                    readerSessionGeneration == readerSession &&
                    waited < 120
                ) {
                    delay(250); waited++
                }
            }
        }
    }

    /**
     * Flips the on-device colorizer on/off. Enabling is intentionally model-gated:
     * the reader never starts an implicit 62 MB download just because a toggle was
     * tapped. The SHA-256 check runs off the UI thread because it reads the model.
     */
    fun toggleColorize() {
        if (colorizeEnabled) {
            colorizeEnabled = false
            invalidateColorizeWork(clearCachedPages = false)
            return
        }
        if (colorizeToggleCheckInFlight) return
        colorizeToggleCheckInFlight = true
        scope.launch {
            val ready = withContext(Dispatchers.IO) {
                runCatching { com.nyora.windows.ai.onnx.OnnxColorizer.isReady() }.getOrDefault(false)
            }
            colorizeToggleCheckInFlight = false
            onnxColorizeReady = ready
            if (!ready) {
                showStatus("Download the Colorizer model in Settings ▸ Colorize before enabling it.")
                return@launch
            }
            colorizeEnabled = true
            colorizePage(readerCurrentPage)
        }
    }

    /** Colorize the page at [index] and cache the resulting PNG path (web core/colorize). */
    fun colorizePage(index: Int) {
        val page = readerPages.getOrNull(index) ?: return
        val chapterId = readerChapter?.id ?: return
        colorizedPages[index]?.let { path ->
            if (translator.hasColorizedPage(path)) return
            colorizedPages = colorizedPages - index
        }
        if (colorizePageJobs[index]?.job?.isActive == true) return

        val readerSession = readerSessionGeneration
        val colorizeSession = colorizeGeneration
        val requestUrl = proxyUrl(page)
        val jobId = ++nextReaderAiJobId
        colorizeBusy = true
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withContext(Dispatchers.IO) {
                    translator.colorizePageImage(requestUrl)
                }
                if (
                    !colorizeEnabled ||
                    colorizeGeneration != colorizeSession ||
                    !isCurrentReaderPage(readerSession, chapterId, index, page)
                ) {
                    (result as? ColorizePageResult.Success)?.let { translator.discardColorizedPage(it.path) }
                    return@launch
                }
                when (result) {
                    is ColorizePageResult.Success -> {
                        colorizedPages = colorizedPages + (index to result.path)
                    }
                    ColorizePageResult.ModelUnavailable -> {
                        // The model can be removed between the toggle's gate and
                        // the actual page job; leave the original page visible.
                        colorizeEnabled = false
                        onnxColorizeReady = false
                        invalidateColorizeWork(clearCachedPages = false)
                        showStatus("Colorizer model is unavailable — download it in Settings ▸ Colorize.")
                    }
                    ColorizePageResult.InputTooLarge -> {
                        showStatus("This page is too large or too tall to colorize safely.")
                    }
                    ColorizePageResult.Failed -> {
                        showStatus("Couldn’t colorize this page; the original is still shown.")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Colorization is optional; retain the original page on failures.
            } finally {
                if (colorizePageJobs[index]?.id == jobId) {
                    colorizePageJobs.remove(index)
                    colorizeBusy = colorizePageJobs.isNotEmpty()
                }
            }
        }
        colorizePageJobs[index] = ReaderAiJob(jobId, job)
        job.start()
    }

    /** Coil model for a reader page: the coloured PNG when colorize is on and ready,
     *  else the normal header-injecting image-proxy URL. */
    fun pageDisplayModel(index: Int): Any? =
        if (colorizeEnabled) {
            colorizedPages[index]
                ?.takeIf { translator.hasColorizedPage(it) }
                ?.let(::File)
                ?: proxyUrl(readerPages[index])
        }
        else proxyUrl(readerPages[index])

    // ── On-device ONNX model management (translation vision stack + colorizer) ───
    var onnxTranslateReady by mutableStateOf(false)
    var onnxColorizeReady  by mutableStateOf(false)
    var onnxDownloadLabel  by mutableStateOf<String?>(null) // e.g. "Colorizer 42%" while downloading

    private fun onnxSrc(s: String) = when {
        s.startsWith("zh") -> "zh"; s.startsWith("ko") -> "ko"; s.startsWith("en") -> "en"; else -> "ja"
    }

    /** Refresh the "models downloaded?" flags the Settings/reader gates read. */
    fun refreshOnnxReady() {
        scope.launch {
            val src = onnxSrc(translateLangs)
            val (translateReady, colorizeReady) = withContext(Dispatchers.IO) {
                val ocr = if (src == "ja") com.nyora.windows.ai.onnx.MangaOcr.isReady()
                          else com.nyora.windows.ai.onnx.PaddleOcr.isReady(src)
                (com.nyora.windows.ai.onnx.OnnxDetector.isReady() && ocr) to
                    com.nyora.windows.ai.onnx.OnnxColorizer.isReady()
            }
            onnxTranslateReady = translateReady
            onnxColorizeReady = colorizeReady
        }
    }

    /** Download the colorizer model (~62 MB) with progress. */
    fun downloadColorizeModel() {
        if (colorizeModelDownloadJob?.isActive == true) return
        val generation = ++colorizeModelDownloadGeneration
        colorizeModelDownloadJob = scope.launch {
            onnxDownloadLabel = "Colorizer 0%"
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    com.nyora.windows.ai.onnx.OnnxColorizer.downloadModel { progress ->
                        // ONNX download callbacks run on an IO thread; publish
                        // Compose state only through the AppState main scope.
                        scope.launch {
                            if (generation == colorizeModelDownloadGeneration) {
                                onnxDownloadLabel = "Colorizer $progress%"
                            }
                        }
                    }
                }.exceptionOrNull()
            }
            if (failure != null) showStatus("Colorizer download failed: ${failure.message}")
            if (generation == colorizeModelDownloadGeneration) {
                // Invalidate any callback already queued on the main dispatcher
                // before clearing the label, so it cannot resurrect stale progress.
                onnxDownloadLabel = null
                colorizeModelDownloadGeneration++
            }
            refreshOnnxReady()
        }
    }

    /** Download the translation vision models (detector + the OCR for the source language). */
    fun downloadTranslateModels() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                com.nyora.windows.ai.onnx.OnnxDetector.downloadModel { onnxDownloadLabel = "Bubble detector $it%" }
                val src = onnxSrc(translateLangs)
                if (src == "ja") com.nyora.windows.ai.onnx.MangaOcr.downloadModels { onnxDownloadLabel = "Japanese OCR $it%" }
                else com.nyora.windows.ai.onnx.PaddleOcr.downloadModels(src) { onnxDownloadLabel = "OCR ($src) $it%" }
            }.onFailure { showStatus("Model download failed: ${it.message}") }
            onnxDownloadLabel = null
            refreshOnnxReady()
        }
    }

    /**
     * Switches the target language. Clears cached translations (they were in the
     * old language) and, when translation is active, re-translates the current
     * page in the new language.
     */
    fun changeTranslateTarget(t: String) {
        if (t == translateTarget) return
        translateTarget = t
        invalidateTranslationWork(clearCachedPages = true)
        if (translateEnabled) translatePage(readerCurrentPage)
    }

    /**
     * Switches the OCR *source* language (BCP-47 tag for Windows.Media.Ocr, e.g.
     * "ja", "zh-Hans", "ko", "ar"). The page's text language must match (and the
     * matching Windows OCR language pack must be installed) or OCR produces
     * garbage. Clears cached translations and re-OCRs the current page.
     */
    fun changeTranslateLangs(langs: String) {
        if (langs == translateLangs) return
        translateLangs = langs
        invalidateTranslationWork(clearCachedPages = true)
        if (translateEnabled) translatePage(readerCurrentPage)
    }

    // ── AI refinement settings ──────────────────────────────────────────────────

    /** Probe whether on-device Windows AI (Phi Silica) is usable; updates
     *  [windowsAiAvailable] for the Settings UI. Cheap after the first call. */
    fun refreshWindowsAi() {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { WindowsAiRefiner.isAvailable() }.getOrDefault(false) }
            windowsAiAvailable = ok
        }
    }

    fun changeAiMode(mode: AiMode) {
        aiMode = mode
        if (mode == AiMode.WINDOWS && windowsAiAvailable == null) refreshWindowsAi()
        savePrefs()
    }

    fun setByok(baseUrl: String, apiKey: String, model: String) {
        byokBaseUrl = baseUrl.trim()
        byokEndpointError = AiEndpointPolicy.validationError(byokBaseUrl)
        // Secrets deliberately live only in memory for this app session. Endpoint
        // and model preferences remain persisted below; savePrefs never serializes
        // this value.
        byokApiKey = apiKey
        byokModel = model
        invalidateTranslationWork(clearCachedPages = true)
        savePrefs()
    }

    fun downloadChapter(manga: Manga, chapter: MangaChapter, source: MangaSource) {
        scope.launch {
            runCatching {
                val params = buildString {
                    append("sourceId=${URLEncoder.encode(source.id, "UTF-8")}")
                    append("&mangaUrl=${URLEncoder.encode(manga.url, "UTF-8")}")
                    append("&chapterUrl=${URLEncoder.encode(chapter.url, "UTF-8")}")
                    append("&mangaTitle=${URLEncoder.encode(manga.title, "UTF-8")}")
                    append("&chapterTitle=${URLEncoder.encode(chapter.title, "UTF-8")}")
                }
                val body = http.post("/downloads/start?$params")
                val resp = http.parse<DownloadResponse>(body)
                downloads = listOf(resp.entry) + downloads
                destination = NavDest.DOWNLOADS
                startPollingDownloads()
                showStatus("Download started.")
            }.onFailure { showStatus("Download failed: ${it.message}") }
        }
    }

    fun downloadChapters(source: MangaSource, manga: Manga, chapters: List<MangaChapter>) {
        if (chapters.isEmpty()) return
        scope.launch {
            runCatching {
                val params = buildString {
                    append("sourceId=${URLEncoder.encode(source.id, "UTF-8")}")
                    append("&mangaUrl=${URLEncoder.encode(manga.url, "UTF-8")}")
                    append("&mangaTitle=${URLEncoder.encode(manga.title, "UTF-8")}")
                }
                val chapterData = chapters.map { mapOf("url" to it.url, "title" to it.title) }
                val jsonBody = Json.encodeToString(chapterData)
                http.post("/downloads/enqueue?$params", body = jsonBody)
                startPollingDownloads()
                destination = NavDest.DOWNLOADS
                showStatus("Enqueued ${chapters.size} chapters.")
            }.onFailure { showStatus("Enqueue failed: ${it.message}") }
        }
    }

    // ── Favourites & categories ───────────────────────────────────────────────

    fun toggleFavourite() {
        val manga = selectedManga ?: return
        scope.launch(Dispatchers.IO) {
            isFavourited = facade.toggleFavourite(manga.id)
            favourites = facade.favourites()
        }
    }

    fun loadCategories() {
        scope.launch(Dispatchers.IO) {
            categories = loadCategoryList()
        }
    }

    private suspend fun loadCategoryList(): List<CategoryDto> {
        return runCatching {
            val body = http.get("/library/categories")
            http.parse<CategoriesResponse>(body).categories
        }.getOrDefault(emptyList())
    }

    private suspend fun loadCategoriesForManga(mangaId: String): List<CategoryDto> {
        return runCatching {
            val body = http.get("/library/categories/manga?mangaId=${URLEncoder.encode(mangaId, "UTF-8")}")
            http.parse<CategoriesResponse>(body).categories
        }.getOrDefault(emptyList())
    }

    fun createCategory(title: String) {
        scope.launch {
            runCatching {
                http.post("/library/categories/create?title=${URLEncoder.encode(title, "UTF-8")}")
                categories = loadCategoryList()
            }.onFailure { showStatus("Failed: ${it.message}") }
        }
    }

    fun renameCategory(id: Long, title: String) {
        scope.launch {
            runCatching {
                http.post("/library/categories/rename?id=$id&title=${URLEncoder.encode(title, "UTF-8")}")
                categories = loadCategoryList()
            }.onFailure { showStatus("Rename failed: ${it.message}") }
        }
    }

    fun deleteCategory(id: Long) {
        scope.launch {
            runCatching {
                http.post("/library/categories/delete?id=$id")
                if (activeCategoryId == id) activeCategoryId = null
                categories = loadCategoryList()
            }
        }
    }

    fun addMangaToCategory(mangaId: String, categoryId: Long) {
        scope.launch {
            runCatching {
                http.post("/library/categories/add?mangaId=${URLEncoder.encode(mangaId, "UTF-8")}&categoryId=$categoryId")
                mangaCategories = loadCategoriesForManga(mangaId)
            }
        }
    }

    fun removeMangaFromCategory(mangaId: String, categoryId: Long) {
        scope.launch {
            runCatching {
                http.post("/library/categories/remove?mangaId=${URLEncoder.encode(mangaId, "UTF-8")}&categoryId=$categoryId")
                mangaCategories = loadCategoriesForManga(mangaId)
                if (activeCategoryId == categoryId) favourites = facade.favourites()
            }
        }
    }

    // ── Library refresh ───────────────────────────────────────────────────────

    fun refreshLibrary() {
        scope.launch(Dispatchers.IO) {
            favourites = facade.favourites()
            history    = facade.history(100)
            bookmarks  = facade.bookmarks()
            updates    = facade.updates()
            withContext(Dispatchers.Main) { loadCategories() }
        }
    }

    fun refreshUpdates() {
        scope.launch {
            // Check both favourites AND recently-read history (deduped) so updates
            // surface for titles the user reads without explicitly favouriting.
            // Each title is wrapped in runCatching so one dead source/extension
            // can't abort the whole sweep.
            val candidates = withContext(Dispatchers.IO) {
                (facade.favourites() + facade.history(200).map { it.manga })
                    .distinctBy { it.id }
                    .take(300)
            }
            candidates.forEach { manga ->
                runCatching {
                    val src = sourceFor(manga) ?: return@forEach
                    val svc = withContext(Dispatchers.IO) { facade.openExtension(src) }
                    val details = withContext(Dispatchers.IO) { svc.getDetails(manga.url) }
                    facade.recordUpdateSync(manga.id, src.id, details.chapters.size, details.chapters.firstOrNull()?.title ?: "")
                }
            }
            updates = withContext(Dispatchers.IO) { facade.updates() }
            showStatus("Updates checked.")
        }
    }

    /** Resolves a Manga by id from the in-memory library (favourites first,
     *  then history). Used by screens that only hold an id (e.g. updates). */
    fun mangaById(id: String): Manga? =
        favourites.firstOrNull { it.id == id } ?: history.firstOrNull { it.manga.id == id }?.manga

    fun markUpdateSeen(mangaId: String) {
        scope.launch(Dispatchers.IO) {
            facade.markUpdatesSeen(mangaId)
            updates = facade.updates()
        }
    }

    fun markAllUpdatesSeen() {
        scope.launch(Dispatchers.IO) {
            facade.markAllUpdatesSeen()
            updates = facade.updates()
        }
    }

    fun removeBookmark(id: Long) {
        scope.launch(Dispatchers.IO) {
            facade.removeBookmark(id)
            bookmarks = facade.bookmarks()
        }
    }

    // ── Downloads ─────────────────────────────────────────────────────────────

    fun loadDownloads() {
        scope.launch {
            runCatching {
                val body = http.get("/downloads")
                downloads = http.parse<DownloadsResponse>(body).entries
            }
        }
    }

    fun cancelDownload(id: String) {
        scope.launch {
            runCatching {
                http.post("/downloads/cancel?id=${URLEncoder.encode(id, "UTF-8")}")
                loadDownloads()
            }
        }
    }

    /** Purge finished (completed/failed/cancelled) rows server-side, then refresh. */
    fun clearFinishedDownloads() {
        scope.launch {
            runCatching {
                http.post("/downloads/clear")
                loadDownloads()
            }
        }
    }

    private fun startPollingDownloads() {
        if (downloadsPolling) return
        downloadsPolling = true
        scope.launch {
            while (downloadsPolling) {
                loadDownloads()
                val active = downloads.any { it.status == "QUEUED" || it.status == "RUNNING" }
                if (!active) { downloadsPolling = false; break }
                delay(1500)
            }
        }
    }

    // ── Local files ───────────────────────────────────────────────────────────

    fun scanLocalFolder(folder: String) {
        localFolder = folder
        scope.launch {
            runCatching {
                val encoded = URLEncoder.encode(folder, "UTF-8")
                val body = http.get("/local/scan?folder=$encoded")
                localFiles = http.parse<LocalScanResponse>(body).entries
            }.onFailure { showStatus("Scan failed: ${it.message}") }
        }
    }

    fun openLocalCbz(path: String) {
        scope.launch {
            runCatching {
                val encoded = URLEncoder.encode(path, "UTF-8")
                val body = http.get("/local/chapter?cbz=$encoded")
                val info = http.parse<LocalChapterResponse>(body)
                localChapter = info
                // Open as reader with synthetic pages
                val pages = info.pageUrls.map { url ->
                    com.nyora.hasan72341.shared.model.MangaPage(url = url)
                }
                beginReaderAiSession()
                readerManga = com.nyora.hasan72341.shared.model.Manga(
                    id = "local:${path.hashCode()}",
                    title = info.name,
                    url = path,
                    source = com.nyora.hasan72341.shared.model.MangaSourceRef.Local,
                )
                readerChapter = com.nyora.hasan72341.shared.model.MangaChapter(
                    id = path,
                    title = info.name,
                    url = path,
                )
                readerPages = pages
                readerCurrentPage = 0
                readerLoading = false
                showReader = true
            }.onFailure { showStatus("Open failed: ${it.message}") }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun sourceFor(manga: Manga): MangaSource? {
        val refName = manga.source.name
        // Browsed/synced manga carry a canonical "JS_<ID>" ref while installed sources
        // use "parser:<ID>" — so match on the BARE id too (strip JS_/parser:). Without
        // this, opening any chapter fails to resolve its source ("Source not installed").
        val bare = refName.removePrefix("JS_").substringAfterLast(':')
        if (bare.isBlank() || bare == "Unknown" || bare == "Local") {
            return sources.firstOrNull { it.isInstalled && (it.id == refName || it.name == refName) }
        }
        return sources.firstOrNull { src ->
            src.isInstalled && (
                src.id == refName || src.name == refName ||
                src.id == "parser:$bare" || src.id.endsWith(":$bare") ||
                src.id.removePrefix("parser:") == bare
            )
        }
    }

    fun proxyUrl(page: MangaPage): String {
        val encoded = URLEncoder.encode(page.url, "UTF-8")
        if (page.headers.isEmpty()) return "$imageBaseUrl/image?u=$encoded"
        val headers = page.headers.entries.joinToString("&") { (k, v) ->
            "h=${URLEncoder.encode("$k:$v", "UTF-8")}"
        }
        return "$imageBaseUrl/image?u=$encoded&$headers"
    }

    fun coverProxyUrl(coverUrl: String, source: MangaSource? = null): String {
        if (coverUrl.isBlank()) return ""
        if (coverUrl.startsWith(imageBaseUrl)) return coverUrl  // already proxied (e.g. browse results)
        val encoded = URLEncoder.encode(coverUrl, "UTF-8")
        val refParam = source?.baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?.let { "&h=" + URLEncoder.encode("Referer:$it/", "UTF-8") } ?: ""
        return "$imageBaseUrl/image?u=$encoded$refParam"
    }

    // ── Appearance / accent persistence ─────────────────────────────────────────

    fun setAppearance(mode: AppearanceMode) {
        _appearance = mode
        savePrefs()
    }

    fun setAccent(value: Accent) {
        _accent = value
        savePrefs()
    }

    // ── Global settings setters ──────────────────────────────────────────────────
    //
    // Plain `var ... by mutableStateOf` fields above are persisted by calling
    // [persistSettings] from the UI after a change (or via the dedicated setters
    // below where a side-effect/value-guard is required). [persistSettings] is
    // just a public alias for the private [savePrefs] so screens have one call.

    /** Persists all global settings to disk. Call after mutating any plain
     *  settings var (defaultReaderMode, gridSize, historySortOrder, …). */
    fun persistSettings() = savePrefs()

    /** Reader background: "auto" | "dark" | "light". Persisted. */
    fun setReaderBackground(value: String) {
        _readerBackground = value
        savePrefs()
    }

    /** Incognito: when on, reading is NOT recorded to history. Persisted. */
    fun setIncognito(value: Boolean) {
        _incognito = value
        savePrefs()
    }

    /**
     * The Nyora config directory, mirroring the app convention:
     *   - Windows: %APPDATA%\Nyora  (fallback ~\AppData\Roaming\Nyora)
     *   - macOS:   ~/Library/Application Support/Nyora
     *   - Linux:   $XDG_CONFIG_HOME/nyora  (fallback ~/.config/nyora)
     *
     * The Windows location matches the shared engine (HelperMain /
     * SqlDelightLibraryRepository write the DB + port file under %APPDATA%\Nyora)
     * so the UI prefs sit alongside the library data.
     */
    private fun configDir(): File {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        val home = System.getProperty("user.home").orEmpty()
        val dir = when {
            os.contains("win") -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                    ?: File(home, "AppData/Roaming").path
                File(appData, "Nyora")
            }
            os.contains("mac") || os.contains("darwin") -> {
                File(home, "Library/Application Support/Nyora")
            }
            else -> {
                val xdg = System.getenv("XDG_CONFIG_HOME")
                if (!xdg.isNullOrBlank()) File(xdg, "nyora") else File(home, ".config/nyora")
            }
        }
        return dir
    }

    private fun prefsFile(): File = File(configDir(), "appPrefs.json")

    // ── First-run onboarding marker ───────────────────────────────────────────
    private fun onboardedMarker(): File = File(configDir(), ".onboarded")

    /** Dismiss the welcome/start page and remember the choice so it never shows again. */
    fun finishOnboarding() {
        showWelcome = false
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dir = configDir(); if (!dir.exists()) dir.mkdirs()
                onboardedMarker().writeText("1")
            }
        }
    }

    // ── Native window placement persistence ───────────────────────────────────
    private fun windowPropsFile(): File = File(configDir(), "window.properties")

    private fun readWindowProps(): java.util.Properties {
        val p = java.util.Properties()
        runCatching { windowPropsFile().takeIf { it.exists() }?.inputStream()?.use { p.load(it) } }
        return p
    }

    /** Persist the window's size + maximized state (the caller debounces the calls). */
    fun saveWindowPlacement(width: Int, height: Int, maximized: Boolean) {
        windowWidth = width; windowHeight = height; windowMaximized = maximized
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dir = configDir(); if (!dir.exists()) dir.mkdirs()
                val p = java.util.Properties()
                p["width"] = width.toString()
                p["height"] = height.toString()
                p["maximized"] = maximized.toString()
                windowPropsFile().outputStream().use { p.store(it, "Nyora window placement") }
            }
        }
    }

    private fun loadPrefs() {
        runCatching {
            val f = prefsFile()
            if (!f.exists()) {
                // First run — follow the Windows system theme + accent colour. The
                // user can still override both from Settings ▸ Appearance afterwards.
                WindowsNative.systemLight?.let {
                    _appearance = if (it) AppearanceMode.LIGHT else AppearanceMode.DARK
                }
                if (WindowsNative.isWindows) _accent = Accent.SYSTEM
                return
            }
            val raw = f.readText()
            val root = prefsJson.parseToJsonElement(raw).jsonObject
            val hasLegacyByokKey = root.containsKey("byokApiKey")
            val dto = prefsJson.decodeFromString<AppPrefs>(raw)
            _appearance = runCatching { AppearanceMode.valueOf(dto.appearance) }.getOrDefault(AppearanceMode.DARK)
            _accent     = runCatching { Accent.valueOf(dto.accent) }.getOrDefault(Accent.SYSTEM)
            _anilistToken = dto.anilistToken
            syncToken     = dto.syncToken
            cloudEmail    = dto.cloudEmail
            syncServerUrl = dto.syncServerUrl
            // Reader
            defaultReaderMode    = runCatching { ReaderMode.valueOf(dto.defaultReaderMode) }.getOrDefault(ReaderMode.PAGED)
            autoDetectReaderMode = dto.autoDetectReaderMode
            _readerBackground    = dto.readerBackground
            showZoomButtons      = dto.showZoomButtons
            twoPageLandscape     = dto.twoPageLandscape
            webtoonSidePadding   = dto.webtoonSidePadding
            autoHideControls     = dto.autoHideControls
            autoScrollLevel      = dto.autoScrollLevel
            keepScreenOn         = dto.keepScreenOn
            descriptionCollapse  = dto.descriptionCollapse
            gridSize             = dto.gridSize
            // Library / History
            historyRetentionDays = dto.historyRetentionDays
            groupHistoryByDate   = dto.groupHistoryByDate
            historySortOrder     = dto.historySortOrder
            hideNsfwSources      = dto.hideNsfwSources
            // Translation
            instantTranslate     = dto.instantTranslate
            translateTarget      = dto.translateTarget
            translateLangs       = dto.translateLangs
            // AI refinement
            aiMode               = runCatching { AiMode.valueOf(dto.aiMode) }.getOrDefault(AiMode.OFF)
            byokBaseUrl          = dto.byokBaseUrl
            byokEndpointError    = AiEndpointPolicy.validationError(byokBaseUrl)
            // API keys are intentionally session-only. Legacy values are never
            // loaded back into memory and are scrubbed from disk immediately.
            byokApiKey           = ""
            byokModel            = dto.byokModel
            // Privacy
            _incognito           = dto.incognito
            confirmBeforeQuit    = dto.confirmBeforeQuit
            // Advanced
            appLocale            = dto.appLocale
            // Source repository
            repositoryActive     = dto.repositoryActive
            repositoryUrl        = dto.repositoryUrl
            repositorySourceIds  = dto.repositorySourceIds
            sourcesGate?.set(repositoryActive)

            if (hasLegacyByokKey) {
                if (!scrubLegacyByokApiKey(f, root)) {
                    showStatus("A legacy saved AI key could not be removed from preferences; close Nyora and check its config folder.")
                }
            }
        }
    }

    /**
     * Remove the historical plaintext API-key field atomically. It is never
     * loaded into [byokApiKey], and a failed rewrite leaves the original prefs
     * intact rather than risking corruption of unrelated settings.
     */
    private fun scrubLegacyByokApiKey(file: File, root: JsonObject): Boolean = runCatching {
        val parent = file.parentFile ?: return@runCatching false
        if (!parent.isDirectory) return@runCatching false
        val sanitized = JsonObject(root.filterKeys { it != "byokApiKey" }).toString()
        val temporary = Files.createTempFile(parent.toPath(), "appPrefs-", ".json")
        try {
            Files.writeString(temporary, sanitized)
            try {
                Files.move(
                    temporary,
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }.getOrDefault(false)

    private fun savePrefs() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dir = configDir()
                if (!dir.exists()) dir.mkdirs()
                val dto = AppPrefs(
                    appearance = appearance.name,
                    accent = accent.name,
                    anilistToken = anilistToken,
                    syncToken = syncToken,
                    cloudEmail = cloudEmail,
                    syncServerUrl = syncServerUrl,
                    // Reader
                    defaultReaderMode = defaultReaderMode.name,
                    autoDetectReaderMode = autoDetectReaderMode,
                    readerBackground = readerBackground,
                    showZoomButtons = showZoomButtons,
                    twoPageLandscape = twoPageLandscape,
                    webtoonSidePadding = webtoonSidePadding,
                    autoHideControls = autoHideControls,
                    autoScrollLevel = autoScrollLevel,
                    keepScreenOn = keepScreenOn,
                    descriptionCollapse = descriptionCollapse,
                    gridSize = gridSize,
                    // Library / History
                    historyRetentionDays = historyRetentionDays,
                    groupHistoryByDate = groupHistoryByDate,
                    historySortOrder = historySortOrder,
                    hideNsfwSources = hideNsfwSources,
                    // Translation
                    instantTranslate = instantTranslate,
                    translateTarget = translateTarget,
                    translateLangs = translateLangs,
                    // AI refinement
                    aiMode = aiMode.name,
                    byokBaseUrl = byokBaseUrl,
                    byokModel = byokModel,
                    // Privacy
                    incognito = incognito,
                    confirmBeforeQuit = confirmBeforeQuit,
                    // Advanced
                    appLocale = appLocale,
                    // Source repository
                    repositoryActive = repositoryActive,
                    repositoryUrl = repositoryUrl,
                    repositorySourceIds = repositorySourceIds,
                )
                prefsFile().writeText(prefsJson.encodeToString(AppPrefs.serializer(), dto))
            }
        }
    }

    // ── Stats ───────────────────────────────────────────────────────────────────

    fun loadStats() {
        statsLoading = true
        scope.launch {
            runCatching {
                val body = http.get("/stats")
                stats = http.parse<StatsResponse>(body)
            }.onFailure { showStatus("Stats load failed: ${it.message}") }
            statsLoading = false
        }
    }

    // ── Suggestions ──────────────────────────────────────────────────────────────

    fun loadSuggestions() {
        suggestionsLoading = true
        scope.launch {
            runCatching {
                val body = http.get("/suggestions")
                suggestions = http.parse<SuggestionsResponse>(body).entries
            }.onFailure { showStatus("Suggestions failed: ${it.message}") }
            suggestionsLoading = false
        }
    }

    // ── Alternative sources ───────────────────────────────────────────────────────

    fun loadAlternatives(title: String) {
        if (title.isBlank()) return
        alternativesLoading = true
        alternatives = emptyList()
        scope.launch {
            runCatching {
                val encoded = URLEncoder.encode(title, "UTF-8")
                val body = http.get("/manga/alternatives?title=$encoded")
                alternatives = http.parse<AlternativesResponse>(body).entries.map { it.manga }
            }.onFailure { showStatus("Alternatives failed: ${it.message}") }
            alternativesLoading = false
        }
    }

    // ── Network settings ──────────────────────────────────────────────────────────

    fun loadNetworkSettings() {
        scope.launch {
            runCatching {
                val body = http.get("/settings/network")
                networkSettings = http.parse<NetworkSettingsResponse>(body).settings
            }.onFailure { showStatus("Network settings failed: ${it.message}") }
        }
    }

    fun saveNetworkSettings(dto: NetworkSettingsDto) {
        scope.launch {
            runCatching {
                val params = buildString {
                    append("proxyType=${URLEncoder.encode(dto.proxyType, "UTF-8")}")
                    append("&proxyAddress=${URLEncoder.encode(dto.proxyAddress, "UTF-8")}")
                    append("&proxyPort=${dto.proxyPort}")
                    append("&dnsOverHttps=${URLEncoder.encode(dto.dnsOverHttps, "UTF-8")}")
                    append("&githubMirror=${URLEncoder.encode(dto.githubMirror, "UTF-8")}")
                    append("&imagesProxy=${URLEncoder.encode(dto.imagesProxy, "UTF-8")}")
                    append("&sslBypass=${dto.sslBypass}")
                    append("&disableConnectivityCheck=${dto.disableConnectivityCheck}")
                    append("&adTrackerBlocking=${dto.adTrackerBlocking}")
                    append("&ignoreSSLErrors=${dto.ignoreSSLErrors}")
                }
                val body = http.post("/settings/network?$params")
                networkSettings = http.parse<NetworkSettingsResponse>(body).settings
                showStatus("Network settings saved.")
            }.onFailure { showStatus("Save failed: ${it.message}") }
        }
    }

    // ── Download settings ─────────────────────────────────────────────────────────

    fun loadDownloadSettings() {
        scope.launch {
            runCatching {
                val body = http.get("/downloads/settings")
                downloadSettings = http.parse<DownloadSettingsResponse>(body).settings
            }.onFailure { showStatus("Download settings failed: ${it.message}") }
        }
    }

    fun saveDownloadSettings(dto: DownloadSettingsDto) {
        scope.launch {
            runCatching {
                // NOTE: the POST param is "maxConcurrent" (the response key is
                // "maxConcurrentDownloads" — they intentionally differ).
                val params = "maxConcurrent=${dto.maxConcurrentDownloads}&format=${URLEncoder.encode(dto.format, "UTF-8")}"
                val body = http.post("/downloads/settings?$params")
                downloadSettings = http.parse<DownloadSettingsResponse>(body).settings
                showStatus("Download settings saved.")
            }.onFailure { showStatus("Save failed: ${it.message}") }
        }
    }

    // ── Backup ────────────────────────────────────────────────────────────────────

    fun exportBackup(path: String) {
        scope.launch {
            runCatching {
                val body = http.get("/backup/export")
                withContext(Dispatchers.IO) { File(path).writeText(body) }
                showStatus("Backup exported.")
            }.onFailure { showStatus("Export failed: ${it.message}") }
        }
    }

    fun importBackup(path: String) {
        scope.launch {
            runCatching {
                val content = withContext(Dispatchers.IO) { File(path).readText() }
                http.post("/backup/import", content)
                refreshLibrary()
                showStatus("Backup imported.")
            }.onFailure { showStatus("Import failed: ${it.message}") }
        }
    }

    // ── Trackers: AniList ──────────────────────────────────────────────────────────
    //
    // The AniList endpoints require an "Authorization: Bearer <token>" header,
    // which NyoraHttpClient does not expose, so these use a small dedicated
    // OkHttp client that injects the header.

    fun setAnilistToken(token: String) {
        _anilistToken = token
        savePrefs()
    }

    fun anilistSearch(query: String) {
        if (query.isBlank()) return
        if (anilistToken.isBlank()) { showStatus("Set an AniList token first."); return }
        anilistLoading = true
        scope.launch {
            runCatching {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val raw = authGet("/tracker/anilist/search?title=$encoded", anilistToken)
                anilistResult = prefsJson.decodeFromString<AniListSearchResponse>(raw)
            }.onFailure { showStatus("AniList search failed: ${it.message}") }
            anilistLoading = false
        }
    }

    fun anilistScrobble(mediaId: Int, progress: Int) {
        if (anilistToken.isBlank()) { showStatus("Set an AniList token first."); return }
        scope.launch {
            runCatching {
                authPost("/tracker/anilist/scrobble?mediaId=$mediaId&progress=$progress", anilistToken)
                showStatus("Progress synced to AniList.")
            }.onFailure { showStatus("Scrobble failed: ${it.message}") }
        }
    }

    // ── Multi-service trackers (AniList / MyAnimeList / Kitsu / Shikimori) ────────
    //
    // These drive the shared jvmMain scrobbler abstraction directly (in-process),
    // giving every desktop variant (linux / windows) real OAuth login + search
    // without the AniList-token-paste limitation above. NOTE: the loopback
    // redirect URI (http://127.0.0.1:<port>/callback) may need per-service
    // registration in each provider's OAuth app before consent succeeds — see
    // ScrobblerOAuth's header comment.

    /** Recompute which services are currently authorized. Cheap; safe to call often. */
    fun refreshTrackerAuth() {
        trackerAuthorized = ScrobblerService.entries
            .filter { runCatching { scrobblerRepo[it].isAuthorized }.getOrDefault(false) }
            .map { it.slug }
            .toSet()
    }

    /**
     * Run the shared loopback OAuth flow for an authorization-code service.
     * Suspends (in a coroutine) until the browser round-trips or the flow times out.
     */
    fun trackerLogin(service: ScrobblerService) {
        if (trackerBusy != null) return
        trackerBusy = service.slug
        scope.launch {
            runCatching {
                ScrobblerOAuth.login(
                    scrobblerRepo[service],
                    openBrowser = { url ->
                        trackerLoginUrl = url
                        ScrobblerOAuth.openInSystemBrowser(url)
                    },
                )
            }.onSuccess {
                showStatus("Signed in to ${service.title}.")
            }.onFailure {
                showStatus("${service.title} sign-in failed: ${it.message}")
            }
            trackerLoginUrl = null
            trackerBusy = null
            refreshTrackerAuth()
        }
    }


    /** Sign out of a single tracker service (clears its stored tokens). */
    fun trackerLogout(service: ScrobblerService) {
        runCatching { scrobblerRepo[service].logout() }
        trackerResults = trackerResults - service.slug
        showStatus("Signed out of ${service.title}.")
        refreshTrackerAuth()
    }

    /** Search a service's catalogue (requires an authorized session). */
    fun trackerSearch(service: ScrobblerService, query: String) {
        if (query.isBlank()) return
        if (trackerBusy != null) return
        trackerBusy = service.slug
        scope.launch {
            runCatching {
                scrobblerRepo[service].search(query)
            }.onSuccess { results ->
                trackerResults = trackerResults + (service.slug to results)
            }.onFailure {
                showStatus("${service.title} search failed: ${it.message}")
            }
            trackerBusy = null
        }
    }

    // ── "For You" feed (MangaBaka discovery) ─────────────────────────────────────
    //
    // AniList disabled its public API, so the Discover feed now comes from the
    // MangaBaka series database (https://api.mangabaka.dev) — NOT the Nyora proxy
    // and NOT imageBaseUrl. MangaBaka is search-first (no trending endpoint), so
    // we fetch a broad manga search (`q=a`), keep only safe content, filter out
    // junk/placeholder entries, then rank client-side by global popularity and
    // map onto the existing AniList feed shape. We reuse the existing `authHttp`
    // OkHttp client for its timeouts; this call needs no Authorization header.

    fun loadAnilistFeed() {
        if (anilistFeed.isNotEmpty()) return // already loaded; avoid refetch
        anilistFeedLoading = true
        scope.launch {
            runCatching {
                val url = "https://api.mangabaka.dev/v1/series/search" +
                    "?q=a&type=manga&content_rating=safe&limit=30"
                val raw = withContext(Dispatchers.IO) {
                    val req = Request.Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .get()
                        .build()
                    authHttp.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        resp.body!!.string()
                    }
                }
                val parsed = prefsJson.decodeFromString<MangaBakaSearchResponse>(raw)
                val media = parsed.data
                    .filter { it.mbUsable() }
                    .sortedByDescending { it.mbPopularity() }
                    .take(24)
                    .map { it.toFeedMedia() }
                withContext(Dispatchers.Main) { anilistFeed = media }
            }.onFailure { showStatus("Discover feed failed: ${it.message}") }
            anilistFeedLoading = false
        }
    }

    /** Best display title for a discovery feed media item. */
    fun anilistFeedTitle(m: AniListFeedMedia): String =
        m.title.english ?: m.title.romaji ?: m.title.native ?: "Untitled"

    // ── Sync ───────────────────────────────────────────────────────────────────────

    fun refreshCloudSyncStatus() {
        scope.launch {
            runCatching {
                cloudSyncStatus = fetchCloudSyncStatus()
            }.onFailure { showStatus("Nyora Sync status failed: ${it.message}") }
        }
    }

    fun cloudSignIn(email: String, password: String) = cloudAuth("/supabase/signin", email, password, register = false)

    fun cloudRegister(email: String, password: String) = cloudAuth("/supabase/register", email, password, register = true)

    private fun cloudAuth(path: String, email: String, password: String, register: Boolean) {
        if (cloudSyncBusy) return
        val em = email.trim()
        // Up-front validation with VISIBLE feedback — these messages surface inline on
        // the first-run Welcome screen (which renders above the global snackbar host).
        if (em.isEmpty() || password.isEmpty()) { authMessage = "Enter your email and password to continue."; return }
        if (register && !em.contains("@")) { authMessage = "Enter a valid email address."; return }
        if (register && password.length < 6) { authMessage = "Use a password with at least 6 characters."; return }
        scope.launch {
            cloudSyncBusy = true
            runCatching {
                val status = fetchCloudSyncStatus()
                cloudSyncStatus = status
                if (!status.isConfigured) error("Sync is temporarily unavailable. Please try again later.")

                authMessage = if (register) "Creating your account…" else "Signing in…"
                val q = "?email=${URLEncoder.encode(em, "UTF-8")}&password=${URLEncoder.encode(password, "UTF-8")}"
                requireSupabaseOk(http.post("$path$q"), if (register) "Couldn't create your account" else "Sign-in failed")
                cloudEmail = em
                savePrefs()

                val hasLocalData = prefsJson.decodeFromString<SupabaseLocalDataResponse>(
                    http.get("/supabase/has-local-data"),
                ).hasLocalData
                if (hasLocalData) {
                    // Local data present — let the user choose (Mac parity) instead of
                    // silently merging. The Welcome screen shows the Merge/Replace dialog.
                    cloudSyncStatus = fetchCloudSyncStatus()
                    authMessage = "You already have a library on this device."
                    cloudConflictPending = true
                    cloudSyncBusy = false
                    return@launch
                }
                // Fresh device — pull the cloud library down.
                authMessage = "Setting up your library…"
                requireSupabaseOk(http.post("/supabase/restore-from-cloud"), "Nyora Sync restore failed")
                refreshLibrary()
                cloudSyncStatus = fetchCloudSyncStatus()
                authMessage = if (register) "Account created — you're all set." else "Signed in — sync is ready."
                showStatus(authMessage!!)
            }.onFailure { authMessage = friendlyAuthError(it.message, register) }
            cloudSyncBusy = false
        }
    }

    /**
     * Resolve the post-sign-in "local data detected" choice (Mac parity). [replace] =
     * discard local and pull the cloud library; otherwise merge (push + pull).
     */
    fun cloudResolveConflict(replace: Boolean) {
        if (cloudSyncBusy) return
        cloudConflictPending = false
        scope.launch {
            cloudSyncBusy = true
            runCatching {
                authMessage = if (replace) "Restoring your cloud library…" else "Merging your libraries…"
                val path = if (replace) "/supabase/restore-from-cloud" else "/supabase/sync"
                requireSupabaseOk(http.post(path), "Nyora Sync failed")
                refreshLibrary()
                cloudSyncStatus = fetchCloudSyncStatus()
                authMessage = "Signed in — sync is ready."
                showStatus(authMessage!!)
            }.onFailure { authMessage = "Nyora Sync failed: ${it.message}" }
            cloudSyncBusy = false
        }
    }

    /** Maps raw server/helper errors to a clear, jargon-free message for the user. */
    private fun friendlyAuthError(raw: String?, register: Boolean): String {
        val m = raw.orEmpty()
        return when {
            m.contains("already registered", ignoreCase = true) || m.contains("409") ->
                "That email is already registered — try signing in instead."
            m.contains("valid email", ignoreCase = true) || m.contains("422") ->
                "Enter a valid email address."
            !register && (m.contains("401") || m.contains("invalid", ignoreCase = true)) ->
                "Incorrect email or password."
            register -> "Couldn't create your account. Check your connection and try again."
            else -> "Couldn't sign in. Check your connection and try again."
        }
    }

    fun cloudSyncNow() {
        if (cloudSyncBusy) return
        scope.launch {
            cloudSyncBusy = true
            runCatching {
                requireSupabaseOk(http.post("/supabase/sync"), "Nyora Sync failed")
                refreshLibrary()
                cloudSyncStatus = fetchCloudSyncStatus()
                showStatus("Nyora Sync complete.")
            }.onFailure { showStatus("Nyora Sync failed: ${it.message}") }
            cloudSyncBusy = false
        }
    }

    fun cloudRestoreFromCloud() {
        if (cloudSyncBusy) return
        scope.launch {
            cloudSyncBusy = true
            runCatching {
                requireSupabaseOk(http.post("/supabase/restore-from-cloud"), "Nyora Sync restore failed")
                refreshLibrary()
                cloudSyncStatus = fetchCloudSyncStatus()
                showStatus("Nyora Sync library restored.")
            }.onFailure { showStatus("Nyora Sync restore failed: ${it.message}") }
            cloudSyncBusy = false
        }
    }

    fun cloudSignOut() {
        if (cloudSyncBusy) return
        scope.launch {
            cloudSyncBusy = true
            runCatching {
                requireSupabaseOk(http.post("/supabase/signout"), "Nyora Sync sign-out failed")
                cloudEmail = ""
                savePrefs()
                cloudSyncStatus = fetchCloudSyncStatus()
                showStatus("Signed out of Nyora Sync.")
            }.onFailure { showStatus("Cloud sign-out failed: ${it.message}") }
            cloudSyncBusy = false
        }
    }

    private suspend fun fetchCloudSyncStatus(): SupabaseStatusResponse =
        prefsJson.decodeFromString(http.get("/supabase/status"))

    fun refreshOtaStatus() {
        scope.launch {
            runCatching {
                otaStatus = prefsJson.decodeFromString<OtaStatusResponse>(http.get("/ota/status"))
            }.onFailure { showStatus("Parser status check failed: ${it.message}") }
        }
    }

    fun otaCheckNow() {
        if (otaBusy) return
        scope.launch {
            otaBusy = true
            runCatching {
                otaStatus = prefsJson.decodeFromString<OtaStatusResponse>(http.post("/ota/check"))
                val st = otaStatus
                if (st != null && st.isActive && st.otaVersion > st.bundledVersion)
                    showStatus("Parsers updated to v${st.otaVersion}. Restart to apply.")
                else
                    showStatus("Parsers are up to date.")
            }.onFailure { showStatus("Parser update check failed: ${it.message}") }
            otaBusy = false
        }
    }

    private fun requireSupabaseOk(raw: String, fallback: String) {
        val resp = prefsJson.decodeFromString<SupabaseOkResponse>(raw)
        if (!resp.ok) error(resp.error.ifBlank { fallback })
    }

    fun openExternalUrl(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        }
        val os = System.getProperty("os.name").lowercase()
        when {
            os.contains("win") -> ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
            os.contains("mac") || os.contains("darwin") -> ProcessBuilder("open", url).start()
            else -> ProcessBuilder("xdg-open", url).start()
        }
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split("&")
            .filter { it.isNotBlank() }
            .associate { part ->
                val idx = part.indexOf("=")
                val key = if (idx >= 0) part.substring(0, idx) else part
                val value = if (idx >= 0) part.substring(idx + 1) else ""
                URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            }

    private fun randomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun pkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun syncSignIn(email: String, password: String, server: String = syncServerUrl) {
        if (server.isBlank()) { showStatus("Set a sync server URL first."); return }
        scope.launch {
            runCatching {
                val payload = prefsJson.encodeToString(
                    SignInBody.serializer(), SignInBody(email = email, password = password),
                )
                val encServer = URLEncoder.encode(server, "UTF-8")
                val raw = http.post("/sync/signin?server=$encServer", payload)
                val resp = prefsJson.decodeFromString<SyncSignInResponse>(raw)
                val token = resp.token ?: resp.accessToken
                if (!token.isNullOrBlank()) {
                    syncToken = token
                    syncServerUrl = server
                    savePrefs()
                    showStatus("Signed in to sync.")
                } else {
                    showStatus("Sign-in failed: ${resp.error ?: resp.message ?: "no token"}")
                }
            }.onFailure { showStatus("Sign-in failed: ${it.message}") }
        }
    }

    fun syncPush(server: String = syncServerUrl) {
        if (syncToken.isBlank()) { showStatus("Sign in to sync first."); return }
        if (server.isBlank()) { showStatus("Set a sync server URL first."); return }
        scope.launch {
            runCatching {
                val encServer = URLEncoder.encode(server, "UTF-8")
                authPost("/sync/push?server=$encServer", syncToken)
                showStatus("Library pushed to sync.")
            }.onFailure { showStatus("Push failed: ${it.message}") }
        }
    }

    // ── Authenticated HTTP helpers (Bearer header) ──────────────────────────────────
    // NyoraHttpClient cannot attach headers; these mirror its baseUrl + IO usage.

    private val authHttp = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private suspend fun authGet(path: String, bearer: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$imageBaseUrl$path")
            .header("Authorization", "Bearer $bearer")
            .get().build()
        authHttp.newCall(req).execute().use { it.body!!.string() }
    }

    private suspend fun authPost(path: String, bearer: String, body: String = "{}"): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$imageBaseUrl$path")
                .header("Authorization", "Bearer $bearer")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            authHttp.newCall(req).execute().use { it.body!!.string() }
        }

    private val prefsJson = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @Serializable
    private data class AppPrefs(
        val appearance: String = AppearanceMode.DARK.name,
        val accent: String = Accent.SYSTEM.name,
        val anilistToken: String = "",
        val syncToken: String = "",
        val cloudEmail: String = "",
        val syncServerUrl: String = "",
        // Reader
        val defaultReaderMode: String = ReaderMode.PAGED.name,
        val autoDetectReaderMode: Boolean = false,
        val readerBackground: String = "auto",
        val showZoomButtons: Boolean = true,
        val twoPageLandscape: Boolean = false,
        val webtoonSidePadding: Double = 0.0,
        val autoHideControls: Boolean = true,
        val autoScrollLevel: Int = 4,
        val keepScreenOn: Boolean = false,
        val descriptionCollapse: Boolean = true,
        val gridSize: Int = 160,
        // Library / History
        val historyRetentionDays: Int = 0,
        val groupHistoryByDate: Boolean = true,
        val historySortOrder: String = "recent",
        val hideNsfwSources: Boolean = true,
        // Translation
        val instantTranslate: Boolean = false,
        val translateTarget: String = "en",
        val translateLangs: String = "ja",
        // AI refinement
        val aiMode: String = AiMode.OFF.name,
        val byokBaseUrl: String = "https://api.openai.com/v1",
        val byokModel: String = "gpt-4o-mini",
        // Privacy
        val incognito: Boolean = false,
        val confirmBeforeQuit: Boolean = false,
        // Advanced
        val appLocale: String = "auto",
        // Source repository (Windows): inactive until a signed index is added.
        val repositoryActive: Boolean = false,
        val repositoryUrl: String = "",
        val repositorySourceIds: List<String> = emptyList(),
    )

    @Serializable
    private data class SignInBody(val email: String = "", val password: String = "")


    private fun showStatus(message: String) {
        statusMessage = message
        scope.launch {
            delay(3000)
            if (statusMessage == message) statusMessage = null
        }
    }
}
