package us.jhangy.textify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MangaOcrTest {

    /** Repo root (Gradle runs unit tests in app/). */
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "scripts").isDirectory) it else it.parentFile }
    private val probe = File(root, "scripts/tmp/ocrprobe")

    /**
     * The real page's merged ML Kit paragraphs (merged_after.txt: box, vertical flag, lines joined by "|") with the
     * ML Kit characters (symbols.txt) whose centre lies in each box.
     */
    private fun realPage(): List<OcrCandidate> {
        val box = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
        fun Box.has(c: OcrChar) = (c.box.left + c.box.right) / 2 in left..right && (c.box.top + c.box.bottom) / 2 in top..bottom
        val chars = File(probe, "symbols.txt").readLines().filter { it.startsWith("LINE") }.flatMap { l ->
            Regex("""([^\s,\[]+?)\[(\d+),(\d+)]\[(\d+),(\d+)]""").findAll(l.substringAfter("syms=[")).map { m ->
                val (t, a, b, c, d) = m.destructured
                OcrChar(t, Box(a.toInt(), b.toInt(), c.toInt(), d.toInt()))
            }.toList()
        }
        return File(probe, "merged_after.txt").readLines().mapNotNull { l ->
            val m = box.find(l) ?: return@mapNotNull null
            val (a, b, c, d) = m.destructured
            val bx = Box(a.toInt(), b.toInt(), c.toInt(), d.toInt())
            val text = l.substringAfter(" text=").replace('|', '\n')
            OcrCandidate(text, bx, text.split('\n').map { bx }, 0f, "vertical=true" in l, chars.filter { bx.has(it) })
        }
    }

    @Test
    fun realPageBubblesReadInReadingOrder() {
        val dir = File(System.getProperty("user.home"), ".cache/textify-models/manga/big")
        val files = listOf("encoder_model_int8.onnx", "decoder_model_int8.onnx", "vocab.txt").map { File(dir, it) }
        files.forEach { assertTrue("model file missing: $it", it.isFile) }
        // javax.imageio is not on the Android compile classpath (android.jar), but the test JVM has it
        val img = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java)
            .invoke(null, File(root, "scripts/tmp/2086027158510792874.jpg"))
        fun int(name: String) = img.javaClass.getMethod(name).invoke(img) as Int
        val getRgb = img.javaClass.getMethod("getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            IntArray::class.java, Int::class.java, Int::class.java)
        val crops = mangaCrops(realPage(), int("getWidth"), int("getHeight"))
        val ocr = MangaOcr(files[0], files[1], files[2])
        val texts = crops.map { c ->
            val px = getRgb.invoke(img, c.crop.left, c.crop.top, c.crop.width, c.crop.height, null, 0, c.crop.width) as IntArray
            ocr.read(px, c.crop.width, c.crop.height).orEmpty().filterNot { it.isWhitespace() }
        }
        ocr.close()
        println("manga-ocr crops: " + crops.zip(texts))
        assertTrue(texts.toString(), texts.any { it == "パパが見ていいのはこっち" || it == "パパが見ていいのはこっち♥" })
        assertTrue(texts.toString(), "ちょっと寝室に来て…" in texts)
        assertTrue(texts.toString(), "話があるから…" in texts)
    }

    @Test
    fun readAfterCloseRunsNoInference() {
        val dir = File(System.getProperty("user.home"), ".cache/textify-models/manga/big")
        val files = listOf("encoder_model_int8.onnx", "decoder_model_int8.onnx", "vocab.txt").map { File(dir, it) }
        files.forEach { assertTrue("model file missing: $it", it.isFile) }
        val img = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java)
            .invoke(null, File(root, "scripts/tmp/2086027158510792874.jpg"))
        val crop = Box(1137, 248, 1239, 485)                            // も〜！ (a read here would return text)
        val px = img.javaClass.getMethod("getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            IntArray::class.java, Int::class.java, Int::class.java)
            .invoke(img, crop.left, crop.top, crop.width, crop.height, null, 0, crop.width) as IntArray
        val ocr = MangaOcr(files[0], files[1], files[2])
        ocr.close()
        assertNull(ocr.read(px, crop.width, crop.height))
    }

    @Test
    fun throwingReadKeepsMlKitResult() {
        val page = realPage()
        val crops = mangaCrops(page, 1240, 2000)
        assertEquals(page, applyMangaReads(page, crops) { throw OutOfMemoryError("crop") })
    }

    @Test
    fun triggerOnRealPageBubblesNotOnLongHorizontalOrChinese() {
        val page = realPage()
        for (start in listOf("こいパ", "もう", "よと", "話が")) {
            val p = page.single { it.text.startsWith(start) }
            assertTrue(p.text, mangaTrigger(p.text, p.vertical))
        }
        assertFalse(mangaTrigger("これはアプリの設定画面に表示される長い説明文の一行です。", false))   // 25 characters
        assertFalse(mangaTrigger("我們今天去看電影", true))
    }

    @Test
    fun cropAbsorbsLoneNeighbourAndPadsHalfACharacter() {
        val page = realPage()
        val crops = mangaCrops(page, 1240, 2000)
        val papa = crops.single { page[it.index].text.startsWith("こいパ") }
        assertEquals(listOf(page.indexOfFirst { it.text == "て" }), papa.absorbed)
        assertEquals(Box(48, 811, 217, 1064), papa.box)
        assertEquals(Box(48 - 25, 811 - 25, 217 + 25, 1064 + 25), papa.crop)   // median char 49 -> pad 25
    }

    @Test
    fun failedReadKeepsMlKitTextAndNeighbours() {
        val page = realPage()
        val crops = mangaCrops(page, 1240, 2000)
        assertEquals(page.sortedWith(compareBy({ it.box.top }, { it.box.left })), applyMangaReads(page, crops) { null })
        val read = applyMangaReads(page, crops) { "x" }
        assertEquals(page.size - 1, read.size)                          // て absorbed
        assertTrue(read.filter { it.text == "x" }.all { it.chars.isEmpty() && it.lines == listOf(it.box) })
    }

    @Test
    fun repetitionGuardFailsLoopingDecode() {
        val loop = listOf(10, 11, 12, 13, 14, 15)
        var n = 0
        assertNull(greedyDecode { loop[n++ % loop.size] })              // never EOS, cycles 6 tokens
        assertNull(greedyDecode { 42 })                                // one token forever
        val aborted = listOf(20, -1, 3)                                // -1 = engine closed mid-decode
        n = 0
        assertNull(greedyDecode { aborted[n++] })
        val text = listOf(20, 21, 22, 3)
        n = 0
        assertEquals(listOf(20, 21, 22), greedyDecode { text[n++] })
        assertTrue(looping(listOf(1, 2, 3, 4, 1, 2, 3, 4)))
        assertFalse(looping(listOf(1, 2, 3, 4, 1, 2, 3, 5)))
    }

    @Test
    fun normalizationMapsDotsToEllipsis() {
        assertEquals("ちょっと寝室に来て…", mangaNormalize("ちょっと 寝室に来て．．．"))
        assertEquals("話が…", mangaNormalize("話が..."))
        assertEquals("も〜！", mangaNormalize("も〜!"))
        assertEquals("ａｂ", mangaText(listOf(2, 5, 6, 3), listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "x", "a", "##b")))
    }
}
