package us.jhangy.textify

import com.github.houbb.opencc4j.util.ZhTwConverterUtil

/**
 * OpenCC "Simplified -> Taiwan standard with Taiwan phrases" (s2twp) through opencc4j (translation spec
 * decision 7). Blocking; the first call loads the dictionaries, so call it off the main thread.
 */
object OpenCc {
    /** [text] converted, or [text] unchanged when the conversion fails (spec §5: never fail the paragraph). */
    fun toTaiwan(text: String): String = try {
        ZhTwConverterUtil.toTraditional(text)
    } catch (e: Exception) {
        text
    } catch (e: LinkageError) {          // dictionary / class initialisation failure on a device
        text
    }
}
