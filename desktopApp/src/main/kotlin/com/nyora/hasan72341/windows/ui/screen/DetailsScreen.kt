package com.nyora.windows.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyora.windows.AppState
import com.nyora.windows.bridge.CategoryDto
import com.nyora.windows.ui.reader.AlternativesDialog
import com.nyora.windows.ui.theme.AnimeAsyncImage
import com.nyora.windows.ui.theme.LocalNyoraAccent
import com.nyora.hasan72341.shared.scrobbling.ScrobblerService
import com.nyora.windows.ui.theme.NyoraButton
import com.nyora.windows.ui.theme.NyoraScrollContainer
import com.nyora.windows.ui.theme.NyoraTokens
import com.nyora.windows.ui.theme.SystemTag
import com.nyora.windows.ui.theme.glassCard
import com.nyora.windows.ui.theme.glassOverlay
import com.nyora.windows.ui.theme.hoverLift
import com.nyora.hasan72341.shared.model.Manga
import com.nyora.hasan72341.shared.model.MangaChapter
import com.nyora.hasan72341.shared.model.MangaSource

/**
 * DetailsScreen (W10) — the hero screen for a single manga.
 *
 * A flat [NyoraTokens.bg] backdrop with a semi-opaque scrim hosts the oversized title and
 * [SystemTag] chips. Below it, a glass-rendered chapter list with sort asc/desc, scanlator
 * filtering and per-chapter read/unread indicators (derived from [AppState.history]).
 * A "Find on other sources" action opens [AlternativesDialog]; the category affordance lets
 * you add/remove the manga to/from categories and rename/delete them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailsScreen(state: AppState) {
    val manga = state.selectedManga
    val accent = LocalNyoraAccent.current.color

    var showCategoryDialog by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showAlt by remember { mutableStateOf(false) }
    var showTrack by remember { mutableStateOf(false) }

    // Chapter list controls.
    var sortAscending by remember(manga?.id) { mutableStateOf(false) }
    var scanlatorFilter by remember(manga?.id) { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(NyoraTokens.bg)) {
        if (manga != null) {
            // Full-bleed blurred cover backdrop: the cover's colors bleed behind the hero,
            // heavily blurred + dimmed, then a vertical scrim dissolves it into the page so
            // the title and chapter list stay legible. Falls back to the flat bg when absent.
            val backdropModel = state.coverProxyUrl(manga.coverUrl, state.activeSource).ifBlank { null }
            if (backdropModel != null) {
                AsyncImage(
                    model = backdropModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.6f)
                        .blur(40.dp)
                        .graphicsLayer { alpha = 0.45f },
                )
            }
            // Vertical scrim: a whisper of accent up top -> solid bg by mid-screen.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to accent.copy(alpha = 0.10f),
                            0.30f to NyoraTokens.bg.copy(alpha = 0.72f),
                            0.65f to NyoraTokens.bg,
                        ),
                    ),
            )

            // Derive history "read" lookup + scanlator universe.
            val readChapterIds = remember(state.history, manga.id) {
                state.history
                    .asSequence()
                    .filter { it.manga.id == manga.id }
                    .map { it.chapterId }
                    .toSet()
            }
            val scanlators = remember(manga.chapters) {
                manga.chapters.mapNotNull { it.scanlator?.takeIf { s -> s.isNotBlank() } }.distinct()
            }

            val visibleChapters = remember(manga.chapters, sortAscending, scanlatorFilter) {
                manga.chapters
                    .let { list ->
                        scanlatorFilter?.let { sc -> list.filter { it.scanlator == sc } } ?: list
                    }
                    .let { list ->
                        if (sortAscending) list.sortedBy { it.number } else list.sortedByDescending { it.number }
                    }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // ── Floating top bar ──────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = { state.showDetails = false },
                    )

                    Spacer(Modifier.weight(1f))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlassIconButton(
                            icon = Icons.Outlined.TravelExplore,
                            contentDescription = "Find on other sources",
                            onClick = { showAlt = true },
                        )
                        if (state.categories.isNotEmpty()) {
                            GlassIconButton(
                                icon = Icons.Default.Label,
                                contentDescription = "Categories",
                                onClick = { showCategoryDialog = true },
                            )
                        }
                        GlassIconButton(
                            icon = if (state.isFavourited) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favourite",
                            tint = if (state.isFavourited) accent else NyoraTokens.onSurfaceHigh,
                            onClick = { state.toggleFavourite() },
                        )
                    }
                }

                val listState = rememberLazyListState()
                NyoraScrollContainer(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    // ── Hero block: cover thumbnail + oversized title + chips ──────
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(28.dp),
                        ) {
                            AnimeAsyncImage(
                                model = state.coverProxyUrl(manga.coverUrl, state.activeSource).ifBlank { null },
                                contentDescription = manga.title,
                                modifier = Modifier
                                    .widthIn(min = 120.dp, max = 200.dp)
                                    .fillMaxWidth(0.25f)
                                    .aspectRatio(0.72f)
                                    .hoverLift(shape = RoundedCornerShape(22.dp), scaleTo = 1.04f),
                                shape = RoundedCornerShape(22.dp),
                            )


                            Column(
                                modifier = Modifier.weight(1f).padding(top = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                // Show the source's friendly display name, never the raw
                                // "JS_<ID>" script ref the manga carries.
                                SystemTag(text = state.sourceFor(manga)?.name ?: manga.source.name.removePrefix("JS_"))
                                Text(
                                    text = manga.title,
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = (-0.5).sp,
                                    color = NyoraTokens.onSurfaceHigh,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (manga.authors.isNotEmpty()) {
                                    Text(
                                        text = manga.authors.joinToString(", "),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NyoraTokens.onSurfaceMuted,
                                    )
                                }
                                // Status / rating chips.
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    manga.state?.let { ChipTag(it.name, accent) }
                                    manga.contentRating?.let { ChipTag(it.name) }
                                    if (manga.rating > 0f) {
                                        ChipTag("${"%.1f".format(manga.rating * 10)}")
                                    }
                                    ChipTag("${manga.chapters.size} CH")
                                }

                                Spacer(Modifier.height(2.dp))

                                // Primary read CTA + Find-on-other-sources.
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val firstUnread = visibleChapters.lastOrNull { it.id !in readChapterIds }
                                        ?: manga.chapters.firstOrNull()
                                    if (firstUnread != null) {
                                        NyoraButton(
                                            text = if (readChapterIds.isEmpty()) "Start Reading" else "Continue",
                                            icon = Icons.Default.PlayArrow,
                                            onClick = { state.openChapter(manga, firstUnread) },
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { showAlt = true },
                                        shape = RoundedCornerShape(14.dp),
                                    ) {
                                        Icon(
                                            Icons.Outlined.TravelExplore,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("Other sources")
                                    }
                                    OutlinedButton(
                                        onClick = { showTrack = true },
                                        shape = RoundedCornerShape(14.dp),
                                    ) {
                                        val linked = state.linkedTrackers(manga.id).isNotEmpty()
                                        Text(if (linked) "Tracking ✓" else "Track")
                                    }
                                }
                            }
                        }
                    }

                    // ── Synopsis ───────────────────────────────────────────────────
                    if (manga.description.isNotBlank()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .glassCard(shape = RoundedCornerShape(20.dp), fill = NyoraTokens.surface1)
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                SystemTag(text = "Synopsis")
                                Text(
                                    text = manga.description,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = NyoraTokens.onSurfaceBody,
                                    lineHeight = 24.sp,
                                )
                            }
                        }
                    }

                    // ── Tags ───────────────────────────────────────────────────────
                    if (manga.tags.isNotEmpty()) {
                        item {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                manga.tags.take(24).forEach { tag ->
                                    Box(
                                        modifier = Modifier.glassOverlay(
                                            shape = RoundedCornerShape(50),
                                            fill = NyoraTokens.surface1,
                                        ),
                                    ) {
                                        Text(
                                            text = tag.title,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = NyoraTokens.onSurfaceBody,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── Chapter list header: count + sort + scanlator filter ───────
                    item {
                        ChapterControlsRow(
                            count = visibleChapters.size,
                            sortAscending = sortAscending,
                            onToggleSort = { sortAscending = !sortAscending },
                            scanlators = scanlators,
                            scanlatorFilter = scanlatorFilter,
                            onSelectScanlator = { scanlatorFilter = it },
                            onShowDownload = { showDownloadDialog = true },
                        )
                    }

                    items(visibleChapters, key = { it.id }) { chapter ->
                        ChapterGlassItem(
                            manga = manga,
                            chapter = chapter,
                            state = state,
                            isRead = chapter.id in readChapterIds,
                            accent = accent,
                        )
                    }
                }
                }
            }
        }

        if (state.detailsLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accent)
            }
        }
    }

    // ── Dialogs ────────────────────────────────────────────────────────────────
    if (showCategoryDialog && manga != null) {
        CategoryManagerDialog(
            mangaId = manga.id,
            categories = state.categories,
            assigned = state.mangaCategories,
            onAdd = { catId -> state.addMangaToCategory(manga.id, catId) },
            onRemove = { catId -> state.removeMangaFromCategory(manga.id, catId) },
            onRename = { catId, title -> state.renameCategory(catId, title) },
            onDelete = { catId -> state.deleteCategory(catId) },
            onDismiss = { showCategoryDialog = false },
        )
    }

    if (showAlt && manga != null) {
        AlternativesDialog(state, manga.title) { showAlt = false }
    }

    if (showTrack && manga != null) {
        TrackDialog(state, manga) { showTrack = false }
    }

    if (showDownloadDialog && manga != null) {
        val source = state.activeSource ?: state.sourceFor(manga)
        if (source != null) {
            DownloadChaptersDialog(
                source = source,
                manga = manga,
                chapters = manga.chapters,
                onDownload = { state.downloadChapters(source, manga, it) },
                onDismiss = { showDownloadDialog = false },
            )
        }
    }
}

// ── Top-bar glass icon button ──────────────────────────────────────────────────

@Composable
private fun GlassIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = NyoraTokens.onSurfaceHigh,
) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .hoverLift(shape = RoundedCornerShape(14.dp), scaleTo = 1.08f)
            .glassOverlay(shape = RoundedCornerShape(14.dp), fill = NyoraTokens.surface1)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

// ── Status / meta chip ──────────────────────────────────────────────────────────

@Composable
private fun ChipTag(text: String, accent: Color? = null) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (accent != null) accent.copy(alpha = 0.10f) else NyoraTokens.surface1),
    ) {
        Text(
            text = text.uppercase(),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.4.sp,
            color = accent ?: NyoraTokens.onSurfaceBody,
        )
    }
}

// ── Chapter controls row (count + sort + scanlator dropdown) ────────────────────

@Composable
private fun ChapterControlsRow(
    count: Int,
    sortAscending: Boolean,
    onToggleSort: () -> Unit,
    scanlators: List<String>,
    scanlatorFilter: String?,
    onSelectScanlator: (String?) -> Unit,
    onShowDownload: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SystemTag(text = "Chapters")
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$count chapters",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = NyoraTokens.onSurfaceHigh,
            )
        }

        // Bulk download button.
        Box(
            modifier = Modifier
                .hoverLift(shape = RoundedCornerShape(12.dp), scaleTo = 1.06f)
                .glassOverlay(shape = RoundedCornerShape(12.dp), fill = NyoraTokens.surface1)
                .clickable { onShowDownload() }
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "Bulk Download",
                    tint = NyoraTokens.onSurfaceBody,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Download",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NyoraTokens.onSurfaceBody,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Scanlator filter dropdown (only when there are multiple scanlators).
        if (scanlators.size > 1) {
            ScanlatorDropdown(
                scanlators = scanlators,
                selected = scanlatorFilter,
                onSelect = onSelectScanlator,
            )
            Spacer(Modifier.width(12.dp))
        }

        // Sort asc/desc toggle.
        Box(
            modifier = Modifier
                .hoverLift(shape = RoundedCornerShape(12.dp), scaleTo = 1.06f)
                .glassOverlay(shape = RoundedCornerShape(12.dp), fill = NyoraTokens.surface1)
                .clickable { onToggleSort() }
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.Sort,
                    contentDescription = "Sort order",
                    tint = NyoraTokens.onSurfaceBody,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = if (sortAscending) "Oldest" else "Newest",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NyoraTokens.onSurfaceBody,
                )
            }
        }
    }
}

@Composable
private fun DownloadChaptersDialog(
    source: MangaSource,
    manga: Manga,
    chapters: List<MangaChapter>,
    onDownload: (List<MangaChapter>) -> Unit,
    onDismiss: () -> Unit,
) {
    var fromIndex by remember { mutableStateOf(chapters.size - 1) }
    var toIndex by remember { mutableStateOf(0) }
    val selectedUrls = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NyoraTokens.surface1,
        title = { Text("Download Chapters", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Range pickers
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("From", style = MaterialTheme.typography.labelMedium, color = NyoraTokens.onSurfaceMuted)
                        Spacer(Modifier.height(4.dp))
                        RangeDropdown(chapters, fromIndex) { fromIndex = it }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("To", style = MaterialTheme.typography.labelMedium, color = NyoraTokens.onSurfaceMuted)
                        Spacer(Modifier.height(4.dp))
                        RangeDropdown(chapters, toIndex) { toIndex = it }
                    }
                    Box(Modifier.align(Alignment.Bottom)) {
                        Button(
                            onClick = {
                                val range = if (fromIndex <= toIndex) fromIndex..toIndex else toIndex..fromIndex
                                chapters.slice(range).forEach { if (it.url !in selectedUrls) selectedUrls.add(it.url) }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = LocalNyoraAccent.current.color),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Select")
                        }
                    }
                }

                // Quick actions
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickChip("All") {
                        selectedUrls.clear()
                        selectedUrls.addAll(chapters.map { it.url })
                    }
                    QuickChip("None") { selectedUrls.clear() }
                    QuickChip("Invert") {
                        val current = selectedUrls.toList()
                        selectedUrls.clear()
                        chapters.forEach { if (it.url !in current) selectedUrls.add(it.url) }
                    }
                }

                // Chapter list
                Divider(color = NyoraTokens.onSurfaceFaint.copy(alpha = 0.1f))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(chapters) { chapter ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (chapter.url in selectedUrls) selectedUrls.remove(chapter.url) else selectedUrls.add(chapter.url)
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = chapter.url in selectedUrls,
                                onCheckedChange = {
                                    if (it == true) selectedUrls.add(chapter.url) else selectedUrls.remove(chapter.url)
                                },
                                colors = CheckboxDefaults.colors(checkedColor = LocalNyoraAccent.current.color)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(chapter.title, style = MaterialTheme.typography.bodyMedium, color = NyoraTokens.onSurfaceHigh)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val selected = chapters.filter { it.url in selectedUrls }
                    onDownload(selected)
                    onDismiss()
                },
                enabled = selectedUrls.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = LocalNyoraAccent.current.color),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Download ${selectedUrls.size} chapters")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = NyoraTokens.onSurfaceMuted)
            }
        }
    )
}

@Composable
private fun RangeDropdown(chapters: List<MangaChapter>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassOverlay(shape = RoundedCornerShape(10.dp), fill = NyoraTokens.surface1)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(chapters.getOrNull(selectedIndex)?.title ?: "Select", style = MaterialTheme.typography.bodySmall)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 300.dp)) {
            chapters.forEachIndexed { index, chapter ->
                DropdownMenuItem(
                    text = { Text(chapter.title) },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(NyoraTokens.surface1)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = NyoraTokens.onSurfaceBody)
    }
}

@Composable
private fun ScanlatorDropdown(
    scanlators: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .hoverLift(shape = RoundedCornerShape(12.dp), scaleTo = 1.06f)
                .glassOverlay(shape = RoundedCornerShape(12.dp), fill = NyoraTokens.surface1)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Icons.Default.FilterList,
                    contentDescription = "Filter scanlator",
                    tint = if (selected != null) LocalNyoraAccent.current.color else NyoraTokens.onSurfaceBody,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = selected ?: "All groups",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected != null) LocalNyoraAccent.current.color else NyoraTokens.onSurfaceBody,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(NyoraTokens.surface1),
        ) {
            DropdownMenuItem(
                text = { Text("All groups", color = NyoraTokens.onSurfaceHigh) },
                onClick = { onSelect(null); expanded = false },
                trailingIcon = {
                    if (selected == null) Icon(Icons.Default.Check, null, tint = LocalNyoraAccent.current.color)
                },
            )
            scanlators.forEach { sc ->
                DropdownMenuItem(
                    text = { Text(sc, color = NyoraTokens.onSurfaceHigh) },
                    onClick = { onSelect(sc); expanded = false },
                    trailingIcon = {
                        if (selected == sc) Icon(Icons.Default.Check, null, tint = LocalNyoraAccent.current.color)
                    },
                )
            }
        }
    }
}

// ── Chapter list item ───────────────────────────────────────────────────────────

@Composable
private fun ChapterGlassItem(
    manga: Manga,
    chapter: MangaChapter,
    state: AppState,
    isRead: Boolean,
    accent: Color,
) {
    val rowAlpha = if (isRead) 0.55f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverLift(shape = RoundedCornerShape(16.dp), scaleTo = 1.012f)
            .glassCard(
                shape = RoundedCornerShape(16.dp),
                fill = NyoraTokens.surface1,
            )
            // Open MUST use the nullable-source fallback (no activeSource?.let gate).
            .clickable { state.openChapter(manga, chapter) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).graphicsLayer { alpha = rowAlpha }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = chapter.title.ifBlank { "Chapter ${chapter.number}" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = NyoraTokens.onSurfaceHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isRead) SystemTag(text = "Read", color = NyoraTokens.onSurfaceMuted)
            }
            chapter.scanlator?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = NyoraTokens.onSurfaceFaint,
                )
            }
        }

        // Download (when a source is resolvable for this manga).
        (state.activeSource ?: state.sourceFor(manga))?.let { src ->
            IconButton(onClick = { state.downloadChapter(manga, chapter, src) }) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "Download chapter",
                    tint = NyoraTokens.onSurfaceMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.width(4.dp))

        Button(
            onClick = { state.openChapter(manga, chapter) },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRead) NyoraTokens.surface1 else accent,
                contentColor = if (isRead) NyoraTokens.onSurfaceBody else Color.White,
            ),
        ) {
            Text(if (isRead) "Reread" else "Read", fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── Category manager dialog (add/remove + rename/delete) ────────────────────────

@Composable
private fun CategoryManagerDialog(
    mangaId: String,
    categories: List<CategoryDto>,
    assigned: List<CategoryDto>,
    onAdd: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = LocalNyoraAccent.current.color
    val assignedIds = remember(assigned) { assigned.map { it.id }.toSet() }
    var renameTarget by remember { mutableStateOf<CategoryDto?>(null) }
    var renameText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NyoraTokens.surface1,
        title = { Text("Categories", fontWeight = FontWeight.Bold, color = NyoraTokens.onSurfaceHigh) },
        text = {
            if (renameTarget != null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SystemTag(text = "Rename category")
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassOverlay(shape = RoundedCornerShape(12.dp), fill = NyoraTokens.surface1)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        BasicTextField(
                            value = renameText,
                            onValueChange = { renameText = it },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(color = NyoraTokens.onSurfaceHigh),
                            cursorBrush = SolidColor(accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { cat ->
                        val isAssigned = cat.id in assignedIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isAssigned) accent.copy(alpha = 0.12f) else NyoraTokens.surface1)
                                .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = isAssigned,
                                onCheckedChange = { if (it) onAdd(cat.id) else onRemove(cat.id) },
                                colors = CheckboxDefaults.colors(checkedColor = accent),
                            )
                            Text(
                                cat.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = NyoraTokens.onSurfaceHigh,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { renameTarget = cat; renameText = cat.title }) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Rename ${cat.title}",
                                    tint = NyoraTokens.onSurfaceMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(onClick = { onDelete(cat.id) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete ${cat.title}",
                                    tint = NyoraTokens.onSurfaceMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (renameTarget != null) {
                Button(
                    onClick = {
                        val target = renameTarget
                        if (target != null && renameText.isNotBlank()) onRename(target.id, renameText.trim())
                        renameTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                ) { Text("Save") }
            } else {
                Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                    Text("Done")
                }
            }
        },
        dismissButton = {
            if (renameTarget != null) {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel", color = NyoraTokens.onSurfaceMuted)
                }
            }
        },
        shape = RoundedCornerShape(24.dp),
    )
}

/**
 * Link this manga to a tracker entry (AniList / MyAnimeList / Shikimori) so the reader
 * scrobbles progress on chapter open. One card per signed-in service: Link runs a search
 * seeded with the manga title; tapping a result stores the mangaId → remoteId link.
 */
@Composable
private fun TrackDialog(state: AppState, manga: Manga, onDismiss: () -> Unit) {
    val accent = LocalNyoraAccent.current.color
    LaunchedEffect(Unit) { state.refreshTrackerAuth() }
    val services = ScrobblerService.entries.filter { it.slug in state.trackerAuthorized }
    var expanded by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Track this manga", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.width(420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (services.isEmpty()) {
                    Text(
                        "Sign in to a tracker under Settings ▸ Tracker first.",
                        color = NyoraTokens.onSurfaceMuted,
                    )
                }
                services.forEach { service ->
                    val linkedId = state.linkedTrackers(manga.id)[service.slug]
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .glassCard(shape = RoundedCornerShape(14.dp), fill = NyoraTokens.surface2)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(service.title, color = NyoraTokens.onSurfaceHigh,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (linkedId != null) {
                                Text("Linked", color = NyoraTokens.mint, fontSize = 12.sp)
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { state.unlinkTracker(manga.id, service.slug) }) {
                                    Text("Unlink", color = NyoraTokens.onSurfaceMuted, fontSize = 12.sp)
                                }
                            } else {
                                TextButton(onClick = {
                                    expanded = service.slug
                                    state.trackerSearch(service, manga.title)
                                }) { Text("Link", color = accent, fontSize = 13.sp) }
                            }
                        }
                        if (linkedId == null && expanded == service.slug) {
                            if (state.trackerBusy == service.slug) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = accent)
                            } else {
                                val results = state.trackerResults[service.slug].orEmpty()
                                if (results.isEmpty()) {
                                    Text("No matches.", color = NyoraTokens.onSurfaceFaint, fontSize = 12.sp)
                                }
                                results.take(6).forEach { r ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                state.linkTracker(manga.id, service.slug, r.id)
                                                expanded = null
                                            }
                                            .padding(vertical = 6.dp, horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(r.name, color = NyoraTokens.onSurfaceBody, fontSize = 13.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = accent) } },
        containerColor = NyoraTokens.surface1,
        shape = RoundedCornerShape(20.dp),
    )
}
