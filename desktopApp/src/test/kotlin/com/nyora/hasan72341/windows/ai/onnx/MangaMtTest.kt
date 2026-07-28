package com.nyora.hasan72341.windows.ai.onnx

import com.nyora.windows.ai.onnx.MangaMt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The translation post-processing is a port of the web reader's pipeline, and the
 * two must letter a page identically. Every expectation below is the web
 * implementation's actual output for that input, captured by running it, so this
 * is a differential test rather than a restatement of what the Kotlin does.
 */
class MangaMtTest {

    private fun dump(m: Map<String, String>): String =
        m.entries.joinToString(", ", "{", "}") { "\"${it.key}\": \"${it.value}\"" }

    private fun stripAndApply(input: String, base: String, hold: Int, applied: String) {
        val h = MangaMt.stripHold(input)
        assertEquals(base, h.text, input)
        assertEquals(hold, h.hold, input)
        assertEquals(applied, MangaMt.applyHold("No", h.hold), input)
    }

    @Test fun `katakana romaji matches the web pipeline`() {
        assertEquals("gacha", MangaMt.katakanaToRomaji("ガチャ"), "ガチャ")
        assertEquals("doki", MangaMt.katakanaToRomaji("ドキ"), "ドキ")
        assertEquals("zawazawa", MangaMt.katakanaToRomaji("ザワザワ"), "ザワザワ")
        assertEquals("batan", MangaMt.katakanaToRomaji("バタン"), "バタン")
        assertEquals("bakibaki", MangaMt.katakanaToRomaji("バキバキ"), "バキバキ")
        assertEquals("kirakira", MangaMt.katakanaToRomaji("キラキラ"), "キラキラ")
        assertEquals("gogogo", MangaMt.katakanaToRomaji("ゴゴゴ"), "ゴゴゴ")
        assertEquals("shakiin", MangaMt.katakanaToRomaji("シャキーン"), "シャキーン")
        assertEquals("zudoon", MangaMt.katakanaToRomaji("ズドーン"), "ズドーン")
        assertEquals("nyaa", MangaMt.katakanaToRomaji("ニャア"), "ニャア")
    }

    @Test fun `hangul romaja matches the web pipeline`() {
        assertEquals("josimhae", MangaMt.hangulToRomaja("조심해"), "조심해")
        assertEquals("kung", MangaMt.hangulToRomaja("쿵"), "쿵")
        assertEquals("banjjakbanjjak", MangaMt.hangulToRomaja("반짝반짝"), "반짝반짝")
        assertEquals("minsu", MangaMt.hangulToRomaja("민수"), "민수")
        assertEquals("junho", MangaMt.hangulToRomaja("준호"), "준호")
    }

    @Test fun `sfx substitution matches the web pipeline`() {
        assertEquals("Gacha", MangaMt.fixSfx("Gacha", "ガチャ"), "ガチャ")
        assertEquals("Batan", MangaMt.fixSfx("Bang", "バタン"), "バタン")
        assertEquals("Bakibaki", MangaMt.fixSfx("Breaking fast", "バキバキ"), "バキバキ")
        assertEquals("Kirakira", MangaMt.fixSfx("Sparkling", "キラキラ"), "キラキラ")
        assertEquals("Google", MangaMt.fixSfx("Google", "ゴゴゴ"), "ゴゴゴ")
        assertEquals("Don", MangaMt.fixSfx("Thump", "ドン"), "ドン")
        assertEquals("Hello there", MangaMt.fixSfx("Hello there", "こんにちは"), "こんにちは")
    }

    @Test fun `held vowels are stripped and re-applied like the web pipeline`() {
        stripAndApply("いやあああ", "いや", 3, "Noooo")
        stripAndApply("そんなーーー", "そんな", 3, "Noooo")
        stripAndApply("ええええっ！？", "えっ！？", 3, "Noooo")
        stripAndApply("こんにちは", "こんにちは", 0, "No")
    }

    @Test fun `line-broken words are rejoined like the web pipeline`() {
        assertEquals("帰らなくては…", MangaMt.joinSplitWords("帰らな…くて…は…"), "帰らな…くて…は…")
        assertEquals("あのそう…", MangaMt.joinSplitWords("あの…そう…"), "あの…そう…")
        assertEquals("ドキドキ", MangaMt.joinSplitWords("ドキ…ドキ"), "ドキ…ドキ")
    }

    @Test fun `named honorifics are found only for the gated language`() {
        assertEquals("{\"ローズ\": \"san\"}", dump(MangaMt.findNamedHonorifics(listOf("ローズさん、こんにちは"), "ja")), "ローズさん、こんにちは @ja")
        assertEquals("{\"ナハト\": \"san\"}", dump(MangaMt.findNamedHonorifics(listOf("ナハトさん"), "ja")), "ナハトさん @ja")
        assertEquals("{\"ベル\": \"kun\"}", dump(MangaMt.findNamedHonorifics(listOf("ベル君"), "ja")), "ベル君 @ja")
        assertEquals("{}", dump(MangaMt.findNamedHonorifics(listOf("金鑾殿很大"), "zh")), "金鑾殿很大 @zh")
        assertEquals("{\"민수\": \"ssi\"}", dump(MangaMt.findNamedHonorifics(listOf("민수씨"), "ko")), "민수씨 @ko")
        assertEquals("{}", dump(MangaMt.findNamedHonorifics(listOf("선배님"), "ko")), "선배님 @ko")
        assertEquals("{\"준호\": \"oppa\"}", dump(MangaMt.findNamedHonorifics(listOf("준호 오빠"), "ko")), "준호 오빠 @ko")
        assertEquals("{\"金鑾\": \"dono\"}", dump(MangaMt.findNamedHonorifics(listOf("金鑾殿很大"), "ja")), "金鑾殿很大 @ja")
    }

    @Test fun `dropped honorifics are re-attached like the web pipeline`() {
        assertEquals("Hello Rose-san,", MangaMt.reattachHonorific("Hello Rose,", "rose", "san"), "Hello Rose,")
        assertEquals("Hello Luffy-sama", MangaMt.reattachHonorific("Hello Luffy-sama", "luffy", "sama"), "Hello Luffy-sama")
        assertEquals("Junho oppa is here", MangaMt.reattachHonorific("Junho oppa is here", "junho", "oppa"), "Junho oppa is here")
        assertEquals("Hello Bell-kun,", MangaMt.reattachHonorific("Hello Bell,", "bell", "kun"), "Hello Bell,")
    }

    @Test fun `title honorifics are restored only for Japanese`() {
        assertEquals("Maruyama-san...", MangaMt.restoreHonorifics("Mr. Maruyama...", "丸山さん…", "ja"), "丸山さん… @ja")
        assertEquals("Tanaka-sensei", MangaMt.restoreHonorifics("Teacher Tanaka", "田中先生", "ja"), "田中先生 @ja")
        assertEquals("Mr. Maruyama...", MangaMt.restoreHonorifics("Mr. Maruyama...", "丸山さん…", "zh"), "丸山さん… @zh")
        assertEquals("Keyaruga-dono", MangaMt.restoreHonorifics("Lord Keyaruga", "ケヤルガ殿", "ja"), "ケヤルガ殿 @ja")
    }
}
