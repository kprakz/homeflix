// Starts Node.js inside the app's server process and sends its console output to logcat.
// node::Start runs until the server exits; it can only run once per process, so the app stops the
// server by ending that process.
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "node.h"

namespace {
int pipeFds[2];

void *logLines(void *) {
    char buf[1024];
    ssize_t n;
    while ((n = read(pipeFds[0], buf, sizeof buf - 1)) > 0) {
        if (buf[n - 1] == '\n') n--;
        buf[n] = '\0';
        __android_log_write(ANDROID_LOG_INFO, "HOMEFLIX-node", buf);
    }
    return nullptr;
}

void redirectOutput() {
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    if (pipe(pipeFds) != 0) return;
    dup2(pipeFds[1], STDOUT_FILENO);
    dup2(pipeFds[1], STDERR_FILENO);
    pthread_t thread;
    if (pthread_create(&thread, nullptr, logLines, nullptr) == 0) pthread_detach(thread);
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_io_github_kprakz_homeflix_NodeRunner_startNode(JNIEnv *env, jclass, jobjectArray jargs) {
    redirectOutput();
    // Node expects all arguments in one contiguous block of memory.
    const int argc = env->GetArrayLength(jargs);
    std::vector<std::string> args;
    size_t total = 0;
    for (int i = 0; i < argc; i++) {
        auto s = (jstring) env->GetObjectArrayElement(jargs, i);
        const char *c = env->GetStringUTFChars(s, nullptr);
        args.emplace_back(c);
        env->ReleaseStringUTFChars(s, c);
        total += args.back().size() + 1;
    }
    char *block = static_cast<char *>(calloc(total, 1));
    std::vector<char *> argv;
    char *p = block;
    for (const auto &a : args) {
        memcpy(p, a.c_str(), a.size());
        argv.push_back(p);
        p += a.size() + 1;
    }
    return node::Start(argc, argv.data());
}
