package io.github.damian1000.visitoranalytics.ingest

import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tails one or more log files on a polling thread, emitting complete lines. Starts at each
 * file's current end — history isn't re-ingested across restarts, so a deploy's restart window
 * drops the seconds of traffic it covers rather than double-counting days.
 *
 * A file whose size shrinks was rotated or truncated; tailing restarts from offset zero of the
 * (new) file at that path. Lines written after a rename but before the new file appears are the
 * rotation's loss — Caddy rotates by size, so that window is tiny.
 */
class CaddyLogTailer(
    private val paths: List<Path>,
    private val pollMillis: Long = 1_000,
) : AutoCloseable {
    private val positions = HashMap<Path, Long>()
    private var thread: Thread? = null

    @Volatile
    private var running = false

    fun start(onLine: (String) -> Unit) {
        check(thread == null) { "tailer already started" }
        for (path in paths) {
            positions[path] = if (Files.exists(path)) Files.size(path) else 0L
        }
        running = true
        thread =
            Thread({
                while (running) {
                    for (path in paths) pollFile(path, onLine)
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
    ) {
        if (!Files.exists(path)) {
            positions[path] = 0L
            return
        }
        val size = Files.size(path)
        var position = positions.getValue(path)
        if (size < position) position = 0L
        if (size == position) {
            positions[path] = position
            return
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
            positions[path] = position + lineStart
        }
    }

    override fun close() {
        running = false
        thread?.interrupt()
        thread?.join(5_000)
    }

    companion object {
        private const val NEWLINE = '\n'.code.toByte()
    }
}
