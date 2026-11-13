package io.github.timhub135.dsh.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ShellFilesTest {
    private val root: File = Files.createTempDirectory("shellfiles").toFile().canonicalFile
    private val fs = ShellFiles(allowed = { it == root.path || it.startsWith(root.path + "/") })
    private fun f(rel: String) = File(root, rel)
    private fun write(rel: String, text: String) = f(rel).apply { parentFile.mkdirs(); writeText(text) }

    @Test fun statAndList() {
        write("pkg/files/a.txt", "hello")
        write("pkg/files/b.log", "12345678")
        f("pkg/cache").mkdirs()
        assertNull(fs.stat(f("missing").path))
        val file = ShellFiles.parseRow(fs.stat(f("pkg/files/a.txt").path)!!)!!
        assertFalse(file.dir); assertEquals(5, file.size)
        val dir = ShellFiles.parseRow(fs.stat(f("pkg").path)!!)!!
        assertTrue(dir.dir); assertEquals(13, dir.size); assertEquals(2, dir.count)
        val listing = fs.list(f("pkg").path, recursive = false, limit = 10).lines()
        assertEquals(listOf("cache", "files"), listing.mapNotNull { ShellFiles.parseRow(it) }.map { File(it.path).name })
        assertEquals("#total 2", listing.last())
        val walk = fs.list(f("pkg").path, recursive = true, limit = 2)
        assertTrue(walk, walk.endsWith("#more"))
    }

    @Test fun refusesPathsOutsideTheAllowedScope() {
        for (p in listOf("/etc/hosts", root.path + "/../escape")) {
            try { fs.stat(p); fail("allowed $p") } catch (_: IllegalArgumentException) {}
        }
        assertTrue(ShellFiles.sharedStorage("/storage/emulated/0/Android/data/x"))
        assertFalse(ShellFiles.sharedStorage("/data/data/com.example"))
    }

    @Test fun moveCopyAndDeleteFollowFileMoveSemantics() {
        write("src/a.txt", "A")
        f("dst").mkdirs()
        assertTrue(fs.move(f("src/a.txt").path, f("dst").path, copy = true).startsWith("copied"))
        assertTrue(f("src/a.txt").exists() && f("dst/a.txt").exists())
        assertTrue(fs.move(f("src/a.txt").path, f("dst").path, copy = false).startsWith("skip"))
        assertTrue(fs.move(f("src/a.txt").path, f("new/").path + "/", copy = false).startsWith("moved"))
        assertTrue(f("new/a.txt").exists() && !f("src/a.txt").exists())
        assertTrue(fs.move(f("nope").path, f("dst").path, copy = false).startsWith("failed"))
        assertTrue(fs.move(f("dst").path, f("dst/inner/").path + "/", copy = false).startsWith("failed"))
        assertTrue(fs.delete(f("dst").path).startsWith("deleted"))
        assertFalse(f("dst").exists())
        assertTrue(fs.delete(f("dst").path).startsWith("not found"))
    }

    @Test fun writeTargetsGetTheirFolders() {
        val t = fs.prepareWrite(f("deep/er/file.txt").path)
        assertTrue(t.parentFile!!.isDirectory)
    }

    @Test fun searchByNameAndContent() {
        write("p/a.json", "{\"Token\": 1}")
        write("p/sub/b.json", "nothing")
        write("p/sub/c.txt", "token here")
        f("p/bin.dat").writeBytes(byteArrayOf(0, 1, 2, 't'.code.toByte(), 'o'.code.toByte(), 'k'.code.toByte()))
        fun names(out: String) = out.lines().mapNotNull { ShellFiles.parseRow(it) }.map { File(it.path).name }.sorted()
        assertEquals(listOf("a.json", "b.json"), names(fs.search(f("p").path, "*.json", null, false, 100, "name")))
        assertEquals(listOf("a.json", "c.txt"), names(fs.search(f("p").path, null, "TOKEN", false, 100, null)))
        assertEquals(listOf("c.txt"), names(fs.search(f("p").path, null, "^tok.n", true, 100, null)))
        assertEquals(listOf("b.json"), names(fs.search(f("p").path, "sub/*.json", null, false, 100, null)))
        val limited = fs.search(f("p").path, null, null, false, 1, "largest")
        assertEquals(1, limited.lines().count { ShellFiles.parseRow(it) != null })
        assertTrue(limited, limited.contains("#total 4"))
    }

    @Test fun globs() {
        assertTrue(ShellFiles.globRegex("*.PDF").matches("a.pdf"))
        assertFalse(ShellFiles.globRegex("*.pdf").matches("dir/a.pdf"))
        assertTrue(ShellFiles.globRegex("**/*.pdf").matches("dir/x/a.pdf"))
        assertTrue(ShellFiles.globRegex("**/*.pdf").matches("a.pdf"))
        assertTrue(ShellFiles.globRegex("*.{jpg,png}").matches("x.png"))
        assertTrue(ShellFiles.globRegex("file?.[0-9]").matches("file1.7"))
        assertTrue(ShellFiles.globRegex("a(b).txt").matches("a(b).txt"))
    }
}
