#include <jni.h>
#include <cstddef>

extern "C" std::size_t ultron_classify_intent(const char*, char*, std::size_t);

extern "C" JNIEXPORT jstring JNICALL
Java_ai_ultron_mobile_NativeEngine_nativeAnalyze(JNIEnv* env, jobject, jstring input) {
    if (input == nullptr) return env->NewStringUTF("GENERAL_ASSISTANCE");
    const char* utf = env->GetStringUTFChars(input, nullptr);
    if (utf == nullptr) return env->NewStringUTF("GENERAL_ASSISTANCE");
    char output[96] = {};
    ultron_classify_intent(utf, output, sizeof(output));
    env->ReleaseStringUTFChars(input, utf);
    return env->NewStringUTF(output);
}
