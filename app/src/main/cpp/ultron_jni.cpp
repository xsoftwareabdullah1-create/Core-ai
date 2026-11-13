#include <jni.h>
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>

extern "C" std::size_t ultron_classify_intent(const char*, char*, std::size_t);
extern "C" std::uint32_t ultron_engine_version();

namespace ultron {
constexpr std::size_t kMaximumInput = 16 * 1024;
constexpr std::size_t kOutputCapacity = 96;

class UtfChars final {
public:
    UtfChars(JNIEnv* env, jstring value) : env_(env), value_(value) {
        if (env_ && value_) chars_ = env_->GetStringUTFChars(value_, nullptr);
    }
    ~UtfChars() {
        if (chars_) env_->ReleaseStringUTFChars(value_, chars_);
    }
    UtfChars(const UtfChars&) = delete;
    UtfChars& operator=(const UtfChars&) = delete;
    const char* get() const { return chars_; }
    bool valid() const { return chars_ != nullptr; }
private:
    JNIEnv* env_;
    jstring value_;
    const char* chars_ = nullptr;
};

std::string classify(const char* input) {
    if (!input) return "GENERAL_ASSISTANCE";
    const auto length = std::strlen(input);
    if (length == 0 || length > kMaximumInput) return "GENERAL_ASSISTANCE";
    char output[kOutputCapacity] = {};
    const auto written = ultron_classify_intent(input, output, sizeof(output));
    if (written == 0 || output[0] == '\0') return "GENERAL_ASSISTANCE";
    return std::string(output, std::min(written, sizeof(output) - 1));
}

jstring makeString(JNIEnv* env, const std::string& value) {
    return env ? env->NewStringUTF(value.c_str()) : nullptr;
}
} // namespace ultron

extern "C" JNIEXPORT jstring JNICALL
Java_ai_ultron_mobile_NativeEngine_nativeAnalyze(JNIEnv* env, jobject, jstring input) {
    if (!env || !input) return ultron::makeString(env, "GENERAL_ASSISTANCE");
    ultron::UtfChars chars(env, input);
    if (!chars.valid()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return ultron::makeString(env, "GENERAL_ASSISTANCE");
    }
    return ultron::makeString(env, ultron::classify(chars.get()));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_ai_ultron_mobile_NativeEngine_nativeAnalyzeBatch(JNIEnv* env, jobject, jobjectArray inputs) {
    if (!env || !inputs) return nullptr;
    const jsize count = env->GetArrayLength(inputs);
    if (count < 0 || count > 64) return nullptr;
    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return nullptr;
    jobjectArray results = env->NewObjectArray(count, stringClass, nullptr);
    env->DeleteLocalRef(stringClass);
    if (!results) return nullptr;

    for (jsize i = 0; i < count; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(inputs, i));
        std::string label = "GENERAL_ASSISTANCE";
        if (item) {
            ultron::UtfChars chars(env, item);
            if (chars.valid()) label = ultron::classify(chars.get());
            else if (env->ExceptionCheck()) env->ExceptionClear();
            env->DeleteLocalRef(item);
        }
        jstring value = ultron::makeString(env, label);
        if (!value) return nullptr;
        env->SetObjectArrayElement(results, i, value);
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) return nullptr;
    }
    return results;
}

extern "C" JNIEXPORT jint JNICALL
Java_ai_ultron_mobile_NativeEngine_nativeVersion(JNIEnv*, jobject) {
    return static_cast<jint>(ultron_engine_version());
}
