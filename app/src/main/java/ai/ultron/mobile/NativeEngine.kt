package ai.ultron.mobile

object NativeEngine {
    val available: Boolean = try {
        System.loadLibrary("ultron_rust")
        System.loadLibrary("ultron_jni")
        true
    } catch (_: UnsatisfiedLinkError) { false }

    external fun nativeAnalyze(text: String): String
    fun classify(text: String): String =
        if (available) runCatching { nativeAnalyze(text) }.getOrDefault("GENERAL_ASSISTANCE")
        else "NATIVE_CORE_NOT_PACKAGED"
}
