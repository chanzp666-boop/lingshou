package com.byd.ls.cluster.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 1.4.176：歌词缓存 100MB 上限的淘汰契约。 */
class LyricCacheEvictionTest {

    @Test
    fun evictsOldestUntilUnderLimitAndKeepsFresh() {
        val dir = Files.createTempDirectory("lyric-cache").toFile()
        try {
            write(dir, "old.lrc", 60, 10_000L)
            write(dir, "middle.lrc.miss", 40, 5_000L)
            write(dir, "fresh.lrc", 60, 1_000L)

            val removed = LyricLoader.trimCache(dir, 120L)

            assertTrue("超限必须至少删一个文件", removed >= 1)
            assertFalse("最旧的缓存应被淘汰", File(dir, "old.lrc").exists())
            assertTrue("新缓存不能被误删", File(dir, "fresh.lrc").exists())
            assertTrue("缓存总量必须回落到上限内", cacheBytes(dir) <= 120L)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun neverDeletesNonCacheFiles() {
        val dir = Files.createTempDirectory("lyric-cache-keep").toFile()
        try {
            write(dir, "old.lrc", 80, 10_000L)
            write(dir, "fresh.lrc", 80, 1_000L)
            File(dir, "keep.txt").writeText("not lyric cache")

            LyricLoader.trimCache(dir, 100L)

            assertTrue("无关文件必须保留", File(dir, "keep.txt").exists())
            assertTrue("缓存总量必须回落到上限内", cacheBytes(dir) <= 100L)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun purgeLegacyMiss_keepsLyricsAndOtherFiles() {
        val dir = Files.createTempDirectory("lyric-cache-miss").toFile()
        try {
            write(dir, "keep.lrc", 20, 1_000L)
            write(dir, "bad.lrc.miss", 10, 1_000L)
            File(dir, "keep.txt").writeText("not lyric cache")

            val removed = LyricLoader.purgeMissFiles(dir)

            assertEquals(1, removed)
            assertTrue("成功歌词必须保留", File(dir, "keep.lrc").exists())
            assertFalse("B7 遗留负缓存必须清掉", File(dir, "bad.lrc.miss").exists())
            assertTrue("无关文件必须保留", File(dir, "keep.txt").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun searchForm_mapsTraditionalTitle() {
        assertEquals("这么近那么远", LyricLoader.searchForm("这麽近那麽远"))
    }

    private fun write(dir: File, name: String, bytes: Int, ageMs: Long) {
        val f = File(dir, name)
        f.writeBytes(ByteArray(bytes))
        assertTrue("测试环境必须能设置 mtime", f.setLastModified(System.currentTimeMillis() - ageMs))
    }

    private fun cacheBytes(dir: File): Long = dir.listFiles()
        ?.filter { it.isFile && (it.name.endsWith(".lrc") || it.name.endsWith(".lrc.miss")) }
        ?.sumOf { it.length() } ?: 0L
}
