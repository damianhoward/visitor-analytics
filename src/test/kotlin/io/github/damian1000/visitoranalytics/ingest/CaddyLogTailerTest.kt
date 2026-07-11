package io.github.damian1000.visitoranalytics.ingest

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.empty
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.util.Collections

class CaddyLogTailerTest {
    @TempDir
    lateinit var dir: Path

    private val lines = Collections.synchronizedList(mutableListOf<String>())

    private fun tailer(vararg paths: Path) = CaddyLogTailer(paths.toList(), pollMillis = 5)

    private fun append(
        path: Path,
        vararg newLines: String,
    ) {
        Files.write(path, newLines.joinToString("") { it + "\n" }.toByteArray(), CREATE, APPEND)
    }

    private fun awaitLines(expected: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (lines.size < expected && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertThat("expected $expected line(s), got $lines within 5s", lines.size >= expected)
    }

    @Test
    fun `emits lines appended after start`() {
        val log = dir.resolve("access.log")
        append(log, "first")
        tailer(log).use {
            it.start(lines::add)
            append(log, "second", "third")
            awaitLines(2)
        }
        // "first" predates start(): history is not re-ingested.
        assertThat(lines, contains("second", "third"))
    }

    @Test
    fun `holds a partial line until its newline arrives`() {
        val log = dir.resolve("access.log")
        tailer(log).use {
            it.start(lines::add)
            Files.write(log, "no newline yet".toByteArray(), CREATE, APPEND)
            Thread.sleep(50)
            assertThat(lines, empty())
            Files.write(log, " - done\n".toByteArray(), APPEND)
            awaitLines(1)
        }
        assertThat(lines, contains("no newline yet - done"))
    }

    @Test
    fun `restarts from zero when the file shrinks`() {
        val log = dir.resolve("access.log")
        tailer(log).use {
            it.start(lines::add)
            append(log, "before rotation")
            awaitLines(1)
            Files.write(log, ByteArray(0)) // truncation, as logrotate's copytruncate does
            append(log, "after rotation")
            awaitLines(2)
        }
        assertThat(lines, contains("before rotation", "after rotation"))
    }

    @Test
    fun `picks up a file created after start`() {
        val log = dir.resolve("late.log")
        tailer(log).use {
            it.start(lines::add)
            Thread.sleep(20)
            append(log, "born late")
            awaitLines(1)
        }
        assertThat(lines, contains("born late"))
    }

    @Test
    fun `tails multiple files`() {
        val a = dir.resolve("a.log")
        val b = dir.resolve("b.log")
        tailer(a, b).use {
            it.start(lines::add)
            append(a, "from a")
            append(b, "from b")
            awaitLines(2)
        }
        assertThat(lines.sorted(), contains("from a", "from b"))
    }
}
