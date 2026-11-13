package io.github.timhub135.dsh.engine

// Package must match the JNI symbol exported by native/engine-jni/dshengine.cc.

/** JNI entry into libdshengine.so, which calls node::Start on the given fds. `args` excludes argv[0]. */
object NodeRuntime {
    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("node")
        System.loadLibrary("dshengine")
    }

    @JvmStatic
    external fun nativeStart(args: Array<String>, stdinFd: Int, stdoutFd: Int, stderrFd: Int): Int
}
