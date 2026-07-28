package com.nyora.windows.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyora.hasan72341.shared.scrobbling.ScrobblerManga
import com.nyora.hasan72341.shared.scrobbling.ScrobblerService
import com.nyora.windows.AppState
import com.nyora.windows.ui.theme.*

/**
 * Multi-service tracker settings (TS-014). Generalized from the former
 * AniList-token-paste panel to every service the shared scrobbler abstraction
 * supports — AniList, MyAnimeList, Kitsu and Shikimori — each with a real OAuth
 * (or, for Kitsu, password) login and per-service search. Shared verbatim with
 * the Windows desktop variant.
 */
@Composable
fun TrackerScreen(state: AppState, onBack: () -> Unit) {
    val accent = LocalNyoraAccent.current.color

    // Recompute authorized services once when the screen opens.
    LaunchedEffect(Unit) { state.refreshTrackerAuth() }

    // Every tracker the shared module defines is surfaced. Kitsu and Shikimori
    // were filtered out here, but they are not in ScrobblerService at all — the
    // screen was written against an API only the macOS (Swift) side ever had.
    val visibleServices = ScrobblerService.entries

    val scrollState = rememberScrollState()
    NyoraScrollContainer(
        adapter = rememberScrollbarAdapter(scrollState),
        modifier = Modifier.fillMaxSize(),
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {

        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                color = NyoraTokens.surface1,
                shape = RoundedCornerShape(12.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = NyoraTokens.onSurfaceHigh,
                    )
                }
            }

            Column {
                SectionHeader(
                    title = "Trackers",
                    subtitle = "Sync your progress across services",
                )
            }

            Spacer(Modifier.weight(1f))

            SystemTag(
                text = "${state.trackerAuthorized.size}/${visibleServices.size} linked",
                color = if (state.trackerAuthorized.isNotEmpty()) NyoraTokens.mint else NyoraTokens.onSurfaceFaint,
            )
        }

        // ── Scrobble-on-read toggle ───────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth()
                .glassCard(shape = RoundedCornerShape(16.dp), fill = NyoraTokens.surface1)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Scrobble on chapter open", color = NyoraTokens.onSurfaceHigh,
                    fontWeight = FontWeight.SemiBold)
                Text("Push your progress to linked trackers as you read.",
                    color = NyoraTokens.onSurfaceMuted, fontSize = 12.sp)
            }
            Switch(checked = state.scrobbleOnRead, onCheckedChange = { state.scrobbleOnRead = it })
        }

        // ── A card per service ────────────────────────────────────────────────
        visibleServices.forEach { service ->
            ServiceCard(service = service, state = state, accent = accent)
        }

        Spacer(Modifier.height(48.dp))
    }
    }
}

// ----- Private composables -------------------------------------------------

@Composable
private fun ServiceCard(service: ScrobblerService, state: AppState, accent: androidx.compose.ui.graphics.Color) {
    val linked = service.slug in state.trackerAuthorized
    val busy = state.trackerBusy == service.slug
    val results = state.trackerResults[service.slug].orEmpty()

    // Per-card local input state.
    var query by remember(service) { mutableStateOf("") }
    var email by remember(service) { mutableStateOf("") }
    var password by remember(service) { mutableStateOf("") }
    var showPassword by remember(service) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(shape = RoundedCornerShape(20.dp), fill = NyoraTokens.surface1)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header row: name + linked tag.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = service.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = NyoraTokens.onSurfaceHigh,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = accent,
                )
                Spacer(Modifier.width(10.dp))
            }
            SystemTag(
                text = if (linked) "Linked" else "Not linked",
                color = if (linked) NyoraTokens.mint else NyoraTokens.onSurfaceFaint,
            )
        }

        if (!linked) {
            // ── Login affordance ──────────────────────────────────────────────
            Text(
                text = "Sign in opens ${service.title} in your browser to authorize Nyora.",
                style = MaterialTheme.typography.bodySmall,
                color = NyoraTokens.onSurfaceMuted,
                lineHeight = 18.sp,
            )
            Button(
                onClick = { state.trackerLogin(service) },
                enabled = !busy,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
            ) {
                Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Log in with ${service.title}", fontWeight = FontWeight.Bold)
            }
            // Surface the consent URL in case the browser opened off-screen.
            state.trackerLoginUrl?.takeIf { busy }?.let { url ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = NyoraTokens.surface1,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = "If the browser didn't open, visit:\n$url",
                        style = MaterialTheme.typography.bodySmall,
                        color = NyoraTokens.onSurfaceMuted,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        } else {
            // ── Signed-in: search + sign-out ──────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Search ${service.title}") },
                    singleLine = true,
                    colors = trackerFieldColors(accent),
                    shape = RoundedCornerShape(14.dp),
                )
                Button(
                    onClick = { state.trackerSearch(service, query) },
                    enabled = !busy && query.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(18.dp))
                }
                OutlinedButton(
                    onClick = { state.trackerLogout(service) },
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp), tint = accent)
                    Spacer(Modifier.width(6.dp))
                    Text("Sign out", color = accent, fontWeight = FontWeight.Bold)
                }
            }

            AnimatedVisibility(visible = results.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader(
                        title = "Results",
                        subtitle = "${results.size} title${if (results.size != 1) "s" else ""} found",
                    )
                    results.take(20).forEach { manga ->
                        ScrobblerMangaRow(manga = manga, accent = accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScrobblerMangaRow(manga: ScrobblerManga, accent: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(shape = RoundedCornerShape(16.dp), fill = NyoraTokens.surface1)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        manga.cover?.let { cover ->
            AnimeAsyncImage(
                model = cover,
                contentDescription = manga.name,
                modifier = Modifier
                    .width(56.dp)
                    .aspectRatio(0.72f),
                shape = RoundedCornerShape(10.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = manga.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = NyoraTokens.onSurfaceHigh,
                maxLines = 2,
            )
            manga.altName?.takeIf { it.isNotBlank() && it != manga.name }?.let { alt ->
                Text(
                    text = alt,
                    style = MaterialTheme.typography.bodySmall,
                    color = NyoraTokens.onSurfaceMuted,
                    maxLines = 1,
                )
            }
        }
        if (manga.isBestMatch) {
            Surface(color = accent.copy(alpha = 0.18f), shape = RoundedCornerShape(8.dp)) {
                Text(
                    text = "Best match",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    color = accent,
                )
            }
        }
    }
}

@Composable
private fun trackerFieldColors(accent: androidx.compose.ui.graphics.Color) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = accent,
    unfocusedBorderColor = NyoraTokens.hairlineFaint,
    focusedLabelColor = accent,
    cursorColor = accent,
)
