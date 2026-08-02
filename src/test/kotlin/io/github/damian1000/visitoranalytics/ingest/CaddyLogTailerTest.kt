package io.github.damian1000.visitoranalytics.ingest

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.`is`
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

    private fun boundedTailer(
        path: Path,
        maxChunkBytes: Int,
    ) = CaddyLogTailer(listOf(path), pollMillis = 5, maxChunkBytes = maxChunkBytes)

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
    fun `resumes from the persisted offset across restarts`() {
        val log = dir.resolve("access.log")
        val state = dir.resolve("positions.properties")
        CaddyLogTailer(listOf(log), pollMillis = 5, statePath = state).use {
            it.start(lines::add)
            append(log, "seen before restart")
            awaitLines(1)
        }
        // Lines written while no tailer is running — the deploy-restart window.
        append(log, "written while down", "also while down")
        CaddyLogTailer(listOf(log), pollMillis = 5, statePath = state).use {
            it.start(lines::add)
            append(log, "after restart")
            awaitLines(4)
        }
        assertThat(lines, contains("seen before restart", "written while down", "also while down", "after restart"))
    }

    @Test
    fun `without persisted state a pre-existing file still starts at its end`() {
        val log = dir.resolve("access.log")
        val state = dir.resolve("positions.properties")
        append(log, "history")
        CaddyLogTailer(listOf(log), pollMillis = 5, statePath = state).use {
            it.start(lines::add)
            append(log, "new")
            awaitLines(1)
        }
        assertThat(lines, contains("new"))
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

    // Guards the mechanism the read ceiling needed: chunk boundaries fall mid-line all through this
    // backlog, so a line split across two reads, or a chunk resumed at the wrong offset, shows up
    // as a missing, duplicated or truncated entry. (The ceiling's real purpose is bounded memory,
    // which is a property of the allocation rather than of observable behaviour.)
    @Test
    fun `a backlog spanning many chunks arrives complete, in order, with no line split`() {
        val log = dir.resolve("access.log")
        val entries = (1..40).map { "line-%026d".format(it) }
        Files.write(log, "seed\n".toByteArray(), CREATE)
        boundedTailer(log, maxChunkBytes = 64).use {
            it.start(lines::add)
            append(log, *entries.toTypedArray())
            awaitLines(entries.size)
        }
        assertThat(lines, contains(*entries.toTypedArray()))
    }

    @Test
    fun `a line with no terminator inside a full chunk is skipped rather than stalling the path`() {
        val log = dir.resolve("access.log")
        Files.write(log, "seed\n".toByteArray(), CREATE)
        boundedTailer(log, maxChunkBytes = 32).use {
            it.start(lines::add)
            // 96 bytes with no newline: three full chunks the tailer can never terminate. Without
            // the skip it would re-read the first chunk forever and never reach "after".
            Files.write(log, ("x".repeat(96) + "\n").toByteArray(), APPEND)
            append(log, "after")
            awaitLines(1)
        }
        assertThat(lines, contains("after"))
    }

    @Test
    fun `a handler that throws leaves the line unconsumed and the tailer alive`() {
        val log = dir.resolve("access.log")
        val attempts = Collections.synchronizedList(mutableListOf<String>())
        var failuresLeft = 3

        tailer(log).use {
            it.start { line ->
                attempts.add(line)
                // Fails the first three times it sees the poison line, then recovers — a store
                // outage, not a permanently bad record.
                if (line == "poison" && failuresLeft > 0) {
                    failuresLeft--
                    throw IllegalStateException("store unavailable")
                }
                lines.add(line)
            }
            append(log, "before", "poison", "after")
            awaitLines(3)
        }

        // Retried rather than skipped, and the lines behind it were not lost or reordered.
        assertThat(lines, contains("before", "poison", "after"))
        assertThat(attempts.count { it == "poison" } >= 4, `is`(true))
        // "before" is committed with the chunk that preceded the failure, so it is not replayed.
        assertThat(attempts.count { it == "before" }, `is`(1))
    }
}
