package us.jhangy.textify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslateRulesTest {

    private val hant = TargetLang.of(TargetLang.ZH_HANT)
    private val hans = TargetLang.of(TargetLang.ZH_HANS)
    private val en = TargetLang.of("en")

    private fun spec(key: String, supports: (String) -> Boolean = { true }) = ModelSpec(
        key, key, "", "", PinnedFile("repo", "f.litertlm", "rev", 1L), Sampler(1, 1.0, 1.0), emptyMap(), { _, _ -> "" }, { it }, supports,
    )

    private val a = spec("a")
    private val ready = setOf("a")

    @Test
    fun undeterminedIsNotNeeded() {
        assertEquals(Route.NotNeeded, route(UNDETERMINED, hant, a, ready))
    }

    @Test
    fun romanizedTagsAreUndetermined() {
        assertEquals(Route.NotNeeded, route("ja-Latn", hant, a, ready))
        assertEquals(Route.NotNeeded, route("zh-Latn", hant, a, ready))
    }

    @Test
    fun chineseToTraditionalIsOpenCcOnly() {
        assertEquals(Route.OpenCcOnly, route("zh", hant, a, emptySet()))   // needs no model
    }

    @Test
    fun chineseToSimplifiedIsNotNeeded() {
        assertEquals(Route.NotNeeded, route("zh", hans, a, ready))
    }

    @Test
    fun sameLanguageIsNotNeeded() {
        assertEquals(Route.NotNeeded, route("ja", TargetLang.of("ja"), a, ready))
    }

    @Test
    fun supportedPairWithModelDownloadedUsesLlm() {
        assertEquals(Route.Llm("en"), route("en", hant, a, ready))
        assertEquals(Route.Llm("ja"), route("ja", en, a, ready))
    }

    @Test
    fun modelMissingNeedsDownload() {
        assertEquals(Route.NeedsModel, route("ja", hant, a, emptySet()))
        assertEquals(Route.NeedsModel, route("ja", hant, a, setOf("b")))   // another model on disk does not count
    }

    @Test
    fun unsupportedPairIsUnsupportedEvenWithoutModel() {
        val noJa = spec("a") { it != "ja" }
        assertEquals(Route.Unsupported, route("ja", en, noJa, ready))
        assertEquals(Route.Unsupported, route("ja", en, noJa, emptySet()))
        assertEquals(Route.Unsupported, route("en", TargetLang.of("fr"), spec("a") { it != "fr" }, ready))
    }

    @Test
    fun unchangedOpenCcOutputIsNotNeeded() {
        val traditional = "好，我週五晚上到。"
        assertEquals(ParagraphState.NotNeeded, openCcOnlyResult(traditional, OpenCc.toTaiwan(traditional)))
        assertEquals(ParagraphState.Done("使用網際網路", "zh"), openCcOnlyResult("使用互联网", OpenCc.toTaiwan("使用互联网")))
    }

    @Test
    fun flattenJoinsLines() {
        assertEquals("Heads up: the museum", flatten("Heads up:\nthe museum", null))
        assertEquals("Heads up: the museum", flatten("Heads up:\nthe museum", "en"))
        assertEquals("我已經把行程表更新", flatten("我已經把\n行程表更新", "zh"))
        assertEquals("チェックインは15時から", flatten("チェックインは\n15時から", "ja"))
        assertEquals("내일 아침", flatten("내일\n아침", "ko"))
    }

    @Test
    fun targetKeysCoverStoredKeysAndAreUnique() {
        val keys = TargetLang.keys
        assertEquals(listOf("zh-Hant", "zh-Hans", "en", "ja", "ko"), keys.take(5))
        assertEquals(59, keys.size)   // the 58 codes ML Kit translation offered, zh split in two targets
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys.drop(5), keys.drop(5).sorted())
        assertTrue("fr" in keys && "he" in keys && "zh" !in keys)
    }

    @Test
    fun targetOfKeys() {
        assertEquals(TargetLang("zh-Hant", "zh", true), TargetLang.of("zh-Hant"))
        assertEquals(TargetLang("zh-Hans", "zh", false), TargetLang.of("zh-Hans"))
        assertEquals(TargetLang("fr", "fr", false), TargetLang.of("fr"))
        assertEquals("zh-Hant", TargetLang.DEFAULT)
        assertTrue(TargetLang.DEFAULT in TargetLang.keys)   // Prefs falls back to it for an unknown key
    }

    @Test
    fun chipsButtonAndRetry() {
        assertEquals(Chip.DOWNLOADING, chipFor(ParagraphState.Downloading))
        assertEquals(Chip.WAITING_WIFI, chipFor(ParagraphState.WaitingWifi))
        assertEquals(Chip.UNSUPPORTED, chipFor(ParagraphState.Unsupported))
        assertEquals(Chip.FAILED, chipFor(ParagraphState.Failed))
        assertNull(chipFor(ParagraphState.Translating))
        assertNull(chipFor(ParagraphState.Done("x", "en")))
        assertNull(chipFor(null))
        assertTrue(anyTranslating(listOf(null, ParagraphState.Downloading, ParagraphState.Translating)))
        assertFalse(anyTranslating(listOf(ParagraphState.Downloading, ParagraphState.Done("x", "en"))))
        assertTrue(needsRun(null))
        assertTrue(needsRun(ParagraphState.WaitingWifi))
        assertTrue(needsRun(ParagraphState.Failed))
        assertTrue(needsRun(ParagraphState.Downloading))   // the next press translates once the model is there
        assertFalse(needsRun(ParagraphState.Translating))
        assertFalse(needsRun(ParagraphState.NotNeeded))
        assertFalse(needsRun(ParagraphState.Done("x", "en")))
    }
}
