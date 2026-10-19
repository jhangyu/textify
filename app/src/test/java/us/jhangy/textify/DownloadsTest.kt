package us.jhangy.textify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsTest {

    private val sha = Regex("[0-9a-f]{40}")

    @Test
    fun urlIsRepoRevisionFile() {
        val llm = modelOf("hymt").pinned
        assertEquals("https://huggingface.co/${llm.repo}/resolve/${llm.revision}/${llm.file}", llm.url)
        val enc = MANGA_OCR.encoder
        assertEquals(
            "https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/${enc.revision}/onnx/encoder_model_int8.onnx",
            enc.url,
        )
    }

    @Test
    fun mangaOcrFilesArePinnedAndDoNotClash() {
        val all = MANGA_OCR.all
        assertEquals(3, all.size)
        for (f in all) {
            assertTrue(f.file, sha.matches(f.revision))
            assertFalse(f.file, "/resolve/main/" in f.url)
        }
        assertEquals(all.size, all.map { it.relPath }.toSet().size)
        assertEquals(86_967_767L + 29_627_936L + 24_072L, all.sumOf { it.size })
    }

    @Test
    fun storagePaths() {
        for (m in MODELS) assertEquals(m.pinned.file, m.pinned.relPath)
        assertEquals("manga-ocr/onnx/encoder_model_int8.onnx", MANGA_OCR.encoder.relPath)
        assertEquals("manga-ocr/vocab.txt", MANGA_OCR.vocab.relPath)
    }

    @Test
    fun onlyTheExactSizeIsComplete() {
        for (f in MANGA_OCR.all + MODELS.map { it.pinned }) {
            assertTrue(f.complete(f.size))
            assertFalse(f.complete(f.size - 1))
            assertFalse(f.complete(f.size + 1))
            assertFalse(f.complete(0))
        }
    }
}
