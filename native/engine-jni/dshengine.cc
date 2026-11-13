// libdshengine.so — JNI entry point that runs Node.js (libnode.so) in-process.
//
// Java side (io.github.timhub135.dsh.engine.NodeRuntime):
//   static native int nativeStart(String[] args, int stdinFd, int stdoutFd, int stderrFd);
//
// nativeStart() blocks until the Node event loop exits and returns the exit
// code. node::Start() may only be called once per process (V8/Node global
// state is not re-initializable), so the app must run it in a dedicated
// process (e.g. android:process=":engine") and restart that process to run
// the engine again.

#include <jni.h>
#include <node.h>

#include <android/log.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

constexpr const char* kTag = "dshengine";
JavaVM* g_vm = nullptr;
std::atomic<bool> g_started{false};

void RedirectFd(int from, int to) {
  if (from < 0 || from == to) return;
  if (dup2(from, to) < 0) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "dup2(%d, %d) failed: %s", from, to,
                        strerror(errno));
  }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
  g_vm = vm;
  return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_timhub135_dsh_engine_NodeRuntime_nativeStart(JNIEnv* env, jclass /*clazz*/,
                                                           jobjectArray args, jint stdinFd,
                                                           jint stdoutFd, jint stderrFd) {
  if (g_started.exchange(true)) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "node::Start already called in this process");
    return -1;
  }

  RedirectFd(stdinFd, STDIN_FILENO);
  RedirectFd(stdoutFd, STDOUT_FILENO);
  RedirectFd(stderrFd, STDERR_FILENO);

  // Node requires argv strings to live in one contiguous block (it may
  // rewrite argv in place for process.title), so pack them together.
  std::vector<std::string> strs;
  strs.emplace_back("node");
  const jsize n = args != nullptr ? env->GetArrayLength(args) : 0;
  for (jsize i = 0; i < n; ++i) {
    auto js = static_cast<jstring>(env->GetObjectArrayElement(args, i));
    if (js == nullptr) {
      strs.emplace_back();
      continue;
    }
    const char* utf = env->GetStringUTFChars(js, nullptr);
    strs.emplace_back(utf != nullptr ? utf : "");
    if (utf != nullptr) env->ReleaseStringUTFChars(js, utf);
    env->DeleteLocalRef(js);
  }

  size_t total = 0;
  for (const auto& s : strs) total += s.size() + 1;
  // Intentionally leaked: node keeps pointers into argv for the process lifetime.
  char* block = static_cast<char*>(calloc(total, 1));
  char** argv = static_cast<char**>(calloc(strs.size() + 1, sizeof(char*)));
  if (block == nullptr || argv == nullptr) return -1;
  char* p = block;
  for (size_t i = 0; i < strs.size(); ++i) {
    memcpy(p, strs[i].data(), strs[i].size());
    argv[i] = p;
    p += strs[i].size() + 1;
  }
  const int argc = static_cast<int>(strs.size());

  __android_log_print(ANDROID_LOG_INFO, kTag, "node::Start argc=%d", argc);
  const int code = node::Start(argc, argv);
  __android_log_print(ANDROID_LOG_INFO, kTag, "node::Start returned %d", code);
  fflush(stdout);
  fflush(stderr);
  return code;
}
