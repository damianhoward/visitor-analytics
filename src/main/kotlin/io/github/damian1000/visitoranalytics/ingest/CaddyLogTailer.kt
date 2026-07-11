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
 */
class CaddyLogTailer(
    private val paths: List<Path>,
    private val pollMillis: Long = 1_000,
    private val statePath: Path? = null,
) : AutoCloseable {
    private val positions = HashMap<Path, Long>()
    private var thread: Thread? = null

    @Volatile
    private var running = false

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
                    var advanced = false
                    for (path in paths) advanced = pollFile(path, onLine) || advanced
                    if (advanced) saveState()
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
            val bytes = ByteArray((size - position).toInt())
            file.readFully(bytes)
            var lineStart = 0
            for (i in bytes.indices) {
                if (bytes[i] == NEWLINE) {
                    val line = String(bytes, lineStart, i - lineStart, StandardCharsets.UTF_8).trimEnd('\r')
                    if (line.isNotBlank()) onLine(line)
                    lineStart = i + 1
                }
            }
            // A trailing fragment without its newline is a line still being written; leave it
            // unconsumed and pick it up whole on a later poll.
            val consumed = position + lineStart
            val moved = consumed != positions.getValue(path)
            positions[path] = consumed
            return moved
        }
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
    }
}
