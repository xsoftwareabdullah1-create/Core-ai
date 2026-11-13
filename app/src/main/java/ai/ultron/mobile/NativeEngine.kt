package ai.ultron.mobile

/**
 * Safe bridge to the optional local Rust classifier.
 * Results are advisory only: execution is handled by the approval-gated tools.
 */
object NativeEngine {
    val available: Boolean = try {
        System.loadLibrary("ultron_rust")
        System.loadLibrary("ultron_jni")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    private external fun nativeAnalyze(text: String): String
    private external fun nativeAnalyzeBatch(texts: Array<String>): Array<String>
    private external fun nativeVersion(): Int

    fun classify(text: String): String {
        if (!available || text.length > 16_384) return "GENERAL_ASSISTANCE"
        return runCatching { nativeAnalyze(text) }.getOrDefault("GENERAL_ASSISTANCE")
    }

    fun classifyBatch(texts: List<String>): List<String> {
        if (!available || texts.size > 64) return texts.map { "GENERAL_ASSISTANCE" }
        return runCatching { nativeAnalyzeBatch(texts.toTypedArray()).toList() }
            .getOrElse { texts.map { "GENERAL_ASSISTANCE" } }
    }

    fun engineVersion(): Int =
        if (available) runCatching { nativeVersion() }.getOrDefault(0) else 0
}
