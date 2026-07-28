package com.nyora.windows.ai.onnx

import com.nyora.windows.ai.AiEndpointPolicy
import com.nyora.windows.ai.AiResponseReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Machine translation via the free Google web endpoint (client=gtx), plus the
 * manga-specific repair layer and optional LLM refinement — a direct port of
 * nyora-web's core/translate/mt.js (itself a port of nyora-android's
 * translator/Translator.kt). All bubbles of a page are joined with the same
 * ||| delimiter and translated in ONE request; if the split comes back
 * misaligned, it bisects rather than falling back to one request per block.
 *
 * Pure logic — no ONNX. Networking uses OkHttp; JSON via kotlinx.serialization.
 */
object MangaMt {

    data class RefineCfg(
        val provider: String,
        val endpoint: String?,
        val apiKey: String,
        val model: String?,
        val context: String?,
    )

    private const val DELIM = "\n\n\n|||\n\n\n"

    // Target languages offered in the reader settings (Google translate codes).
    val TL_LANGS: List<Pair<String, String>> = listOf(
        "en" to "English", "es" to "Spanish", "pt" to "Portuguese", "fr" to "French",
        "de" to "German", "it" to "Italian", "ru" to "Russian", "id" to "Indonesian",
        "ar" to "Arabic", "tr" to "Turkish", "pl" to "Polish", "vi" to "Vietnamese",
        "th" to "Thai", "hi" to "Hindi", "ko" to "Korean", "zh-CN" to "Chinese",
    )

    // Source (page) languages the OCR engines support. 'auto' resolves from the
    // manga source's language in the reader.
    val TL_SOURCES: List<Pair<String, String>> = listOf(
        "auto" to "Auto (source language)", "ja" to "Japanese", "zh" to "Chinese",
        "ko" to "Korean", "en" to "English",
    )

    // OCR language → Google translate source code.
    private val GTX_SOURCE = mapOf("ja" to "ja", "zh" to "zh-CN", "ko" to "ko", "en" to "en")

    // LLM refinement defaults (port of Android's translatePageDialoguesAtOnce).
    private data class AiDefault(val endpoint: String, val model: String)

    private val AI_DEFAULTS = mapOf(
        "openai" to AiDefault("https://api.openai.com/v1", "gpt-4o-mini"),
        "anthropic" to AiDefault("https://api.anthropic.com", "claude-haiku-4-5-20251001"),
    )

    private val client = OkHttpClient()
    // BYOK requests carry an authorization secret. Never follow a redirect: a
    // redirect could otherwise change an initially safe endpoint into an HTTP or
    // attacker-controlled destination after the request has been constructed.
    private val refinementClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val JSON_MEDIA = "application/json".toMediaType()

    // encodeURIComponent equivalent (Java URLEncoder differs on space & a few marks).
    private fun encodeURIComponent(s: String): String =
        URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")

    private suspend fun gtx(text: String, target: String, source: String = "auto"): String =
        withContext(Dispatchers.IO) {
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t" +
                "&sl=${encodeURIComponent(source)}&tl=${encodeURIComponent(target)}" +
                "&q=${encodeURIComponent(text)}"
            val res = client.newCall(Request.Builder().url(url).get().build()).execute()
            res.use {
                if (!it.isSuccessful) throw RuntimeException("translate failed (${it.code})")
                val bodyText = AiResponseReader.readUtf8(it.body)
                    ?: throw RuntimeException("Translation response is too large or empty")
                val data = json.parseToJsonElement(bodyText)
                val first = (data as? JsonArray)?.getOrNull(0) as? JsonArray ?: return@use ""
                first.joinToString("") { seg ->
                    ((seg as? JsonArray)?.getOrNull(0) as? JsonPrimitive)
                        ?.takeUnless { p -> p is JsonNull }?.contentOrEmpty() ?: ""
                }
            }
        }

    private fun JsonPrimitive.contentOrEmpty(): String = if (this is JsonNull) "" else content

    // --- manga-specific repair of the plain-MT output ---------------------

    // Set phrases gtx reliably gets WRONG (reads interjections as literal
    // statements) — answered directly and never sent to Google.
    private val LEXICON: Map<String, String> = mapOf(
        "しまった" to "Damn it", "ヤバい" to "This is bad", "やばい" to "This is bad",
        "まずい" to "This is bad", "くそ" to "Damn", "くそっ" to "Damn it",
        "ちくしょう" to "Dammit", "やめろ" to "Stop it", "まさか" to "No way",
        "さすが" to "As expected", "よし" to "All right", "なるほど" to "I see",
        "うるさい" to "Shut up", "てめえ" to "You bastard", "ざけんな" to "Screw you",
        "どういうことだ" to "What do you mean", "ありえない" to "Impossible",
    )

    private val FULLWIDTH = mapOf(
        '！' to "!", '？' to "?", '。' to ".", '、' to ",", '．' to ".", '，' to ",",
    )
    private val ASCII_PUNCT_RE = Regex("[！？。、．，]")
    private fun asciiPunct(s: String): String =
        ASCII_PUNCT_RE.replace(s) { FULLWIDTH[it.value[0]] ?: it.value }

    private val FIX_SPACED_BANG = Regex("([!?])(\\s+[!?])+")
    private val WS = Regex("\\s+")
    private val ELLIPSIS = Regex("…")
    private val DOTS4 = Regex("\\.{4,}")
    private val WS_BEFORE_PUNCT = Regex("\\s+([,.!?;:])")
    private val WS_2 = Regex("\\s{2,}")

    private fun fixPunct(s: String): String =
        FIX_SPACED_BANG.replace(s) { WS.replace(it.value, "") } // "! ! !" → "!!!"
            .let { ELLIPSIS.replace(it, "...") }
            .let { DOTS4.replace(it, "...") }
            .let { WS_BEFORE_PUNCT.replace(it, "$1") }
            .let { WS_2.replace(it, " ") }
            .trim()

    // Clamp any run in the output to the longest run in the source.
    private val SRC_RUN_RE = Regex("(.)\\1+")
    private val EN_RUN_RE = Regex("(\\p{L})\\1{2,}")
    private fun clampRuns(en: String, src: String): String {
        val matches = SRC_RUN_RE.findAll(src).toList()
        // No run in the source means there is nothing to clamp AGAINST — bail out.
        if (matches.isEmpty()) return en
        var maxLen = 2
        for (r in matches) maxLen = max(maxLen, r.value.length)
        val cap = maxLen
        return EN_RUN_RE.replace(en) { m ->
            val ch = m.groupValues[1]
            ch.repeat(min(m.value.length, cap))
        }
    }

    // A stutter (ま、まさか…) is a first-mora repeat.
    private val STUTTER = Regex("^(.)[、,]\\s*(?=\\1)")
    private data class Stripped(val text: String, val stutter: Boolean)
    private fun stripStutter(t: String): Stripped =
        if (STUTTER.containsMatchIn(t)) Stripped(STUTTER.replaceFirst(t, ""), true)
        else Stripped(t, false)

    private val RESTORE_RE = Regex("^([A-Za-z])(\\w*)")
    private fun restoreStutter(en: String): String {
        val m = RESTORE_RE.find(en) ?: return en
        val first = m.groupValues[1]
        return "$first-${first.lowercase()}${en.substring(1)}"
    }

    private fun capitalize(s: String): String =
        if (s.isNotEmpty()) s.substring(0, 1).uppercase() + s.substring(1) else s

    // ---- SFX, held vowels and honorifics ----------------------------------
    // Ported from the web reader's pipeline so all three clients letter a page
    // the same way. Every rule below is language-gated: the characters are not
    // language-specific (殿/君/先輩 are ordinary Chinese words — 殿 is "hall"),
    // so running the Japanese rules over Chinese produced "Jinluan-dono Palace".

    private val KANA_ROMAJI = mapOf(
        'ア' to "a", 'イ' to "i", 'ウ' to "u", 'エ' to "e", 'オ' to "o",
        'カ' to "ka", 'キ' to "ki", 'ク' to "ku", 'ケ' to "ke", 'コ' to "ko",
        'サ' to "sa", 'シ' to "shi", 'ス' to "su", 'セ' to "se", 'ソ' to "so",
        'タ' to "ta", 'チ' to "chi", 'ツ' to "tsu", 'テ' to "te", 'ト' to "to",
        'ナ' to "na", 'ニ' to "ni", 'ヌ' to "nu", 'ネ' to "ne", 'ノ' to "no",
        'ハ' to "ha", 'ヒ' to "hi", 'フ' to "fu", 'ヘ' to "he", 'ホ' to "ho",
        'マ' to "ma", 'ミ' to "mi", 'ム' to "mu", 'メ' to "me", 'モ' to "mo",
        'ヤ' to "ya", 'ユ' to "yu", 'ヨ' to "yo",
        'ラ' to "ra", 'リ' to "ri", 'ル' to "ru", 'レ' to "re", 'ロ' to "ro",
        'ワ' to "wa", 'ヲ' to "o", 'ン' to "n",
        'ガ' to "ga", 'ギ' to "gi", 'グ' to "gu", 'ゲ' to "ge", 'ゴ' to "go",
        'ザ' to "za", 'ジ' to "ji", 'ズ' to "zu", 'ゼ' to "ze", 'ゾ' to "zo",
        'ダ' to "da", 'ヂ' to "ji", 'ヅ' to "zu", 'デ' to "de", 'ド' to "do",
        'バ' to "ba", 'ビ' to "bi", 'ブ' to "bu", 'ベ' to "be", 'ボ' to "bo",
        'パ' to "pa", 'ピ' to "pi", 'プ' to "pu", 'ペ' to "pe", 'ポ' to "po",
        'ヴ' to "vu",
    )
    private val KANA_SMALL = mapOf(
        'ャ' to "ya", 'ュ' to "yu", 'ョ' to "yo",
        'ァ' to "a", 'ィ' to "i", 'ゥ' to "u", 'ェ' to "e", 'ォ' to "o",
    )
    private val LEADING_GLIDE = Regex("^y(?=[aou])")

    /**
     * Hepburn-ish transliteration: ー doubles the previous vowel, ッ geminates the
     * next consonant, and a small kana forms a digraph (キャ → kya, シャ → sha).
     */
    fun katakanaToRomaji(s: String): String {
        val out = StringBuilder()
        val chars = s.toCharArray()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            val next = chars.getOrNull(i + 1)
            if (c == 'ー') { if (out.isNotEmpty()) out.append(out[out.length - 1]); i++; continue }
            if (c == 'ッ') { next?.let { KANA_ROMAJI[it] }?.let { out.append(it[0]) }; i++; continue }
            val base = KANA_ROMAJI[c]
            if (base == null) { i++; continue }
            val small = next?.let { KANA_SMALL[it] }
            if (small != null) {
                // シ + ャ → sh + a, キ + ャ → k + ya: drop the trailing i, and drop
                // the glide's y only when the base already ends in a digraph.
                val glide = if (base.endsWith("i") && base.length > 2) LEADING_GLIDE.replace(small, "") else small
                out.append(base.removeSuffix("i")).append(glide)
                i += 2
                continue
            }
            out.append(base)
            i++
        }
        return out.toString()
    }

    // Hangul is compositional, so romanisation is arithmetic rather than a table:
    // a syllable's code point encodes (initial × 21 + medial) × 28 + final.
    private val JAMO_INITIAL = listOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val JAMO_MEDIAL = listOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo",
        "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    private val JAMO_FINAL = listOf("", "k", "k", "k", "n", "n", "n", "t", "l", "l", "l", "l", "l", "l", "l", "l",
        "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t")

    fun hangulToRomaja(s: String): String {
        val out = StringBuilder()
        for (ch in s) {
            val code = ch.code - 0xAC00
            if (code < 0 || code > 11171) continue
            out.append(JAMO_INITIAL[code / 588]).append(JAMO_MEDIAL[(code % 588) / 28]).append(JAMO_FINAL[code % 28])
        }
        return out.toString()
    }

    private fun editDistance(s: String, t: String): Int {
        var prev = IntArray(t.length + 1) { it }
        for (i in 1..s.length) {
            val cur = IntArray(t.length + 1)
            cur[0] = i
            for (j in 1..t.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (s[i - 1] == t[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[t.length]
    }

    private val NON_ALPHA = Regex("[^a-z]")

    /** 0 = identical, 1 = nothing in common. Case- and length-normalised. */
    private fun phoneticDistance(a: String, b: String): Double {
        val x = NON_ALPHA.replace(a.lowercase(), "")
        val y = NON_ALPHA.replace(b.lowercase(), "")
        if (x.isEmpty() || y.isEmpty()) return 1.0
        return editDistance(x, y).toDouble() / max(x.length, y.length)
    }

    // Katakana, long marks and small kana only, plus trailing punctuation. A real
    // sentence carries particles in hiragana or kanji, so this cannot match
    // dialogue. Deliberately NOT applied to Korean: katakana is a separate script
    // reserved for foreign words and effects, so "katakana-only" is evidence a
    // bubble is an effect, whereas Hangul is Korean's only script and proves
    // nothing — trying it turned 조심해! ("Be careful!") into "Josimhae!".
    private val KATAKANA_ONLY = Regex("^[゠-ヿー]+[\\s!?！？.。…、,ッっ]*$")
    private val SFX_TRAIL = Regex("[\\s!?！？.。…、,~]+$")
    private val SFX_PUNCT_TAIL = Regex("[!?！？.。…、,]+$")

    /** The translator sent a katakana effect to the dictionary — take romaji instead. */
    internal fun fixSfx(en: String, src: String): String {
        val core = SFX_TRAIL.replace(src, "")
        if (!KATAKANA_ONLY.matches(core) || core.length > 8) return en
        val romaji = katakanaToRomaji(core)
        if (romaji.length < 2) return en
        // Measured distances do NOT separate cleanly: バタン → "Bang" (0.60) sits
        // above バキバキ → "Breaking fast" (0.58), so no threshold keeps the good
        // English onomatopoeia without also keeping the mistranslations. 0.5
        // sacrifices "Bang" → "Batan" on purpose: a plain transliteration is never
        // WRONG, only less colourful, while a confident mistranslation puts a
        // false sentence on the page.
        if (phoneticDistance(en, romaji) <= 0.5) return en
        val cap = romaji.substring(0, 1).uppercase() + romaji.substring(1)
        return cap + asciiPunct(SFX_PUNCT_TAIL.find(src)?.value ?: "")
    }

    // A scream is a word with its last sound HELD: いやあああ, そんなーーー. Sent
    // as-is the translator mishandles it three ways — いやあああ → "Noaaa" (kana
    // glued onto English), そんなーーー → "That's so..." (hold dropped), ええええっ
    // → "Yeah yeah" (hold became a repeated word). So strip the hold before
    // translating and re-apply it to the English, as a letterer would.
    private val HOLD = Regex("([ぁ-おァ-オー아-이])\\1+(?=[っッ]?[^ぁ-んァ-ヶ一-鿿가-힣]*$)")
    internal data class Held(val text: String, val hold: Int)

    internal fun stripHold(t: String): Held {
        val m = HOLD.find(t) ?: return Held(t, 0)
        val idx = m.range.first
        // Keep one instance so the base is still a word (いやあああ → いや + 3;
        // ええええ → え + 3, not an empty base).
        val base = t.substring(0, idx) + (if (idx == 0) m.groupValues[1] else "") + t.substring(idx + m.value.length)
        return Held(base, m.value.length - (if (idx == 0) 1 else 0))
    }

    private val LAST_LETTER = Regex("(\\p{L})(\\P{L}*)$")
    internal fun applyHold(en: String, hold: Int): String {
        if (hold < 2) return en
        // Repeat the final letter of the last word — "No" → "Nooo" — leaving any
        // trailing punctuation where it is.
        return LAST_LETTER.replace(en) { m -> m.groupValues[1].repeat(1 + min(hold, 5)) + m.groupValues[2] }
    }

    // Ellipses inside a word are a line break, not a pause: 帰らな…くて…は… is one
    // word. Left in, the translator reads the fragments separately and inverts the
    // meaning ("I don't want to go home" for "I have to go home").
    private val SPLIT_WORD = Regex("([぀-ヿ一-鿿]{2,})…+(?=[぀-ヿ一-鿿])")
    internal fun joinSplitWords(t: String): String = SPLIT_WORD.replace(t, "$1")

    // Honorifics: keep them as suffixes, the way a scanlator letters them. The
    // translator is inconsistent — 「…アカネさん？」 comes back "...Akane-san?" but
    // 「丸山さん…」 comes back "Mr. Maruyama...". Only "Title + Name" is rewritten;
    // a bare noun must be left alone or 「この子達の先生」 would become "the -sensei
    // of these children".
    private class Honorific(val jp: Regex, val en: String, val titles: String)
    private val HONORIFICS = listOf(
        // Longest/most specific first — 兄さん must not be matched by the さん rule.
        Honorific(Regex("姉(さん|ちゃん)|お姉[さち]ゃん"), "nee", "\\b(?:Sister|Big Sister)\\s+"),
        Honorific(Regex("兄(さん|ちゃん)|お兄[さち]ゃん"), "nii", "\\b(?:Brother|Big Brother)\\s+"),
        Honorific(Regex("先輩"), "senpai", "\\b(?:Senior|Senpai)\\s+"),
        Honorific(Regex("先生"), "sensei", "\\b(?:Teacher|Doctor|Dr)\\.?\\s+"),
        Honorific(Regex("[様さ]ま|様"), "sama", "\\b(?:Lord|Lady|Master|Sir)\\s+"),
        Honorific(Regex("殿(?![ぁ-ん])|どの(?=[、。！？…\\s]|$)"), "dono", "\\b(?:Lord|Sir)\\s+"),
        Honorific(Regex("ちゃん"), "chan", "\\b(?:Little|Miss)\\s+"),
        Honorific(Regex("(?:君|くん)(?![ぁ-ん])"), "kun", "\\b(?:Master|Mr)\\.?\\s+"),
        Honorific(Regex("さん(?![ぁ-ん])"), "san", "\\b(?:Mr|Mrs|Ms|Miss)\\.?\\s+"),
    )

    // The commoner failure is not a title but a SILENT DROP: ローズさん、こんにちは
    // → "Hello Rose," with -san gone. Fixing it needs the name's ENGLISH spelling,
    // so the bare names ride along as extra segments of the request already going
    // out — reusing the translator's own romanisation (ルフィ → "Luffy", where
    // mechanical Hepburn would give "Rufi").
    private val NAME_HONORIFIC = Regex("([゠-ヿ一-鿿][゠-ヿ一-鿿ー]{1,7})(さん|ちゃん|くん|君|様|さま|殿|先輩)(?![ぁ-ん])")
    private val JA_SUFFIX = mapOf(
        "さん" to "san", "ちゃん" to "chan", "くん" to "kun", "君" to "kun",
        "様" to "sama", "さま" to "sama", "殿" to "dono", "先輩" to "senpai",
    )
    // 兄さん / お姉ちゃん are relationship words, not names — HONORIFICS covers those.
    private val JA_RELATION = Regex("^[兄姉母父]")
    // Korean: 씨/님 attach directly, relationship terms follow a space.
    private val KO_NAME_HONORIFIC = Regex("([가-힣]{2,4})\\s*(씨|님|선배|오빠|언니|형|누나)(?![가-힣])")
    private val KO_SUFFIX = mapOf(
        "씨" to "ssi", "님" to "nim", "선배" to "sunbae", "오빠" to "oppa",
        "언니" to "eonni", "형" to "hyung", "누나" to "noona",
    )
    // Role words, not names. 선배 only acts as a suffix after a real name (민수 선배);
    // without this, 선배님 parsed as name=선배 + 님 and produced "senior-nim".
    private val KO_ROLE = Regex("^(사장|선생|부장|과장|회장|팀장|손님|고객|선배|후배|아저씨|아주머니)$")

    /** [lang] is required — see the note above HONORIFICS. */
    internal fun findNamedHonorifics(texts: List<String>, lang: String): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        val ja = lang.startsWith("ja")
        val ko = lang.startsWith("ko")
        if (!ja && !ko) return found
        if (ko) for (t in texts) {
            for (m in KO_NAME_HONORIFIC.findAll(t)) {
                if (KO_ROLE.containsMatchIn(m.groupValues[1])) continue
                KO_SUFFIX[m.groupValues[2]]?.let { found[m.groupValues[1]] = it }
            }
        }
        if (ja) for (t in texts) {
            for (m in NAME_HONORIFIC.findAll(t)) {
                val name = m.groupValues[1]
                if (JA_RELATION.containsMatchIn(name) || name.length < 2) continue
                JA_SUFFIX[m.groupValues[2]]?.let { found[name] = it }
            }
        }
        return found
    }

    private val NAME_TRIM = Regex("^[^\\p{L}]+|[^\\p{L}]+$")
    private val NAME_OK = Regex("^[\\p{L}][\\p{L}'-]*$")
    private const val HON_SUFFIXES = "san|chan|kun|sama|dono|senpai|sensei|nee|nii|ssi|nim|sunbae|oppa|eonni|hyung|noona"

    /** Append `-suffix` where the translator dropped it, never to a name that has one. */
    internal fun reattachHonorific(en: String, englishName: String?, suffix: String): String {
        val name = NAME_TRIM.replace((englishName ?: "").trim(), "")
        if (name.length < 2 || !NAME_OK.matches(name)) return en
        val quoted = Regex.escape(name)
        // Case-INSENSITIVE on purpose: the translator lowercases any name it reads
        // as an ordinary word (ローズ → "rose", ベル → "bell") while the sentence
        // capitalises it, so matching on capitalisation missed the names that need
        // this most. The space form counts as already-suffixed too — it writes
        // "Junho oppa" for 준호 오빠, and checking only "Junho-oppa" gave
        // "Junho-oppa oppa".
        if (Regex("\\b$quoted[\\s-](?:$HON_SUFFIXES)\\b", RegexOption.IGNORE_CASE).containsMatchIn(en)) return en
        return Regex("\\b$quoted\\b", RegexOption.IGNORE_CASE).replace(en) { m -> "${m.value}-$suffix" }
    }

    internal fun restoreHonorifics(en: String, src: String, lang: String): String {
        if (!lang.startsWith("ja")) return en
        var out = en
        for (h in HONORIFICS) {
            if (!h.jp.containsMatchIn(src)) continue
            // The name has to look like one: a capitalised word right after the
            // title. "Mr. Maruyama" → "Maruyama-san"; "the teacher of" is untouched.
            out = Regex(h.titles + "([A-Z][\\w'-]*)").replace(out, "$1-${h.en}")
            if (out != en) break // one honorific per line is the normal case
        }
        return out
    }

    private fun polish(en: String?, src: String, stutter: Boolean, lang: String): String {
        var out = fixPunct(en ?: "")
        out = fixSfx(out, src)
        out = restoreHonorifics(out, src, lang)
        out = clampRuns(out, src)
        if (stutter) out = restoreStutter(out)
        return capitalize(out)
    }

    // Split a joined reply back into segments; null when it can't align.
    private val SPLIT_RE = Regex("\\s*\\|\\s*\\|\\s*\\|\\s*")
    private fun splitParts(full: String, n: Int): List<String>? {
        val parts = SPLIT_RE.split(full).map { it.trim() }
        return if (parts.size == n) parts else null
    }

    // Translate a run of segments, halving on misalignment (~log2(n) round trips).
    private suspend fun translateRun(texts: List<String>, target: String, source: String): List<String> {
        if (texts.isEmpty()) return emptyList()
        if (texts.size == 1) {
            return listOf(
                try {
                    gtx(texts[0], target, source).trim()
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    ""
                },
            )
        }
        try {
            val parts = splitParts(gtx(texts.joinToString(DELIM), target, source), texts.size)
            if (parts != null) return parts
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // A transient provider/misaligned batch falls back to smaller
            // requests; cancellation must instead stop the whole reader job.
        }
        val mid = ceil(texts.size / 2.0).toInt()
        return coroutineScope {
            val a = async { translateRun(texts.subList(0, mid), target, source) }
            val b = async { translateRun(texts.subList(mid, texts.size), target, source) }
            val (ra, rb) = awaitAll(a, b)
            ra + rb
        }
    }

    private class Prep(
        val src: String,
        val direct: String? = null,
        val send: String? = null,
        val stutter: Boolean = false,
        val hold: Int = 0,
        val names: Map<String, String> = emptyMap(),
        var out: String? = null,
    )

    private val TRAILING_PUNCT = Regex("[！？!?。．.…、,\\s]+$")

    suspend fun translateBatch(texts: List<String>, target: String, source: String = "auto"): List<String> {
        if (texts.isEmpty()) return emptyList()
        val lang = source // captured before the gtx code mapping below
        val src = GTX_SOURCE[source] ?: source.ifEmpty { "auto" }

        // Answer known interjections locally; the lexicon is English-only.
        val prepared = texts.map { raw ->
            val t = raw.trim()
            val bare = TRAILING_PUNCT.replace(t, "")
            val hit = if (target == "en") LEXICON[bare] else null
            if (hit != null) {
                Prep(src = t, direct = fixPunct(hit + asciiPunct(t.substring(bare.length))))
            } else {
                val (unstuttered, stutter) = stripStutter(t)
                val held = stripHold(unstuttered)
                // Per-line, NOT batch-wide: if this line writes the name bare the
                // author dropped the honorific on purpose and it must stay dropped.
                // Collecting them batch-wide leaked 「ナハトさん」's -san onto
                // 「天才だナハト…！」.
                Prep(
                    src = t,
                    send = joinSplitWords(held.text),
                    stutter = stutter,
                    hold = held.hold,
                    names = findNamedHonorifics(listOf(t), lang),
                )
            }
        }

        val pending = prepared.filter { it.send != null }
        // Names carrying an honorific ride along as extra segments so the
        // translator's own romanisation comes back in the SAME request. English
        // targets only: the -san convention is an English scanlation habit.
        val nameList = if (target == "en") findNamedHonorifics(texts, lang).keys.toList() else emptyList()
        val got = translateRun(pending.map { it.send!! } + nameList, target, src)
        pending.forEachIndexed { i, p -> p.out = got.getOrNull(i) }
        val englishName = HashMap<String, String>()
        nameList.forEachIndexed { i, jp -> englishName[jp] = got.getOrNull(pending.size + i) ?: "" }

        return prepared.map { p ->
            if (p.direct != null) return@map p.direct
            var out = applyHold(polish(p.out, p.src, p.stutter, lang), p.hold)
            for ((jp, suffix) in p.names) out = reattachHonorific(out, englishName[jp], suffix)
            out
        }
    }

    private val REFINE_SPLIT = Regex("\\s*\\|\\|\\|\\s*")

    suspend fun refineBatch(
        originals: List<String>,
        drafts: List<String>,
        target: String,
        cfg: RefineCfg,
    ): List<String>? {
        val langName = TL_LANGS.firstOrNull { it.first == target }?.second ?: "English"
        val system = "You are an expert manga translator. Translate each dialogue segment into " +
            langName + ", preserving tone and keeping lines short enough for speech bubbles. " +
            "The segments come from ONE manga page in reading order — keep them coherent with each other. " +
            (if (!cfg.context.isNullOrEmpty()) "\nUse this series context for accurate character names and terms:\n" + cfg.context + "\n" else "") +
            "Reply with ONLY the translated segments, in the same order, separated by \" ||| \". " +
            "No numbering, no commentary, and exactly " + originals.size + " segments."
        val user = "Original segments:\n" + originals.joinToString("\n|||\n") +
            (if (drafts.size == originals.size)
                "\n\nDraft machine translations (improve on these):\n" + drafts.joinToString("\n|||\n")
            else "")

        if (cfg.apiKey.isBlank()) return null
        val defaults = AI_DEFAULTS[cfg.provider] ?: AI_DEFAULTS["openai"]!!
        val configuredEndpoint = cfg.endpoint?.trim()?.takeIf { it.isNotEmpty() } ?: defaults.endpoint
        val endpoint = AiEndpointPolicy.normalizeBaseUrl(configuredEndpoint) ?: return null
        val model = cfg.model?.ifEmpty { null } ?: defaults.model

        val out: String = withContext(Dispatchers.IO) {
            if (cfg.provider == "anthropic") {
                val body = buildJsonObject {
                    put("model", model)
                    put("max_tokens", 4096)
                    put("system", system)
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", user)
                        })
                    })
                }.toString()
                val requestUrl = AiEndpointPolicy.requestUrl(endpoint, "/v1/messages") ?: return@withContext ""
                val req = Request.Builder()
                    .url(requestUrl)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("x-api-key", cfg.apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("anthropic-dangerous-direct-browser-access", "true")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .build()
                refinementClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) throw RuntimeException("AI refinement failed (${res.code})")
                    val bodyText = AiResponseReader.readUtf8(res.body)
                        ?: throw RuntimeException("AI refinement response is too large or empty")
                    val data = json.parseToJsonElement(bodyText).jsonObject
                    val text = (data["content"] as? JsonArray)?.getOrNull(0)
                        ?.let { (it as? JsonObject)?.get("text") as? JsonPrimitive }
                        ?.contentOrEmpty() ?: ""
                    text.trim()
                }
            } else {
                val body = buildJsonObject {
                    put("model", model)
                    put("temperature", 0.3)
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("role", "system")
                            put("content", system)
                        })
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", user)
                        })
                    })
                }.toString()
                val requestUrl = AiEndpointPolicy.requestUrl(endpoint, "/chat/completions") ?: return@withContext ""
                val req = Request.Builder()
                    .url(requestUrl)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer ${cfg.apiKey}")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .build()
                refinementClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) throw RuntimeException("AI refinement failed (${res.code})")
                    val bodyText = AiResponseReader.readUtf8(res.body)
                        ?: throw RuntimeException("AI refinement response is too large or empty")
                    val data = json.parseToJsonElement(bodyText).jsonObject
                    val content = (data["choices"] as? JsonArray)?.getOrNull(0)
                        ?.let { (it as? JsonObject)?.get("message") as? JsonObject }
                        ?.let { it["content"] as? JsonPrimitive }
                        ?.contentOrEmpty() ?: ""
                    content.trim()
                }
            }
        }

        val parts = REFINE_SPLIT.split(out).map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size == originals.size) parts else null
    }
}
