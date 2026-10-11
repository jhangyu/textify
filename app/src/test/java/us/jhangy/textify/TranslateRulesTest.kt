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

    @Test
    fun undeterminedIsNotNeeded() {
        assertEquals(Route.NotNeeded, route(UNDETERMINED, null, hant))
    }

    @Test
    fun unsupportedSource() {
        assertEquals(Route.Unsupported, route("la", null, hant))
    }

    @Test
    fun chineseToTraditionalIsOpenCcOnly() {
        assertEquals(Route.OpenCcOnly, route("zh", "zh", hant))
    }

    @Test
    fun chineseToSimplifiedIsNotNeeded() {
        assertEquals(Route.NotNeeded, route("zh", "zh", hans))
    }

    @Test
    fun sameLanguageIsNotNeeded() {
        assertEquals(Route.NotNeeded, route("ja", "ja", TargetLang.of("ja")))
    }

    @Test
    fun otherSourceUsesMlKitWithOpenCcForTraditional() {
        assertEquals(Route.MlKit("en", true), route("en", "en", hant))
        assertEquals(Route.MlKit("en", false), route("en", "en", hans))
        assertEquals(Route.MlKit("ja", false), route("ja", "ja", en))
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
    fun targetKeysOrder() {
        val ml = listOf("zh", "ko", "fr", "en", "af", "ja")
        assertEquals(listOf("zh-Hant", "zh-Hans", "en", "ja", "ko", "af", "fr"), TargetLang.keys(ml))
        assertEquals(listOf("zh", "en", "ja", "ko", "af", "fr"), TargetLang.modelKeys(ml))
    }

    @Test
    fun targetOfKeys() {
        assertEquals(TargetLang("zh-Hant", "zh", true), TargetLang.of("zh-Hant"))
        assertEquals(TargetLang("zh-Hans", "zh", false), TargetLang.of("zh-Hans"))
        assertEquals(TargetLang("fr", "fr", false), TargetLang.of("fr"))
        assertEquals("zh-Hant", TargetLang.DEFAULT)
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
        assertFalse(needsRun(ParagraphState.Downloading))
        assertFalse(needsRun(ParagraphState.NotNeeded))
        assertFalse(needsRun(ParagraphState.Done("x", "en")))
    }

    @Test
    fun modelRowsBuiltinFirstAndPriority() {
        val rows = modelRows(downloaded = setOf("en", "zh", "ja"), downloading = setOf("ko", "ja"), waiting = setOf("fr", "zh"))
        assertEquals(
            listOf(
                "en" to ModelState.BUILTIN,
                "fr" to ModelState.WAITING_WIFI,
                "ja" to ModelState.DOWNLOADING,
                "ko" to ModelState.DOWNLOADING,
                "zh" to ModelState.DONE,
            ),
            rows,
        )
        assertEquals(listOf("en" to ModelState.BUILTIN), modelRows(emptySet(), emptySet(), emptySet()))
    }

    @Test
    fun romanizedTagsAreUndetermined() {
        assertEquals(Route.NotNeeded, route("ja-Latn", null, hant))
        assertEquals(Route.NotNeeded, route("zh-Latn", "zh", hant))
    }
}
