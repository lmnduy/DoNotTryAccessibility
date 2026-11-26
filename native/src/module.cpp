#include <jni.h>
#include <android/log.h>

#include <cstdlib>
#include <cstring>

#include "zygisk.hpp"

#define LOG_TAG "DNTA-Zygisk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {
bool register_Pine(JNIEnv *env, jclass clazz);
bool register_Ruler(JNIEnv *env, jclass clazz);
extern const unsigned char dnta_dex[];
extern const unsigned int dnta_dex_len;
}

namespace {

constexpr const char *kPineClass = "top.canyie.pine.Pine";
constexpr const char *kRulerClass = "top.canyie.pine.Ruler";
constexpr const char *kBootstrapClass =
        "io.github.nitsuya.donottryaccessibility.hook.Bootstrap";

bool gInjected = false;
jobject gDexLoader = nullptr;

bool clearException(JNIEnv *env, const char *where) {
    if (env->ExceptionCheck() == JNI_TRUE) {
        LOGE("%s: JNI exception", where);
        env->ExceptionDescribe();
        env->ExceptionClear();
        return true;
    }
    return false;
}

jclass loadClass(JNIEnv *env, jobject loader, const char *name) {
    jclass classLoaderClass = env->FindClass("java/lang/ClassLoader");
    if (classLoaderClass == nullptr) {
        clearException(env, "FindClass(ClassLoader)");
        return nullptr;
    }
    jmethodID loadClassMethod = env->GetMethodID(
            classLoaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (loadClassMethod == nullptr) {
        clearException(env, "GetMethodID(ClassLoader.loadClass)");
        return nullptr;
    }
    jstring className = env->NewStringUTF(name);
    auto clazz = static_cast<jclass>(
            env->CallObjectMethod(loader, loadClassMethod, className));
    env->DeleteLocalRef(className);
    if (clearException(env, "ClassLoader.loadClass")) {
        return nullptr;
    }
    return clazz;
}

jobject createDexLoader(JNIEnv *env) {
    jclass classLoaderClass = env->FindClass("java/lang/ClassLoader");
    if (classLoaderClass == nullptr) {
        clearException(env, "FindClass(ClassLoader)");
        return nullptr;
    }
    jmethodID getSystemClassLoader = env->GetStaticMethodID(
            classLoaderClass, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    if (getSystemClassLoader == nullptr) {
        clearException(env, "GetStaticMethodID(getSystemClassLoader)");
        return nullptr;
    }
    jobject parent = env->CallStaticObjectMethod(classLoaderClass, getSystemClassLoader);
    if (clearException(env, "getSystemClassLoader")) {
        return nullptr;
    }

    jclass inMemoryClass = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    if (inMemoryClass == nullptr) {
        clearException(env, "FindClass(InMemoryDexClassLoader)");
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(inMemoryClass, "<init>",
                                             "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    if (constructor == nullptr) {
        clearException(env, "GetMethodID(InMemoryDexClassLoader)");
        return nullptr;
    }

    // The byte buffer must stay alive and writable for the lifetime of the
    // class loader, therefore the embedded dex is copied to the heap once.
    void *dexCopy = std::malloc(dnta_dex_len);
    if (dexCopy == nullptr) {
        LOGE("cannot allocate %u bytes for the embedded dex", dnta_dex_len);
        return nullptr;
    }
    std::memcpy(dexCopy, dnta_dex, dnta_dex_len);
    jobject buffer = env->NewDirectByteBuffer(dexCopy, static_cast<jlong>(dnta_dex_len));
    if (buffer == nullptr) {
        clearException(env, "NewDirectByteBuffer");
        std::free(dexCopy);
        return nullptr;
    }

    jobject loader = env->NewObject(inMemoryClass, constructor, buffer, parent);
    if (clearException(env, "new InMemoryDexClassLoader")) {
        return nullptr;
    }
    return loader;
}

bool init(JNIEnv *env) {
    if (gInjected) {
        return true;
    }
    LOGI("injecting into system_server (dex %u bytes)", dnta_dex_len);

    jobject loader = createDexLoader(env);
    if (loader == nullptr) {
        LOGE("cannot create the dex class loader");
        return false;
    }
    gDexLoader = env->NewGlobalRef(loader);

    jclass pineClass = loadClass(env, loader, kPineClass);
    jclass rulerClass = loadClass(env, loader, kRulerClass);
    if (pineClass == nullptr || rulerClass == nullptr) {
        LOGE("cannot load Pine classes");
        return false;
    }
    if (!register_Pine(env, pineClass) || !register_Ruler(env, rulerClass)) {
        LOGE("cannot register Pine native methods");
        return false;
    }

    jclass bootstrapClass = loadClass(env, loader, kBootstrapClass);
    if (bootstrapClass == nullptr) {
        LOGE("cannot load Bootstrap");
        return false;
    }
    jmethodID start = env->GetStaticMethodID(bootstrapClass, "start", "()V");
    if (start == nullptr) {
        clearException(env, "GetStaticMethodID(Bootstrap.start)");
        return false;
    }
    env->CallStaticVoidMethod(bootstrapClass, start);
    if (clearException(env, "Bootstrap.start")) {
        return false;
    }

    gInjected = true;
    LOGI("injection finished");
    return true;
}

class DoNotTryAccessibilityModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        // The module only needs to run inside system_server.  Every other
        // process is left untouched.
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *args) override {
        api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

    void preServerSpecialize(zygisk::ServerSpecializeArgs *args) override {
    }

    void postServerSpecialize(const zygisk::ServerSpecializeArgs *args) override {
        if (env_ == nullptr) {
            LOGE("no JNIEnv in postServerSpecialize");
            return;
        }
        // Never dlclose the library here: Pine's trampolines live in it.
        init(env_);
    }

private:
    zygisk::Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
};

}  // namespace

REGISTER_ZYGISK_MODULE(DoNotTryAccessibilityModule)
