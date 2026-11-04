#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#include "node.h"

namespace {
int pipe_stdout[2];
int pipe_stderr[2];
const char* TAG = "HOMEFLIX_NODE";

void* pump_stdout(void*) {
    char buf[2048];
    ssize_t n;
    while ((n = read(pipe_stdout[0], buf, sizeof buf - 1)) > 0) {
        if (buf[n - 1] == '\n') --n;
        buf[n] = 0;
        __android_log_write(ANDROID_LOG_INFO, TAG, buf);
    }
    return nullptr;
}

void* pump_stderr(void*) {
    char buf[2048];
    ssize_t n;
    while ((n = read(pipe_stderr[0], buf, sizeof buf - 1)) > 0) {
        if (buf[n - 1] == '\n') --n;
        buf[n] = 0;
        __android_log_write(ANDROID_LOG_ERROR, TAG, buf);
    }
    return nullptr;
}

int redirect_output() {
    setvbuf(stdout, nullptr, _IONBF, 0);
    if (pipe(pipe_stdout) != 0) return -1;
    dup2(pipe_stdout[1], STDOUT_FILENO);
    setvbuf(stderr, nullptr, _IONBF, 0);
    if (pipe(pipe_stderr) != 0) return -1;
    dup2(pipe_stderr[1], STDERR_FILENO);
    pthread_t t;
    if (pthread_create(&t, nullptr, pump_stdout, nullptr) != 0) return -1;
    pthread_detach(t);
    if (pthread_create(&t, nullptr, pump_stderr, nullptr) != 0) return -1;
    pthread_detach(t);
    return 0;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_homeflix_app_server_NativeNode_startNode(
        JNIEnv* env, jobject, jobjectArray arguments, jstring workDir,
        jstring ffmpegPath, jstring ffprobePath) {
    const jsize count = env->GetArrayLength(arguments);

    jsize size = 0;
    for (jsize i = 0; i < count; i++) {
        auto arg = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
        const char* text = env->GetStringUTFChars(arg, nullptr);
        size += static_cast<jsize>(strlen(text)) + 1;
        env->ReleaseStringUTFChars(arg, text);
        env->DeleteLocalRef(arg);
    }

    auto* buffer = static_cast<char*>(calloc(static_cast<size_t>(size), 1));
    char* cursor = buffer;
    auto** argv = static_cast<char**>(calloc(static_cast<size_t>(count), sizeof(char*)));
    for (jsize i = 0; i < count; i++) {
        auto arg = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
        const char* text = env->GetStringUTFChars(arg, nullptr);
        strcpy(cursor, text);
        argv[i] = cursor;
        cursor += strlen(text) + 1;
        env->ReleaseStringUTFChars(arg, text);
        env->DeleteLocalRef(arg);
    }

    const char* cwd = env->GetStringUTFChars(workDir, nullptr);
    const char* ffmpeg = env->GetStringUTFChars(ffmpegPath, nullptr);
    const char* ffprobe = env->GetStringUTFChars(ffprobePath, nullptr);
    chdir(cwd);
    setenv("FFMPEG_PATH", ffmpeg, 1);
    setenv("FFPROBE_PATH", ffprobe, 1);
    setenv("HOME", cwd, 1);
    __android_log_print(ANDROID_LOG_INFO, TAG, "start node cwd=%s ffmpeg=%s", cwd, ffmpeg);

    if (redirect_output() != 0) {
        __android_log_write(ANDROID_LOG_ERROR, TAG, "could not redirect node output");
    }

    const int result = node::Start(count, argv);
    __android_log_print(ANDROID_LOG_INFO, TAG, "node exited with %d", result);
    return result;
}
