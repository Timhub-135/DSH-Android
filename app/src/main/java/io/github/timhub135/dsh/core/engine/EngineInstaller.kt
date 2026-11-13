package io.github.timhub135.dsh.core.engine

import android.content.Context
import android.system.Os
import java.io.File
import java.util.zip.ZipInputStream

/** Unpacks assets/engine.zip into files/engine/<sha> once per engine build. Runs in :engine. */
object EngineInstaller {
    fun install(context: Context): File {
        val sha = context.assets.open("engine.sha256").bufferedReader().readText().trim().take(16)
        val root = File(context.filesDir, "engine")
        val dir = File(root, sha)
        val marker = File(dir, ".installed")
        if (!marker.exists()) {
            root.listFiles()?.filter { it.name != sha }?.forEach { it.deleteRecursively() }
            dir.deleteRecursively()
            dir.mkdirs()
            ZipInputStream(context.assets.open("engine.zip").buffered()).use { zip ->
                generateSequence { zip.nextEntry }.forEach { e ->
                    val out = File(dir, e.name).canonicalFile
                    require(out.path.startsWith(dir.canonicalPath)) { "bad entry ${e.name}" }
                    if (e.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            marker.writeText(sha)
        }
        linkTools(context)
        return dir
    }

    /** files/bin holds symlinks to executables shipped as lib*.so (the only exec-allowed location). */
    private fun linkTools(context: Context) {
        val bin = File(context.filesDir, "bin").apply { mkdirs() }
        val nativeDir = context.applicationInfo.nativeLibraryDir
        mapOf("rg" to "librg.so", "node" to "libdshnode.so").forEach { (name, lib) ->
            val link = File(bin, name)
            val target = File(nativeDir, lib)
            if (!target.exists()) return@forEach
            runCatching { if (Os.readlink(link.path) == target.path) return@forEach }
            link.delete()
            Os.symlink(target.path, link.path)
        }
    }
}
