package io.github.damian1000.visitoranalytics.ingest

import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * Tails one or more log files on a polling thread, emitting complete lines.
 *
 * With a [statePath], consumed offsets are persisted after every advance and a restart resumes
 * exactly where the previous process stopped — a deploy's restart window loses nothing. A file
 * with no persisted offset (first ever run) starts at its current end, so history is not
 * re-ingested. A crash between emitting and persisting replays at most one poll's lines; the
 * store's unique visit key absorbs the replay, so delivery is at-least-once and storage is
 * still exact.
 *
 * A file whose size shrinks was rotated or truncated; tailing restarts from offset zero of the
 * (new) file at that path. Lines written after a rename but before the new file appears are the
 * rotation's loss — Caddy rotates by size, so that window is tiny.
 *
 * A poll reads at most [maxChunkBytes], not the whole backlog. This process runs in a 96 MB heap
 * and the file is whatever size the log grew to while it was stopped, so sizing one array to the
 * gap made memory a function of downtime. Catch-up therefore takes several polls, which is the
 * intended trade: bounded memory and a bounded write rate at the store, rather than one burst.
 *
 * The poll thread is the whole ingest path, so nothing it does is allowed to end it. A failure
 * reading a file, or a handler that throws, is reported and retried on the next poll; the thread
 * outlives it. A dead tailer is invisible from outside — the dashboard keeps serving, so the
 * service looks healthy while it silently stops recording.
 */
class CaddyLogTailer(
    private val paths: List<Path>,
    private val pollMillis: Long = 1_000,
    private val statePath: Path? = null,
    private val maxChunkBytes: Int = DEFAULT_MAX_CHUNK_BYTES,
) : AutoCloseable {
    private val positions = HashMap<Path, Long>()
    private val lastFailedOffsets = HashMap<Path, Long>()
    private var thread: Thread? = null

    @Volatile
    private var running = false

    // Written only by the poll thread, read by the readiness probe on an HTTP thread. `positions`
    // itself stays single-writer and unsynchronised; a snapshot is republished after each cycle
    // so the probe never reads a map that is being mutated.
    @Volatile
    private var lastPollMillis: Long = 0

    @Volatile
    private var lastFailure: String? = null

    @Volatile
    private var offsetSnapshot: Map<String, Long> = emptyMap()

    /**
     * What the ingest path is doing, for [io.github.damian1000.visitoranalytics.health.Readiness].
     *
     * [pollAgeMillis] measures the loop turning over, not lines arriving. That distinction is the
     * whole point: a site with no visitors produces no lines for hours and is perfectly healthy,
     * so a probe keyed on ingest volume would page on a quiet Sunday and stay silent on a dead
     * tailer. The loop polls on a fixed interval whatever the traffic, so its age answers "is
     * this still running" without asking "is anyone visiting".
     */
    val threadAlive: Boolean
        get() = thread?.isAlive == true

    /** Milliseconds since the last completed poll cycle, or null before the first one. */
    fun pollAgeMillis(nowMillis: Long): Long? = if (lastPollMillis == 0L) null else nowMillis - lastPollMillis

    /** The most recent poll failure, retained so a probe can report it; null once a poll succeeds. */
    fun lastFailure(): String? = lastFailure

    /** Consumed offset per tailed file, as of the last completed poll. */
    fun offsets(): Map<String, Long> = offsetSnapshot

    fun start(onLine: (String) -> Unit) {
        check(thread == null) { "tailer already started" }
        val persisted = loadState()
        for (path in paths) {
            positions[path] = persisted[path] ?: if (Files.exists(path)) Files.size(path) else 0L
        }
        running = true
        thread =
            Thread({
                while (running) {
                    // Nothing here may escape: this thread is the entire ingest path, and losing
                    // it stops recording silently while the dashboard carries on serving.
                    try {
                        var advanced = false
                        for (path in paths) advanced = pollFile(path, onLine) || advanced
                        if (advanced) saveState()
                        lastFailure = null
                    } catch (e: Exception) {
                        // The offset for a path that failed was not advanced, so the next poll
                        // re-reads from the same place rather than stepping over the gap.
                        lastFailure = e.message ?: e::class.simpleName
                        report("poll failed", e)
                    }
                    // After the catch, so a failed cycle still counts as the loop being alive: a
                    // tailer failing every poll is a different fault from a tailer that stopped,
                    // and lastFailure is what distinguishes them.
                    offsetSnapshot = positions.mapKeys { it.key.toString() }
                    lastPollMillis = System.currentTimeMillis()
                    try {
                        Thread.sleep(pollMillis)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }, "caddy-log-tailer").apply {
                isDaemon = true
                start()
            }
    }

    private fun pollFile(
        path: Path,
        onLine: (String) -> Unit,
    ): Boolean {
        if (!Files.exists(path)) {
            positions[path] = 0L
            return false
        }
        val size = Files.size(path)
        var position = positions.getValue(path)
        if (size < position) position = 0L
        if (size == position) {
            positions[path] = position
            return false
        }
        RandomAccessFile(path.toFile(), "r").use { file ->
            file.seek(position)
            // Bounded by the chunk, never by the backlog: (size - position) is however far behind
            // this process fell, and .toInt() on it silently wrapped past 2 GB besides.
            val chunk = minOf(size - position, maxChunkBytes.toLong()).toInt()
            val bytes = ByteArray(chunk)
            file.readFully(bytes)
            var lineStart = 0
            for (i in bytes.indices) {
                if (bytes[i] != NEWLINE) continue
                val line = String(bytes, lineStart, i - lineStart, StandardCharsets.UTF_8).trimEnd('\r')
                if (line.isNotBlank()) {
                    try {
                        onLine(line)
                    } catch (e: Exception) {
                        // Stop at the line that failed rather than past it: leaving the offset on
                        // it means the next poll retries, so a handler failure delays a line
                        // instead of dropping it. Progress made earlier in the chunk still counts.
                        reportLine(path, position + lineStart, e)
                        return commit(path, position + lineStart)
                    }
                }
                lineStart = i + 1
            }
            // No newline anywhere in a full chunk: the line is longer than the chunk, so waiting
            // for its terminator would stall this path forever. Step over the chunk and resync at
            // the next newline, loudly — a Caddy access-log line is ~1 KB, so this is a corrupt file.
            if (lineStart == 0 && chunk == maxChunkBytes) {
                report("no line terminator in $maxChunkBytes bytes of $path at $position; skipping", null)
                return commit(path, position + chunk)
            }
            // A trailing fragment without its newline is a line still being written; leave it
            // unconsumed and pick it up whole on a later poll.
            return commit(path, position + lineStart)
        }
    }

    private fun commit(
        path: Path,
        consumed: Long,
    ): Boolean {
        val moved = consumed != positions.getValue(path)
        positions[path] = consumed
        return moved
    }

    // Reported once per distinct offset: a line that keeps failing is retried every poll, and
    // logging each attempt would bury the first occurrence under a line a second.
    private fun reportLine(
        path: Path,
        offset: Long,
        cause: Exception,
    ) {
        if (lastFailedOffsets[path] == offset) return
        lastFailedOffsets[path] = offset
        report("handler failed on $path at $offset; retrying", cause)
    }

    private fun report(
        message: String,
        cause: Exception?,
    ) {
        println("visitor-analytics: tailer $message${cause?.let { " (${it.message})" } ?: ""}")
    }

    private fun loadState(): Map<Path, Long> {
        val state = statePath ?: return emptyMap()
        if (!Files.exists(state)) return emptyMap()
        val properties = Properties()
        Files.newBufferedReader(state, StandardCharsets.UTF_8).use(properties::load)
        return properties.entries
            .mapNotNull { (key, value) ->
                value.toString().toLongOrNull()?.let { Path.of(key.toString()) to it }
            }.toMap()
    }

    private fun saveState() {
        val state = statePath ?: return
        val properties = Properties()
        for ((path, offset) in positions) properties.setProperty(path.toString(), offset.toString())
        state.parent?.let(Files::createDirectories)
        val temp = state.resolveSibling(state.fileName.toString() + ".tmp")
        Files.newBufferedWriter(temp, StandardCharsets.UTF_8).use { properties.store(it, null) }
        try {
            Files.move(temp, state, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, state, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun close() {
        running = false
        thread?.interrupt()
        thread?.join(5_000)
        if (positions.isNotEmpty()) saveState()
    }

    companion object {
        private const val NEWLINE = '\n'.code.toByte()

        /** Per-poll read ceiling: ~1000 access-log lines, and 1 MB against a 96 MB heap. */
        const val DEFAULT_MAX_CHUNK_BYTES = 1024 * 1024
    }
}
