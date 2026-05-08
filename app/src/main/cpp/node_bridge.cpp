#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdlib>

#define TAG "NodeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Forward-declare node::Start from the imported libnode.so.
// Resolved at link time; no node headers needed.
namespace node {
    int Start(int argc, char** argv);
}

extern "C" JNIEXPORT void JNICALL
Java_com_claudecodesetup_NodeEngine_nativeStart(
        JNIEnv* env, jclass /*clazz*/, jobjectArray jargs)
{
    auto argc = static_cast<int>(env->GetArrayLength(jargs));
    auto argv = new char*[static_cast<size_t>(argc) + 1];

    for (int i = 0; i < argc; i++) {
        auto jstr = static_cast<jstring>(env->GetObjectArrayElement(jargs, i));
        const char* cstr = env->GetStringUTFChars(jstr, nullptr);
        argv[i] = strdup(cstr);
        env->ReleaseStringUTFChars(jstr, cstr);
        env->DeleteLocalRef(jstr);
    }
    argv[argc] = nullptr;

    LOGI("Starting Node.js with %d args; script=%s",
         argc, argc > 1 ? argv[1] : "(none)");

    int ret = node::Start(argc, argv);
    LOGI("Node.js exited with code %d", ret);

    for (int i = 0; i < argc; i++) free(argv[i]);
    delete[] argv;
}
