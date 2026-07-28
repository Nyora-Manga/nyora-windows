package com.nyora.windows.ui.screen

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.FormatTextdirectionLToR
import androidx.compose.material.icons.filled.FormatTextdirectionRToL
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.nyora.windows.AppState
import com.nyora.windows.ReaderMode
import com.nyora.windows.ui.reader.ReaderColorFilterSheet
import com.nyora.windows.ui.reader.buildReaderColorFilter
import com.nyora.windows.ui.theme.LocalNyoraAccent
import com.nyora.windows.ui.theme.NyoraTokens
import com.nyora.windows.ui.theme.SectionHeader
import com.nyora.windows.ui.theme.SystemTag
import com.nyora.windows.ui.theme.glassCard
import com.nyora.windows.ui.theme.glassOverlay
import com.nyora.windows.translate.PageTranslation
import com.nyora.windows.ai.AiMode
import com.nyora.hasan72341.shared.model.MangaChapter
import com.nyora.hasan72341.shared.model.MangaPage
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How the current page is fit to the viewport when paging. Cycled with the 'F' key.
 */
private enum class FitMode { FIT_CENTER, FIT_WIDTH, FIT_HEIGHT }

private fun FitMode.next(): FitMode = when (this) {
    FitMode.FIT_CENTER -> FitMode.FIT_WIDTH
    FitMode.FIT_WIDTH -> FitMode.FIT_HEIGHT
    FitMode.FIT_HEIGHT -> FitMode.FIT_CENTER
}

private fun FitMode.contentScale(): ContentScale = when (this) {
    FitMode.FIT_CENTER -> ContentScale.Fit
    FitMode.FIT_WIDTH -> ContentScale.FillWidth
    FitMode.FIT_HEIGHT -> ContentScale.FillHeight
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(state: AppState) {
    val pages = state.readerPages
    val totalPages = pages.size
    var showHUD by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var showColorSheet by remember { mutableStateOf(false) }
    var scrubPreview by remember { mutableStateOf<Int?>(null) }

    // Distinct right-to-left *paging* direction for the PAGED mode, independent of the
    // global rtlReading layout flag. Seeded from the global flag for first-open parity,
    // then driven by its own HUD toggle. Re-seeded whenever a new chapter opens.
    var pagedRtl by remember { mutableStateOf(state.rtlReading) }
    LaunchedEffect(state.readerChapter?.id) { pagedRtl = state.rtlReading }

    // Effective horizontal reading direction for the active paged view.
    val effectiveRtl = pagedRtl

    // Lifted zoom / pan / fit so keyboard shortcuts and the page view share one source of
    // truth. Reset whenever the visible page changes.
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var fitMode by remember { mutableStateOf(FitMode.FIT_CENTER) }
    LaunchedEffect(state.readerCurrentPage) {
        scale = 1f
        offset = Offset.Zero
    }

    // The live GPU color grade — recomputed only when a grading parameter changes.
    val colorFilter: ColorFilter? = remember(
        state.readerBrightness,
        state.readerContrast,
        state.readerSaturation,
        state.readerHue,
        state.readerPalette,
    ) {
        buildReaderColorFilter(
            brightness = state.readerBrightness,
            contrast = state.readerContrast,
            saturation = state.readerSaturation,
            hue = state.readerHue,
            palette = state.readerPalette,
        )
    }

    // Seed readerMode from the global default when the chapter opens. loadMangaPrefs()
    // runs async inside openChapter() and will override this if a per-manga row exists
    // (dto.present == true). For titles with no saved prefs this IS the effective mode.
    LaunchedEffect(state.readerChapter?.id) {
        state.readerMode = state.defaultReaderMode
    }

    // Instant-translate: auto-enable translation on chapter open when the global setting
    // is on. translateEnabled is preserved across chapters by openChapter(), but if the
    // user had it off and this pref is on we force it here.
    LaunchedEffect(state.readerChapter?.id) {
        if (state.instantTranslate && !state.translateEnabled) {
            state.toggleTranslate()
        }
    }

    // Whole-chapter pre-translate on open when translation is already on
    // (carries across chapters) — mac parity. Idempotent: skips cached pages.
    LaunchedEffect(state.readerChapter?.id) {
        if (state.translateEnabled) state.translateWholeChapter()
    }

    // Auto-hide HUD after 4s of inactivity — only when autoHideControls is on.
    LaunchedEffect(showHUD, state.autoHideControls) {
        if (showHUD && state.autoHideControls) {
            delay(4000)
            showHUD = false
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    // Shared scroll state for the continuous (webtoon/vertical) readers so
    // auto-scroll can drive the offset directly.
    val webtoonListState = rememberLazyListState()
    // Auto-scroll driver (nyora-mac / web model). Paged: flip a page every
    // `autoScrollPagedDelay` seconds. Webtoon/vertical: nudge the offset every
    // frame by px/sec. Both roll into the next chapter at the end, then stop.
    LaunchedEffect(state.autoScrollOn, state.readerMode, state.readerChapter?.id) {
        if (!state.autoScrollOn) return@LaunchedEffect
        if (state.readerMode == ReaderMode.PAGED) {
            while (state.autoScrollOn) {
                delay((state.autoScrollPagedDelay * 1000).toLong())
                if (!state.autoScrollOn) break
                val last = state.readerPages.size - 1
                if (state.readerCurrentPage >= last && !hasAdjacentChapter(state, +1)) {
                    state.autoScrollOn = false; break
                }
                navigateEdgeAware(state, +1)
            }
        } else {
            var lastNanos = 0L
            while (state.autoScrollOn) {
                val now = withFrameNanos { it }
                val dtNs = if (lastNanos == 0L) 0L else (now - lastNanos).coerceAtMost(100_000_000L)
                lastNanos = now
                val dy = (state.autoScrollPxPerSec * (dtNs / 1_000_000_000.0)).toFloat()
                if (dy > 0f) runCatching { webtoonListState.scrollBy(dy) }
                if (!webtoonListState.canScrollForward) {
                    if (hasAdjacentChapter(state, +1)) goAdjacentChapter(state, +1)
                    else state.autoScrollOn = false
                    break
                }
            }
        }
    }

    // Determine background color from the global readerBackground setting.
    // "dark" and "auto" both use pure black for reading; "light" uses the theme surface.
    // Webtoon/vertical page tracking (parity with paged + mac). Seed the scroll to
    // the resume page on open, and mirror the scroll position back into
    // readerCurrentPage so the scrubber/progress line move and the chapter resumes.
    LaunchedEffect(state.readerChapter?.id, state.readerMode) {
        if (state.readerMode != ReaderMode.PAGED) {
            val p = state.readerCurrentPage.coerceIn(0, (state.readerPages.size - 1).coerceAtLeast(0))
            if (p > 0) runCatching { webtoonListState.scrollToItem(p) }
        }
    }
    LaunchedEffect(webtoonListState, state.readerMode) {
        if (state.readerMode != ReaderMode.PAGED) {
            snapshotFlow { webtoonListState.firstVisibleItemIndex }
                .collect { state.recordReaderPage(it) }
        }
    }
    val readerBgColor = if (state.readerBackground == "light") NyoraTokens.bg else Color(0xFF000000)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(readerBgColor)
            .androidxFocusable(focusRequester)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Escape -> { state.closeReader(); true }
                    // Horizontal arrows respect the effective paged direction.
                    Key.DirectionRight -> { navigateEdgeAware(state, if (effectiveRtl) -1 else 1); true }
                    Key.DirectionLeft -> { navigateEdgeAware(state, if (effectiveRtl) 1 else -1); true }
                    Key.DirectionDown -> { navigateEdgeAware(state, 1); true }
                    Key.DirectionUp -> { navigateEdgeAware(state, -1); true }
                    // Zoom.
                    Key.Equals, Key.Plus, Key.NumPadAdd -> {
                        scale = (scale + 0.25f).coerceIn(1f, 4f); true
                    }
                    Key.Minus, Key.NumPadSubtract -> {
                        scale = (scale - 0.25f).coerceIn(1f, 4f)
                        if (scale <= 1f) offset = Offset.Zero
                        true
                    }
                    Key.Zero, Key.NumPad0 -> { scale = 1f; offset = Offset.Zero; true }
                    Key.F -> { fitMode = fitMode.next(); true }
                    Key.Home, Key.MoveHome -> { state.recordReaderPage(0); true }
                    Key.MoveEnd -> { state.recordReaderPage((totalPages - 1).coerceAtLeast(0)); true }
                    Key.N -> { goAdjacentChapter(state, +1); true }
                    Key.P -> { goAdjacentChapter(state, -1); true }
                    Key.Spacebar -> { navigateEdgeAware(state, 1); true }
                    Key.A -> { state.toggleAutoScroll(); true }
                    else -> false
                }
            },
    ) {
        when {
            state.readerLoading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LocalNyoraAccent.current.color)
                }
            pages.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No pages found.", color = NyoraTokens.onSurfaceMuted)
                }
            state.readerMode == ReaderMode.PAGED ->
                PagedReader(
                    state = state,
                    pages = pages,
                    colorFilter = colorFilter,
                    reverseLayout = effectiveRtl,
                    scale = scale,
                    offset = offset,
                    fitMode = fitMode,
                    onScaleChange = { scale = it },
                    onOffsetChange = { offset = it },
                )
            state.readerMode == ReaderMode.WEBTOON ->
                WebtoonReader(state, pages, colorFilter, webtoonListState)
            else ->
                VerticalReader(state, pages, colorFilter, webtoonListState)
        }

        // Click-zone navigation overlay (only meaningful in paged mode). Left / right 25%
        // edges page; the center toggles the HUD. RTL-aware: in RTL, the left edge advances.
        // A single tap-gesture detector also owns double-tap-to-zoom so it does not fight the
        // pager's own gestures. Disabled while zoomed in so panning is unobstructed.
        if (state.readerMode == ReaderMode.PAGED && pages.isNotEmpty() && scale <= 1f) {
            ClickZoneOverlay(
                rtl = effectiveRtl,
                onPrev = { navigateEdgeAware(state, -1) },
                onNext = { navigateEdgeAware(state, +1) },
                onCenter = { showHUD = !showHUD },
                onDoubleTap = { scale = 2.5f },
            )
        }

        // Persistent back button: always visible, top-left, z-ordered above the click-zone
        // overlay. Uses a bounded 48dp hit area so it does NOT steal the left-25% prev tap
        // region — it only consumes events directly on the button circle itself.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.30f))
                .clickable { state.closeReader() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }

        // Hyper-Modern Zen HUD.
        AnimatedVisibility(
            visible = showHUD,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
        ) {
            ReaderControlPill(
                state = state,
                totalPages = totalPages,
                pagedRtl = pagedRtl,
                onTogglePagedRtl = { pagedRtl = !pagedRtl },
                onSettings = { showSettings = true },
                onColorGrade = { showColorSheet = true },
                scale = scale,
                onZoomIn = { scale = (scale + 0.25f).coerceIn(1f, 4f) },
                onZoomOut = {
                    scale = (scale - 0.25f).coerceIn(1f, 4f)
                    if (scale <= 1f) offset = Offset.Zero
                },
                onScrub = { scrubPreview = it },
            )
        }

        // Scrub preview thumbnail (mac / Apple Books) — above the pill while dragging.
        scrubPreview?.let { previewPage ->
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 116.dp),
                contentAlignment = Alignment.Center,
            ) { ScrubThumbnail(state, previewPage, totalPages) }
        }

        // Signature glowing progress line (absolute bottom) with a breathing accent glow.
        if (totalPages > 1) {
            GlowProgressLine(progress = (state.readerCurrentPage + 1).toFloat() / totalPages.toFloat())
        }
    }

    if (showSettings) {
        ReaderSettingsDialog(
            state = state,
            onDismiss = { showSettings = false },
            onOpenColor = { showSettings = false; showColorSheet = true },
        )
    }
    if (showColorSheet) {
        ReaderColorFilterSheet(state = state, onDismiss = { showColorSheet = false })
    }
}

/** The bottom progress beacon: a spring-animated accent fill with a soft gradient head. */
@Composable
private fun BoxScope.GlowProgressLine(progress: Float) {
    val accent = LocalNyoraAccent.current.color
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 260f),
        label = "readerProgress",
    )
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth(animated)
            .height(3.dp)
            .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
            .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.65f), accent)))
    )
}

/**
 * Transparent overlay covering the viewport. A single [detectTapGestures] resolves the tap
 * by horizontal position: the left 25% / right 25% page (RTL-aware — in RTL the left edge
 * advances), and the center 50% toggles the HUD. Double-tap is handled here too so it does
 * not race the pager's own gestures. Drag gestures pass through to the pager untouched.
 */
@Composable
private fun ClickZoneOverlay(
    rtl: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCenter: () -> Unit,
    onDoubleTap: () -> Unit,
) {
    val leftAction by rememberUpdatedState(if (rtl) onNext else onPrev)
    val rightAction by rememberUpdatedState(if (rtl) onPrev else onNext)
    val center by rememberUpdatedState(onCenter)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(rtl) {
                detectTapGestures(
                    onDoubleTap = { doubleTap() },
                    onTap = { pos ->
                        val w = size.width.toFloat()
                        when {
                            pos.x < w * 0.25f -> leftAction()
                            pos.x > w * 0.75f -> rightAction()
                            else -> center()
                        }
                    },
                )
            }
    )
}

@Composable
private fun ScrubThumbnail(state: AppState, page: Int, total: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .glassOverlay(shape = RoundedCornerShape(16.dp), fill = NyoraTokens.surface1)
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(120.dp, 168.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black)
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            SubcomposeAsyncImage(
                model = state.pageDisplayModel(page),
                contentDescription = "Preview page ${page + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                loading = { CircularProgressIndicator(color = LocalNyoraAccent.current.color, modifier = Modifier.size(20.dp)) },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${page + 1} / $total",
            style = MaterialTheme.typography.labelSmall,
            color = NyoraTokens.onSurfaceHigh,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ReaderControlPill(
    state: AppState,
    totalPages: Int,
    pagedRtl: Boolean,
    onTogglePagedRtl: () -> Unit,
    onSettings: () -> Unit,
    onColorGrade: () -> Unit,
    scale: Float = 1f,
    onZoomIn: () -> Unit = {},
    onZoomOut: () -> Unit = {},
    onScrub: (Int?) -> Unit = {},
) {
    val accent = LocalNyoraAccent.current.color
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPage by remember { mutableStateOf(state.readerCurrentPage) }
    LaunchedEffect(state.readerCurrentPage) { if (!scrubbing) scrubPage = state.readerCurrentPage }
    Box(
        modifier = Modifier
            .height(72.dp)
            .widthIn(min = 480.dp, max = 940.dp)
            .glassOverlay(shape = RoundedCornerShape(36.dp), fill = NyoraTokens.surface1)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PillIconButton(
                onClick = { state.closeReader() },
                accentTinted = false,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = NyoraTokens.onSurfaceHigh)
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.readerChapter?.title ?: "Reading",
                    color = NyoraTokens.onSurfaceHigh,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = "PROGRESS ${state.readerCurrentPage + 1} / $totalPages",
                    color = accent,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black
                )
            }

            IconButton(onClick = { navigateEdgeAware(state, -1) }) {
                Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "Prev", tint = NyoraTokens.onSurfaceHigh)
            }

            // Page scrubber with a live thumbnail preview (mac / Apple Books):
            // preview only while dragging, commit the page on release.
            Slider(
                value = (if (scrubbing) scrubPage else state.readerCurrentPage).toFloat(),
                onValueChange = { v ->
                    scrubbing = true
                    scrubPage = v.toInt().coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                    onScrub(scrubPage)
                },
                onValueChangeFinished = {
                    scrubbing = false
                    onScrub(null)
                    state.recordReaderPage(scrubPage)
                },
                valueRange = 0f..(totalPages - 1).coerceAtLeast(1).toFloat(),
                modifier = Modifier.width(150.dp),
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = NyoraTokens.glass4
                )
            )

            IconButton(onClick = { navigateEdgeAware(state, +1) }) {
                Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "Next", tint = NyoraTokens.onSurfaceHigh)
            }

            VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 12.dp), color = NyoraTokens.glass3)

            // Zoom buttons: only shown when the global showZoomButtons pref is enabled.
            if (state.showZoomButtons) {
                IconButton(onClick = onZoomOut, enabled = scale > 1f) {
                    Icon(Icons.Default.Remove, contentDescription = "Zoom out", tint = if (scale > 1f) NyoraTokens.onSurfaceHigh else NyoraTokens.onSurfaceFaint)
                }
                IconButton(onClick = onZoomIn, enabled = scale < 4f) {
                    Icon(Icons.Default.Add, contentDescription = "Zoom in", tint = if (scale < 4f) NyoraTokens.onSurfaceHigh else NyoraTokens.onSurfaceFaint)
                }
                VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 12.dp), color = NyoraTokens.glass3)
            }

            // Paged RTL toggle (independent of the global rtlReading layout flag).
            IconButton(onClick = onTogglePagedRtl) {
                Icon(
                    imageVector = if (pagedRtl) Icons.Default.FormatTextdirectionRToL else Icons.Default.FormatTextdirectionLToR,
                    contentDescription = "Paged direction",
                    tint = if (pagedRtl) accent else NyoraTokens.onSurfaceHigh
                )
            }

            // Auto-scroll play/pause (web-style). Tinted when running.
            IconButton(onClick = { state.toggleAutoScroll() }) {
                Icon(
                    imageVector = if (state.autoScrollOn) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Auto-scroll",
                    tint = if (state.autoScrollOn) accent else NyoraTokens.onSurfaceHigh,
                )
            }

            IconButton(onClick = { state.togglePageBookmark() }) {
                Icon(
                    imageVector = if (state.currentPageBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = "Bookmark",
                    tint = if (state.currentPageBookmarked) accent else NyoraTokens.onSurfaceHigh
                )
            }

            // Color grade sheet (W1).
            IconButton(onClick = onColorGrade) {
                Icon(Icons.Rounded.Tune, contentDescription = "Color grade", tint = NyoraTokens.onSurfaceHigh)
            }

            // In-image translation: toggle + (when on) busy spinner + language menu.
            TranslateControls(state = state, accent = accent)

            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = NyoraTokens.onSurfaceHigh)
            }
        }
    }
}

/**
 * The HUD translate cluster: a [Icons.Default.Translate] toggle that flips
 * [AppState.toggleTranslate]; a small spinner while [AppState.translateBusy]; and — only
 * while translation is enabled — a compact language menu showing the active target code.
 * When Windows OCR is unavailable ([AppState.translateUnavailable]) a tiny "OCR?" hint
 * sits under the toggle (the hint snackbar is already raised by AppState).
 */
@Composable
private fun TranslateControls(state: AppState, accent: Color) {
    var langMenuOpen by remember { mutableStateOf(false) }
    val languages = remember {
        listOf(
            "English" to "en",
            "Spanish" to "es",
            "French" to "fr",
            "German" to "de",
            "Indonesian" to "id",
            "Portuguese" to "pt",
            "Russian" to "ru",
            "Arabic" to "ar",
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.BottomCenter) {
            IconButton(onClick = { state.toggleTranslate() }) {
                Icon(
                    imageVector = Icons.Default.Translate,
                    contentDescription = "Translate page",
                    tint = if (state.translateEnabled) accent else NyoraTokens.onSurfaceBody,
                )
            }
            if (state.translateUnavailable) {
                Text(
                    text = "OCR?",
                    color = NyoraTokens.onSurfaceMuted,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }

        if (state.translateBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = accent,
            )
            Spacer(Modifier.width(4.dp))
        }

        // Compact language picker — only meaningful once translation is active.
        if (state.translateEnabled) {
            Box {
                TextButton(
                    onClick = { langMenuOpen = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = state.translateTarget.uppercase(),
                        color = accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp,
                    )
                }
                DropdownMenu(
                    expanded = langMenuOpen,
                    onDismissRequest = { langMenuOpen = false },
                ) {
                    languages.forEach { (label, code) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = label,
                                    color = if (state.translateTarget == code) accent else NyoraTokens.onSurfaceBody,
                                    fontWeight = if (state.translateTarget == code) FontWeight.Black else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                state.changeTranslateTarget(code)
                                langMenuOpen = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PillIconButton(
    onClick: () -> Unit,
    accentTinted: Boolean,
    content: @Composable () -> Unit,
) {
    val accent = LocalNyoraAccent.current.color
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (accentTinted) accent.copy(alpha = 0.18f) else NyoraTokens.surface1)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagedReader(
    state: AppState,
    pages: List<MangaPage>,
    colorFilter: ColorFilter?,
    reverseLayout: Boolean,
    scale: Float,
    offset: Offset,
    fitMode: FitMode,
    onScaleChange: (Float) -> Unit,
    onOffsetChange: (Offset) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Slot model: each slot is ONE page normally, or TWO pages side-by-side in
        // landscape double-page mode (twoPageLandscape pref + a wider-than-tall window).
        // readerCurrentPage stays the source of truth in single-page units, so the HUD
        // counter, slider, bookmarks and keyboard Home/End are untouched — only paging
        // steps a whole spread at a time (via readerSlots, read by navigateEdgeAware).
        val twoUp = state.twoPageLandscape && maxWidth > maxHeight && pages.size > 1
        val slots = remember(pages.size, twoUp) { buildReaderSlots(pages.size, twoUp) }

        // Publish the slot model so navigateEdgeAware pages a spread at a time; clear it on
        // leave so webtoon/vertical/portrait fall back to single-page ±1 stepping.
        DisposableEffect(twoUp, slots) {
            state.readerSlots = if (twoUp) slots else emptyList()
            onDispose { state.readerSlots = emptyList() }
        }

        fun pageToSlot(page: Int) = slots.indexOfFirst { page in it }.coerceAtLeast(0)

        val pagerState = rememberPagerState(
            initialPage = pageToSlot(state.readerCurrentPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))),
            pageCount = { slots.size },
        )
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage }.collect { slot ->
                slots.getOrNull(slot)?.firstOrNull()?.let { state.recordReaderPage(it) }
            }
        }
        LaunchedEffect(state.readerCurrentPage, slots) {
            val target = pageToSlot(state.readerCurrentPage)
            if (pagerState.currentPage != target) pagerState.scrollToPage(target)
        }

        HorizontalPager(
            state = pagerState,
            reverseLayout = reverseLayout,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 0.dp,
            // Prefetch depth: decode the next/previous N slots off-screen so the
            // reader never blocks on a single slow page. Coil fetches each page
            // image independently and asynchronously through the engine /image
            // proxy (gated by its own Semaphore(48)), so raising this fans out
            // concurrent decodes ahead of the viewport rather than serialising
            // them. Gated by the user's "Enable prefetching" pref: 2 when on
            // (current ±2 slots), 0 when off (only the visible slot decodes).
            beyondViewportPageCount = if (state.prefetchEnabled) 2 else 0
        ) { slotIndex ->
            val isCurrent = slotIndex == pagerState.currentPage
            val slotPages = slots[slotIndex]
            // The remembered gesture-state lambda must read the *latest* lifted zoom/pan, so we
            // funnel the current values through rememberUpdatedState to avoid capturing stale
            // params from first composition.
            val latestScale by rememberUpdatedState(scale)
            val latestOffset by rememberUpdatedState(offset)
            val latestIsCurrent by rememberUpdatedState(isCurrent)
            // Only the current slot reflects the lifted zoom/pan (keyboard-driven). Off-screen
            // slots always render at rest.
            val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                if (latestIsCurrent) {
                    val next = (latestScale * zoomChange).coerceIn(1f, 4f)
                    onScaleChange(next)
                    onOffsetChange(if (next <= 1f) Offset.Zero else latestOffset + panChange)
                }
            }
            val appliedScale = if (isCurrent) scale else 1f
            val appliedOffset = if (isCurrent) offset else Offset.Zero

            // Translate every page in the *current* slot on entry / when translation toggles
            // on. translatePage() is a no-op when already cached or disabled, so this is cheap.
            LaunchedEffect(slotIndex, state.translateEnabled, isCurrent) {
                if (isCurrent && state.translateEnabled) slotPages.forEach { state.translatePage(it) }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(transformState)
                    .pointerInput(isCurrent) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (isCurrent) {
                                    if (latestScale > 1f) {
                                        onScaleChange(1f); onOffsetChange(Offset.Zero)
                                    } else {
                                        onScaleChange(2.5f)
                                    }
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                // The whole spread (1–2 pages) shares ONE transformed container so zoom / pan
                // move both halves together. In RTL the higher-numbered page sits on the left.
                Box(
                    modifier = Modifier.fillMaxSize().graphicsLayer(
                        scaleX = appliedScale, scaleY = appliedScale,
                        translationX = appliedOffset.x, translationY = appliedOffset.y,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    val ordered = if (reverseLayout) slotPages.asReversed() else slotPages
                    Row(Modifier.fillMaxSize()) {
                        ordered.forEach { pageIndex ->
                            Box(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                contentAlignment = Alignment.Center,
                            ) {
                                ReaderPageImage(state, pages, pageIndex, colorFilter, fitMode)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One page image plus its translation overlay, filling its parent slot cell. Kept
 * separate so the paged reader can place one (single) or two (double-page spread) of
 * these inside a shared, zoom/pan-transformed container.
 */
@Composable
private fun ReaderPageImage(
    state: AppState,
    pages: List<MangaPage>,
    index: Int,
    colorFilter: ColorFilter?,
    fitMode: FitMode,
) {
    // The image + its translation overlay share ONE box so the bubbles track the art.
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = state.pageDisplayModel(index),
            contentDescription = "Page ${index + 1}",
            contentScale = fitMode.contentScale(),
            colorFilter = colorFilter,
            modifier = Modifier.fillMaxSize(),
            loading = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LocalNyoraAccent.current.color)
                }
            },
        )

        val pt = state.pageTranslations[index]
        if (state.translateEnabled && pt != null &&
            pt.imageWidth > 0 && pt.imageHeight > 0 && pt.blocks.isNotEmpty()
        ) {
            TranslationOverlay(
                translation = pt,
                fitMode = fitMode,
                boxMaxWidth = with(LocalDensity.current) { maxWidth.toPx() },
                boxMaxHeight = with(LocalDensity.current) { maxHeight.toPx() },
            )
        }
    }
}

/**
 * Group page indices into on-screen slots for the paged reader. Single-page mode (or a
 * portrait window) yields one page per slot. Double-page mode pairs pages after a solo
 * cover — [0], [1,2], [3,4], … — with the trailing page left solo when the count is odd.
 */
private fun buildReaderSlots(pageCount: Int, twoUp: Boolean): List<List<Int>> {
    if (!twoUp || pageCount <= 1) return (0 until pageCount).map { listOf(it) }
    val slots = ArrayList<List<Int>>()
    slots.add(listOf(0)) // cover alone, so subsequent spreads pair on natural boundaries
    var i = 1
    while (i < pageCount) {
        if (i + 1 < pageCount) { slots.add(listOf(i, i + 1)); i += 2 }
        else { slots.add(listOf(i)); i += 1 }
    }
    return slots
}

/**
 * Draws translated speech bubbles aligned to the displayed page art. Computes the on-screen
 * rectangle of the image given the active [FitMode] (the AsyncImage uses
 * `fitMode.contentScale()`), then maps every [TransBlock]'s image-pixel coords into that
 * rectangle. Lives inside the SAME transformed container as the image, so it inherits zoom /
 * pan for free.
 */
@Composable
private fun TranslationOverlay(
    translation: PageTranslation,
    fitMode: FitMode,
    boxMaxWidth: Float,
    boxMaxHeight: Float,
) {
    val density = LocalDensity.current
    val imgW = translation.imageWidth.toFloat()
    val imgH = translation.imageHeight.toFloat()
    if (boxMaxWidth <= 0f || boxMaxHeight <= 0f) return

    // Per-axis scale derives from the fit mode so bubbles stay glued to the art under
    // FIT_CENTER / FIT_WIDTH / FIT_HEIGHT alike.
    val scale = when (fitMode) {
        FitMode.FIT_CENTER -> min(boxMaxWidth / imgW, boxMaxHeight / imgH)
        FitMode.FIT_WIDTH -> boxMaxWidth / imgW
        FitMode.FIT_HEIGHT -> boxMaxHeight / imgH
    }
    val dispW = imgW * scale
    val dispH = imgH * scale
    val offX = (boxMaxWidth - dispW) / 2f
    val offY = (boxMaxHeight - dispH) / 2f

    Box(modifier = Modifier.fillMaxSize()) {
        // 1) Repaint each balloon by filling its 8-direction ray-cast polygon with the
        //    sampled balloon colour — covers the original text and conforms to the
        //    bubble shape instead of stamping a rectangle.
        Canvas(modifier = Modifier.matchParentSize()) {
            translation.blocks.forEach { b ->
                if (b.translated.isBlank() || b.fillPolygon.size < 3) return@forEach
                val path = Path()
                b.fillPolygon.forEachIndexed { i, (px, py) ->
                    val x = offX + px * scale
                    val y = offY + py * scale
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, Color(b.fillArgb))
            }
        }

        // 2) Draw the translated text centred over each balloon's text rect.
        translation.blocks.forEach { b ->
            if (b.translated.isBlank()) return@forEach
            val rx = offX + b.left * scale
            val ry = offY + b.top * scale
            val rw = b.width * scale
            val rh = b.height * scale
            if (rw <= 0f || rh <= 0f) return@forEach

            // Auto-fit font: base size from the bubble height, then shrink when the
            // text is long relative to the bubble area so it stays inside the balloon.
            val baseSp = (rh / density.density / 3f).coerceIn(8f, 18f)
            val len = b.translated.length.coerceAtLeast(1)
            val capacity = (rw * rh) / (baseSp * baseSp * density.density * density.density * 0.5f)
            val shrink = if (capacity > 0f) (capacity / len).coerceIn(0.45f, 1f) else 1f
            val fontSp = (baseSp * shrink).coerceIn(7f, 18f)

            // Only stamp a rounded-rect fill when ray-casting produced no polygon
            // (the Canvas above already painted the balloon otherwise).
            val needsRectFill = b.fillPolygon.size < 3

            Box(
                modifier = Modifier
                    .offset { IntOffset(rx.roundToInt(), ry.roundToInt()) }
                    .size(
                        width = with(density) { rw.toDp() },
                        height = with(density) { rh.toDp() },
                    )
                    .then(
                        if (needsRectFill) Modifier.clip(RoundedCornerShape(6.dp)).background(Color(b.fillArgb))
                        else Modifier
                    )
                    .padding(horizontal = 3.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = b.translated,
                    color = Color(b.textArgb),
                    textAlign = TextAlign.Center,
                    fontSize = fontSp.sp,
                    lineHeight = (fontSp * 1.1f).sp,
                    softWrap = true,
                    overflow = TextOverflow.Clip,
                    maxLines = 64,
                )
            }
        }
    }
}

@Composable
private fun WebtoonReader(state: AppState, pages: List<MangaPage>, colorFilter: ColorFilter?, listState: LazyListState) {
    val wf = (1f - state.webtoonSidePadding.toFloat()).coerceIn(0.4f, 1f)
    LazyColumn(Modifier.fillMaxSize(), state = listState, horizontalAlignment = Alignment.CenterHorizontally) {
        itemsIndexed(pages, key = { index, _ -> index }, contentType = { _, _ -> "page" }) { index, page ->
            SubcomposeAsyncImage(
                model = state.pageDisplayModel(index),
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.FillWidth,
                colorFilter = colorFilter,
                modifier = Modifier.fillMaxWidth(wf),
                loading = {
                    Box(Modifier.fillMaxWidth().height(480.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = LocalNyoraAccent.current.color)
                    }
                },
            )
        }
    }
}

@Composable
private fun VerticalReader(state: AppState, pages: List<MangaPage>, colorFilter: ColorFilter?, listState: LazyListState) {
    val wf = (1f - state.webtoonSidePadding.toFloat()).coerceIn(0.4f, 1f)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp), // Zero gap for modern feel
    ) {
        itemsIndexed(pages, key = { index, _ -> index }, contentType = { _, _ -> "page" }) { index, page ->
            SubcomposeAsyncImage(
                model = state.pageDisplayModel(index),
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.FillWidth,
                colorFilter = colorFilter,
                modifier = Modifier.fillMaxWidth(wf),
                loading = {
                    Box(Modifier.fillMaxWidth().height(480.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = LocalNyoraAccent.current.color)
                    }
                },
            )
        }
    }
}

@Composable
private fun ReaderSettingsDialog(
    state: AppState,
    onDismiss: () -> Unit,
    onOpenColor: () -> Unit,
) {
    val accent = LocalNyoraAccent.current.color
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .glassCard(shape = RoundedCornerShape(24.dp), fill = NyoraTokens.surface1)
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader(title = "Reader Settings", subtitle = "Display")

            SystemTag(text = "Display Mode")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ReaderMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = state.readerMode == mode,
                        onClick = {
                            state.readerMode = mode
                            state.saveMangaPrefs()
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, ReaderMode.entries.size),
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            ReaderToggleRow("Right-to-left layout", state.rtlReading) { state.rtlReading = it }
            ReaderToggleRow("Show page numbers", state.showPageNumbers) { state.showPageNumbers = it }
            ReaderToggleRow("Enable prefetching", state.prefetchEnabled) { state.prefetchEnabled = it }

            // Auto-scroll speed (1 slow .. 10 fast), matches nyora-mac.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-scroll speed", style = MaterialTheme.typography.bodyLarge, color = NyoraTokens.onSurfaceBody)
                Text("${'$'}{state.autoScrollLevel} / 10", color = accent, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = state.autoScrollLevel.toFloat(),
                onValueChange = { state.autoScrollLevel = it.roundToInt().coerceIn(1, 10) },
                onValueChangeFinished = { state.persistSettings() },
                valueRange = 1f..10f,
                steps = 8,
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = NyoraTokens.glass4),
            )

            // Webtoon/vertical side padding (mac zoom-out) — narrow the strip on wide monitors.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Webtoon side padding", style = MaterialTheme.typography.bodyLarge, color = NyoraTokens.onSurfaceBody)
                Text("${'$'}{(state.webtoonSidePadding * 100).roundToInt()}%", color = accent, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = state.webtoonSidePadding.toFloat(),
                onValueChange = { state.webtoonSidePadding = it.toDouble().coerceIn(0.0, 0.45) },
                onValueChangeFinished = { state.persistSettings() },
                valueRange = 0f..0.45f,
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = NyoraTokens.glass4),
            )

            HorizontalDivider(color = NyoraTokens.glass2)

            // ── Translation ───────────────────────────────────────────────────────────
            SystemTag(text = "Translation")
            ReaderToggleRow("Translate pages", state.translateEnabled) { state.toggleTranslate() }
            if (state.translateEnabled) {
                var langOpen by remember { mutableStateOf(false) }
                val languages = listOf(
                    "English" to "en", "Spanish" to "es", "French" to "fr", "German" to "de",
                    "Indonesian" to "id", "Portuguese" to "pt", "Russian" to "ru", "Arabic" to "ar",
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Language", style = MaterialTheme.typography.bodyLarge, color = NyoraTokens.onSurfaceBody)
                    Box {
                        TextButton(onClick = { langOpen = true }) {
                            Text(
                                text = languages.firstOrNull { it.second == state.translateTarget }?.first
                                    ?: state.translateTarget.uppercase(),
                                color = accent,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        DropdownMenu(expanded = langOpen, onDismissRequest = { langOpen = false }) {
                            languages.forEach { (label, code) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            label,
                                            color = if (state.translateTarget == code) accent else NyoraTokens.onSurfaceBody,
                                            fontWeight = if (state.translateTarget == code) FontWeight.Black else FontWeight.Normal,
                                        )
                                    },
                                    onClick = { state.changeTranslateTarget(code); langOpen = false },
                                )
                            }
                        }
                    }
                }

                // ── OCR source language (must match the page's text language) ──
                // BCP-47 tags for the Windows OCR engine; the matching Windows
                // language pack must be installed for recognition to work.
                val ocrLangs = listOf(
                    "Auto (source language)" to "auto",
                    "Japanese" to "ja",
                    "Chinese (Simplified)" to "zh-Hans",
                    "Chinese (Traditional)" to "zh-Hant",
                    "Korean" to "ko",
                    "English" to "en",
                )
                var ocrOpen by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Source (OCR)", style = MaterialTheme.typography.bodyLarge, color = NyoraTokens.onSurfaceBody)
                    Box {
                        TextButton(onClick = { ocrOpen = true }) {
                            Text(
                                text = ocrLangs.firstOrNull { it.second == state.translateLangs }?.first
                                    ?: state.translateLangs,
                                color = accent,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        DropdownMenu(expanded = ocrOpen, onDismissRequest = { ocrOpen = false }) {
                            ocrLangs.forEach { (label, code) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            label,
                                            color = if (state.translateLangs == code) accent else NyoraTokens.onSurfaceBody,
                                            fontWeight = if (state.translateLangs == code) FontWeight.Black else FontWeight.Normal,
                                        )
                                    },
                                    onClick = { state.changeTranslateLangs(code); ocrOpen = false },
                                )
                            }
                        }
                    }
                }

                // ── AI refinement (polish the machine-translation draft) ──
                AiRefineRow(state = state, accent = accent)
            }
            if (state.translateUnavailable) {
                Text(
                    "Windows OCR couldn't run. Install the source language's OCR support " +
                        "under Windows Settings ▸ Time & language ▸ Language & region.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NyoraTokens.onSurfaceMuted,
                )
            }

            HorizontalDivider(color = NyoraTokens.glass2)

            // ── Color correction ──────────────────────────────────────────────────────
            SystemTag(text = "Color")
            TextButton(onClick = onOpenColor, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Tune, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Color correction", color = NyoraTokens.onSurfaceBody)
                Spacer(Modifier.weight(1f))
                Text("Open", color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ReaderToggleRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val accent = LocalNyoraAccent.current.color
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = NyoraTokens.onSurfaceBody)
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedThumbColor = accent, checkedTrackColor = accent.copy(alpha = 0.5f))
        )
    }
}

// =======================================================================================
// Navigation helpers
// =======================================================================================

/**
 * Paginate by [delta] within the current chapter. The target is clamped to
 * `[0, lastPage]`; an attempt to move past either edge is NOT a dead click — it rolls
 * over into the previous / next chapter via [goAdjacentChapter]. In-range moves simply
 * record the new page.
 */
private fun navigateEdgeAware(state: AppState, delta: Int) {
    val last = state.readerPages.size - 1
    if (last < 0) return
    // Double-page: step a whole spread at a time so a click/key advances past both halves.
    // readerSlots is populated only while the landscape two-up paged reader is on screen.
    val slots = state.readerSlots
    if (slots.isNotEmpty() && (delta == 1 || delta == -1)) {
        val curSlot = slots.indexOfFirst { state.readerCurrentPage in it }
        if (curSlot >= 0) {
            when (val nextSlot = curSlot + delta) {
                -1 -> goAdjacentChapter(state, -1)                  // before first spread
                slots.size -> goAdjacentChapter(state, +1)          // past last spread
                else -> slots[nextSlot].firstOrNull()?.let { state.recordReaderPage(it) }
            }
            return
        }
    }
    val target = state.readerCurrentPage + delta
    when {
        target < 0 -> goAdjacentChapter(state, -1)   // before first page -> prev chapter
        target > last -> goAdjacentChapter(state, +1) // past last page -> next chapter
        else -> state.recordReaderPage(target)
    }
}

/**
 * Resolve the chapter [direction] steps away (+1 = next, -1 = prev) from the open
 * [AppState.readerChapter] inside [AppState.readerManga].chapters and open it. No-op when
 * there is no such neighbour (already at the first / last chapter).
 */
private fun hasAdjacentChapter(state: AppState, direction: Int): Boolean {
    val manga = state.readerManga ?: return false
    val current = state.readerChapter ?: return false
    val chapters = manga.chapters
    val idx = chapters.indexOfFirst { it.id == current.id }
    if (idx < 0) return false
    return (idx + direction * chapterNextDelta(chapters)) in chapters.indices
}

private fun goAdjacentChapter(state: AppState, direction: Int) {
    val manga = state.readerManga ?: return
    val current = state.readerChapter ?: return
    val chapters: List<MangaChapter> = manga.chapters
    if (chapters.isEmpty()) return
    val idx = chapters.indexOfFirst { it.id == current.id }
    if (idx < 0) return
    // direction: +1 = next (higher-numbered) chapter, -1 = previous. The source
    // array order varies (oldest-first vs newest-first), so map to an index step
    // via the detected ordering rather than assuming +1 is always "next".
    val targetIdx = idx + direction * chapterNextDelta(chapters)
    if (targetIdx !in chapters.indices) return
    state.openChapter(manga, chapters[targetIdx])
}

/**
 * Index step that moves to the NEXT (higher-numbered) chapter. Chapter arrays are
 * oldest-first on some sources (MangaDex) and newest-first on others (many
 * scanlation sites), so we detect the ordering from chapter numbers: ascending
 * (first < last) ⇒ +1, descending ⇒ -1. Falls back to +1 when indistinguishable.
 */
internal fun chapterNextDelta(chapters: List<MangaChapter>): Int {
    if (chapters.size < 2) return 1
    val a = chapters.first().number
    val b = chapters.last().number
    return if (a != b) { if (a < b) 1 else -1 } else 1
}

// =======================================================================================
// Small local helpers (kept private to avoid polluting the design-system surface)
// =======================================================================================

/** Make the root focusable so the key listener receives events. */
private fun Modifier.androidxFocusable(requester: FocusRequester): Modifier =
    this
        .focusRequester(requester)
        .focusable()

/**
 * AI-polish selector inside the reader Translation settings: Off / Windows AI /
 * Custom key, with a status hint. The full BYOK fields live in the main Settings
 * screen (Translation section); here we just pick the mode and surface state.
 */
@Composable
private fun AiRefineRow(state: AppState, accent: Color) {
    var open by remember { mutableStateOf(false) }
    val modes = listOf(
        "Off" to AiMode.OFF,
        "Windows AI" to AiMode.WINDOWS,
        "Custom key" to AiMode.BYOK,
    )
    // Probe Windows AI once when the row first appears so the hint is accurate.
    LaunchedEffect(state.aiMode) {
        if (state.aiMode == AiMode.WINDOWS && state.windowsAiAvailable == null) state.refreshWindowsAi()
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("AI polish", style = MaterialTheme.typography.bodyLarge, color = NyoraTokens.onSurfaceBody)
        Box {
            TextButton(onClick = { open = true }) {
                Text(
                    text = modes.firstOrNull { it.second == state.aiMode }?.first ?: "Off",
                    color = accent,
                    fontWeight = FontWeight.Bold,
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                modes.forEach { (label, mode) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                label,
                                color = if (state.aiMode == mode) accent else NyoraTokens.onSurfaceBody,
                                fontWeight = if (state.aiMode == mode) FontWeight.Black else FontWeight.Normal,
                            )
                        },
                        onClick = { state.changeAiMode(mode); open = false },
                    )
                }
            }
        }
    }
    val hint = when (state.aiMode) {
        AiMode.OFF -> null
        AiMode.WINDOWS -> when (state.windowsAiAvailable) {
            true -> "On-device Phi Silica ready."
            false -> "Windows AI unavailable on this PC — using machine translation. Try a Custom key."
            null -> "Checking Windows AI…"
        }
        AiMode.BYOK ->
            if (state.byokApiKey.isBlank()) "Add your API key in Settings ▸ Translation."
            else "Polishing with ${state.byokModel}."
    }
    if (hint != null) {
        Text(hint, style = MaterialTheme.typography.bodySmall, color = NyoraTokens.onSurfaceMuted)
    }
}
