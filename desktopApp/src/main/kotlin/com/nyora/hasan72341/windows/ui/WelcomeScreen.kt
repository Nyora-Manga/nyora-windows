package com.nyora.windows.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyora.windows.AppState
import com.nyora.windows.bridge.CatalogEntry
import com.nyora.windows.ui.theme.LocalNyoraAccent
import com.nyora.windows.ui.theme.NyoraTokens
import com.nyora.windows.ui.theme.glassCard

/**
 * First-run onboarding — a two-layer flow modelled on the web app:
 *  Layer 1 (auth): editorial hero + an auth panel (sign in / create / guest).
 *  Layer 2 (prefs): once you're in, a "Set up your shelf" card — the 18+ source
 *  preference — then Start reading finishes onboarding.
 */
@Composable
fun WelcomeScreen(state: AppState) {
    val accent = LocalNyoraAccent.current.color
    val busy = state.cloudSyncBusy
    val authed = state.cloudSyncStatus?.isAuthenticated == true

    // "auth" → the sign-in stage; "prefs" → the preferences stage. Everyone passes
    // through prefs (guest directly; signed-in/created after auth succeeds) so the
    // Start-reading CTA is the single point that finishes onboarding.
    var stage by remember { mutableStateOf("auth") }

    LaunchedEffect(Unit) { state.refreshCloudSyncStatus() }
    LaunchedEffect(authed) { if (authed) stage = "prefs" }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B0707), NyoraTokens.bg, Color.Black))),
        contentAlignment = Alignment.Center,
    ) {
        // Ambient accent wash from the top edge (native, no per-frame animation).
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(360.dp)
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.13f), Color.Transparent))),
        )
        Box(
            Modifier.align(Alignment.BottomStart).size(520.dp)
                .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.08f), Color.Transparent))),
        )

        when (stage) {
            "prefs" -> PreferencesStage(state, accent, onDone = { state.finishOnboarding() })
            else -> AuthStage(
                state = state, accent = accent, busy = busy,
                onGuest = { stage = "prefs" },
            )
        }

        // "Local Data Detected" — Merge vs Replace on sign-in (Mac parity).
        if (state.cloudConflictPending) {
            AlertDialog(
                onDismissRequest = { state.cloudConflictPending = false },
                title = { Text("Local data detected", fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "You already have library data on this device. Merge it with your " +
                            "cloud library, or replace this local data with what's in the cloud?",
                        color = NyoraTokens.onSurfaceMuted,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { state.cloudResolveConflict(replace = false) }) {
                        Text("Merge local & cloud", color = accent, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { state.cloudResolveConflict(replace = true) }) {
                        Text("Replace with cloud", color = NyoraTokens.onSurfaceMuted)
                    }
                },
                containerColor = NyoraTokens.surface2,
                shape = RoundedCornerShape(20.dp),
            )
        }
    }
}

/**
 * Re-run of the content & language preferences step, opened from Settings as a
 * full-screen overlay (see [AppState.showPreferences]). Reuses [PreferencesStage]
 * with re-run copy; "Save & apply" reseeds sources and dismisses. A top "Close"
 * bails out without changing anything.
 */
@Composable
fun PreferencesOverlay(state: AppState) {
    val accent = LocalNyoraAccent.current.color
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B0707), NyoraTokens.bg, Color.Black))),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(360.dp)
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.13f), Color.Transparent))),
        )
        TextButton(
            onClick = { state.showPreferences = false },
            modifier = Modifier.align(Alignment.TopEnd).padding(20.dp),
        ) { Text("Close", color = NyoraTokens.onSurfaceMuted, fontSize = 14.sp) }

        PreferencesStage(
            state = state, accent = accent,
            kicker = "PREFERENCES",
            title = "Languages & sources",
            sub = "Re-pick the languages you read and your content preference — this reseeds your installed sources.",
            cta = "Save & apply",
            onDone = { state.showPreferences = false },
        )
    }
}

// ── Layer 1 · auth ─────────────────────────────────────────────────────────────

@Composable
private fun AuthStage(state: AppState, accent: Color, busy: Boolean, onGuest: () -> Unit) {
    val high = NyoraTokens.onSurfaceHigh
    val body = NyoraTokens.onSurfaceBody
    val muted = NyoraTokens.onSurfaceMuted

    var authMode by remember { mutableStateOf("landing") } // landing | signin | signup
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.22f), NyoraTokens.bg),
                    center = androidx.compose.ui.geometry.Offset(120f, 80f),
                    radius = 900f,
                ),
            ),
    ) {
        val compact = maxWidth < 920.dp
        Box(
            Modifier.align(Alignment.BottomEnd).size(if (compact) 340.dp else 520.dp)
                .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.12f), Color.Transparent))),
        )

        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = if (compact) 28.dp else 56.dp, vertical = 42.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1.1f).widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(accent.copy(alpha = 0.14f))
                        .border(1.dp, accent.copy(alpha = 0.32f), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
                    Text("NYORA DESKTOP", color = high, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
                }

                Text(
                    "Your manga library, built for the big screen.",
                    color = high,
                    fontSize = if (compact) 42.sp else 58.sp,
                    lineHeight = if (compact) 48.sp else 64.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    "Browse manga, manhwa, and manhua from one calm desktop reader. Sync your shelf across devices, read offline, and keep going from the exact page you left.",
                    color = body,
                    fontSize = 16.sp,
                    lineHeight = 25.sp,
                )

                if (!compact) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HeroStat("960+", "sources", accent)
                        HeroStat("Offline", "downloads", accent)
                        HeroStat("Cloud", "sync", accent)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .widthIn(min = if (compact) 340.dp else 380.dp, max = 430.dp)
                    .glassCard(shape = RoundedCornerShape(26.dp), fill = NyoraTokens.surface1)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    when (authMode) {
                        "signin" -> "Welcome back"
                        "signup" -> "Create your account"
                        else -> "Start reading"
                    },
                    color = high,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Sign in to sync your library, or continue locally and connect later.",
                    color = muted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )

                if (authMode == "landing") {
                    MonoPrimaryButton("Sign in", accent, busy = false, enabled = !busy) {
                        authMode = "signin"; state.authMessage = null
                    }
                    MonoSecondaryButton("Create account", accent, enabled = !busy) {
                        authMode = "signup"; state.authMessage = null
                    }
                    TextButton(onClick = onGuest, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue as guest", color = muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    val signup = authMode == "signup"
                    MonoField("Email", email, accent, enabled = !busy) { email = it; state.authMessage = null }
                    MonoField("Password", password, accent, enabled = !busy, isPassword = true) {
                        password = it; state.authMessage = null
                    }
                    MonoPrimaryButton(
                        if (signup) "Create account" else "Sign in",
                        accent,
                        busy = busy,
                        enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                    ) {
                        if (signup) state.cloudRegister(email, password) else state.cloudSignIn(email, password)
                    }
                    TextButton(
                        onClick = { authMode = "landing"; state.authMessage = null },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Back", color = muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                }

                state.authMessage?.let { msg ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(NyoraTokens.surface2)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
                        Text(msg, color = body, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FeatureLine(accent, "No account required to read")
                    FeatureLine(accent, "Sync favourites, history, bookmarks, and progress")
                    FeatureLine(accent, "Choose sources and languages on the next step")
                }
            }
        }
    }
}

/** Ink-filled primary button (mac Mono primary): dark-on-light inverted fill. */
@Composable
private fun MonoPrimaryButton(text: String, accent: Color, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = if (!enabled || busy) 0.35f else 1f))
            .clickable(enabled = enabled && !busy) { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        val content = contrastOn(accent)
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = content)
        else Text(text, color = content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Ink-outlined secondary button (mac Mono secondary). */
@Composable
private fun MonoSecondaryButton(text: String, accent: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NyoraTokens.surface2)
            .border(1.5.dp, accent.copy(alpha = 0.38f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = NyoraTokens.onSurfaceHigh, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

/** Monochrome rounded text field (no accent). */
@Composable
private fun MonoField(
    label: String,
    value: String,
    accent: Color,
    enabled: Boolean,
    isPassword: Boolean = false,
    onChange: (String) -> Unit,
) {
    val ink = NyoraTokens.onSurfaceHigh
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accent,
            unfocusedBorderColor = ink.copy(alpha = 0.25f),
            cursorColor = accent,
            focusedLabelColor = accent,
            unfocusedLabelColor = NyoraTokens.onSurfaceMuted,
            focusedTextColor = ink,
            unfocusedTextColor = ink,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HeroStat(value: String, label: String, accent: Color) {
    Column(
        modifier = Modifier
            .width(128.dp)
            .glassCard(shape = RoundedCornerShape(18.dp), fill = NyoraTokens.surface1)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(value, color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = NyoraTokens.onSurfaceMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

private fun contrastOn(color: Color): Color =
    if (color.luminance() > 0.55f) Color.Black.copy(alpha = 0.88f) else Color.White

// ── Layer 2 · preferences ────────────────────────────────────────────────────────

@Composable
private fun PreferencesStage(
    state: AppState,
    accent: Color,
    onDone: () -> Unit,
    kicker: String = "YOU’RE IN",
    title: String = "Set up your shelf",
    sub: String = "Choose your languages and content preference — we’ll line up the matching " +
        "sources. You can change any of this later in Settings.",
    cta: String = "Start reading",
) {
    // "Show 18+ sources" is the inverse of hideNsfwSources (default hidden).
    var show18 by remember { mutableStateOf(!state.hideNsfwSources) }
    // Selected language codes; empty ⇒ all languages (mirrors the web onboarding).
    val selectedLangs = remember { mutableStateListOf<String>() }

    // Ensure the catalog is loaded before the language selector renders. loadCatalog
    // no-ops (empty) when no source repository is active — the stage then degrades to
    // just the 18+ toggle and the user can still finish (defaults preserved).
    LaunchedEffect(Unit) {
        if (state.catalogEntries.isEmpty() && !state.catalogLoading) state.loadCatalog()
    }

    val entries = state.catalogEntries
    val langOptions = remember(entries) { languageOptions(entries) }

    // A source matches iff its language is selected (or no language filter is set)
    // AND it passes the 18+ gate. Empty match ⇒ fall back to the 18+-only set so the
    // shelf is never seeded empty.
    fun matchedIds(): List<String> = entries
        .filter { (selectedLangs.isEmpty() || selectedLangs.contains(it.lang.lowercase())) && (show18 || !it.isNsfw) }
        .map { it.id }
    val fallbackCount = entries.count { show18 || !it.isNsfw }
    val addCount = matchedIds().size.takeIf { it > 0 } ?: fallbackCount

    Column(
        modifier = Modifier.widthIn(max = 460.dp).fillMaxHeight().padding(40.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Text(kicker, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            letterSpacing = 2.5.sp)
        Spacer(Modifier.height(14.dp))
        Text(title, color = NyoraTokens.onSurfaceHigh, fontSize = 30.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            sub,
            color = NyoraTokens.onSurfaceMuted, fontSize = 14.sp, textAlign = TextAlign.Center,
            lineHeight = 21.sp,
        )
        Spacer(Modifier.height(28.dp))

        // 18+ toggle row
        Row(
            modifier = Modifier.fillMaxWidth()
                .glassCard(shape = RoundedCornerShape(18.dp), fill = NyoraTokens.surface1)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Show 18+ sources", color = NyoraTokens.onSurfaceHigh, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold)
                Text("Include adult-only sources in Explore & search.",
                    color = NyoraTokens.onSurfaceFaint, fontSize = 12.sp)
            }
            Switch(
                checked = show18, onCheckedChange = { show18 = it },
                colors = SwitchDefaults.colors(checkedTrackColor = accent),
            )
        }

        // Language selector — only shown when the catalog is available. While the
        // catalog is loading, a spinner sits in its place.
        if (state.catalogLoading && entries.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp))
        } else if (langOptions.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Column(
                modifier = Modifier.fillMaxWidth()
                    .glassCard(shape = RoundedCornerShape(18.dp), fill = NyoraTokens.surface1)
                    .padding(18.dp),
            ) {
                Text("LANGUAGES", color = NyoraTokens.onSurfaceFaint, fontSize = 11.sp,
                    fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(4.dp))
                Text("Pick the languages you read, or keep “All languages”.",
                    color = NyoraTokens.onSurfaceFaint, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                LanguageChips(
                    options = langOptions,
                    selected = selectedLangs,
                    accent = accent,
                    onAll = { selectedLangs.clear() },
                    onToggle = { code ->
                        if (selectedLangs.contains(code)) selectedLangs.remove(code)
                        else selectedLangs.add(code)
                    },
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Column(
            modifier = Modifier.fillMaxWidth()
                .glassCard(shape = RoundedCornerShape(18.dp), fill = NyoraTokens.surface1)
                .padding(18.dp),
        ) {
            Text("GOOD TO KNOW", color = NyoraTokens.onSurfaceFaint, fontSize = 11.sp,
                fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(12.dp))
            FeatureLine(accent, "Tap the heart to favourite a series")
            FeatureLine(accent, "Pin your go-to sources to search them first")
            FeatureLine(accent, "Sign in on any device to pick up where you left off")
        }

        // Live count of what "Start reading" will seed.
        if (entries.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(
                "$addCount source${if (addCount == 1) "" else "s"} will be added",
                color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                state.hideNsfwSources = !show18
                state.persistSettings()
                var ids = matchedIds()
                if (ids.isEmpty()) ids = entries.filter { show18 || !it.isNsfw }.map { it.id }
                state.seedSources(ids)
                onDone()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
        ) { Text(cta, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
        Spacer(Modifier.weight(1f))
    }
}

// ── Language selection ───────────────────────────────────────────────────────────

/** A distinct language present in the catalog, with a friendly label + count. */
private data class LangOption(val code: String, val label: String, val count: Int)

/**
 * Distinct languages across [entries] as [LangOption]s, sorted by count desc then
 * label. Entries with no language collapse under code "" (label "Other"), so the
 * option set always covers every source. Mirrors the web's languageOptions().
 */
private fun languageOptions(entries: List<CatalogEntry>): List<LangOption> {
    val counts = LinkedHashMap<String, Int>()
    for (e in entries) {
        val code = e.lang.lowercase()
        counts[code] = (counts[code] ?: 0) + 1
    }
    return counts.entries
        .map { (code, count) -> LangOption(code, if (code.isEmpty()) "Other" else langLabel(code), count) }
        .sortedWith(compareByDescending<LangOption> { it.count }.thenBy { it.label })
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LanguageChips(
    options: List<LangOption>,
    selected: List<String>,
    accent: Color,
    onAll: () -> Unit,
    onToggle: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip(label = "All languages", count = null, active = selected.isEmpty(), accent = accent, onClick = onAll)
        options.forEach { opt ->
            Chip(
                label = opt.label, count = opt.count,
                active = selected.contains(opt.code), accent = accent,
                onClick = { onToggle(opt.code) },
            )
        }
    }
}

@Composable
private fun Chip(label: String, count: Int?, active: Boolean, accent: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) accent.copy(alpha = 0.18f) else NyoraTokens.surface2)
            .border(
                width = 1.dp,
                color = if (active) accent.copy(alpha = 0.65f) else NyoraTokens.onSurfaceFaint.copy(alpha = 0.22f),
                shape = RoundedCornerShape(50),
            )
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (active) accent else NyoraTokens.onSurfaceBody,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                count.toString(),
                color = if (active) accent.copy(alpha = 0.85f) else NyoraTokens.onSurfaceFaint,
                fontSize = 11.sp,
            )
        }
    }
}

// Friendly language names so onboarding shows names, not ISO codes (matches web).
private val LANG_NAMES = mapOf(
    "en" to "English", "es" to "Spanish", "es-419" to "Spanish (LatAm)", "pt" to "Portuguese",
    "pt-br" to "Portuguese (BR)", "fr" to "French", "de" to "German", "it" to "Italian",
    "ru" to "Russian", "id" to "Indonesian", "ar" to "Arabic", "tr" to "Turkish", "pl" to "Polish",
    "vi" to "Vietnamese", "th" to "Thai", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese",
    "zh-hans" to "Chinese", "zh-hant" to "Chinese (Trad.)", "uk" to "Ukrainian", "fa" to "Persian",
    "nl" to "Dutch", "multi" to "Multi-language", "all" to "Multi-language", "bg" to "Bulgarian",
    "bn" to "Bengali", "ca" to "Catalan", "cs" to "Czech", "da" to "Danish", "el" to "Greek",
    "fi" to "Finnish", "he" to "Hebrew", "hi" to "Hindi", "hr" to "Croatian", "hu" to "Hungarian",
    "is" to "Icelandic", "kn" to "Kannada", "ml" to "Malayalam", "ms" to "Malay", "ne" to "Nepali",
    "no" to "Norwegian", "ro" to "Romanian", "sk" to "Slovak", "sl" to "Slovenian", "sq" to "Albanian",
    "sr" to "Serbian", "sv" to "Swedish", "ta" to "Tamil", "ur" to "Urdu", "fil" to "Filipino",
    "mn" to "Mongolian", "ka" to "Georgian",
)

private fun langLabel(raw: String): String {
    val code = raw.lowercase()
    if (code.isEmpty()) return "Manga"
    return LANG_NAMES[code] ?: LANG_NAMES[code.take(2)] ?: code.uppercase()
}

// ── bits ───────────────────────────────────────────────────────────────────────

@Composable
private fun FeatureLine(accent: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(12.dp))
        Text(text, color = NyoraTokens.onSurfaceMuted, fontSize = 14.sp)
    }
}

@Composable
private fun OrDivider() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(NyoraTokens.onSurfaceFaint.copy(alpha = 0.25f)))
        Text("  or  ", color = NyoraTokens.onSurfaceFaint, fontSize = 12.sp)
        Box(Modifier.weight(1f).height(1.dp).background(NyoraTokens.onSurfaceFaint.copy(alpha = 0.25f)))
    }
}
