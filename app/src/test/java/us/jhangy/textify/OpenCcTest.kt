package us.jhangy.textify

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCcTest {

    @Test
    fun simplifiedBecomesTaiwanPhrases() {
        assertEquals("使用網際網路", OpenCc.toTaiwan("使用互联网"))
        assertEquals("軟體和記憶體", OpenCc.toTaiwan("软件和内存"))
    }

    @Test
    fun traditionalUnchanged() {
        assertEquals("吉卜力美術館預約", OpenCc.toTaiwan("吉卜力美術館預約"))
    }

    @Test
    fun lineBreaksKept() {
        assertEquals("軟體\n記憶體", OpenCc.toTaiwan("软件\n内存"))
    }
}
