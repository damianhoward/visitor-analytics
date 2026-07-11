package io.github.damian1000.visitoranalytics.store

import io.github.damian1000.visitoranalytics.FakeVisitStore
import io.github.damian1000.visitoranalytics.sampleVisit
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException

class BufferingVisitStoreTest {
    @TempDir
    lateinit var dir: Path

    private val delegate = FakeVisitStore()

    private fun store() = BufferingVisitStore(delegate, dir.resolve("buffer.jsonl"))

    @Test
    fun `records straight through while the database is up`() {
        val store = store()
        store.record(sampleVisit(path = "/a"))
        assertThat(delegate.recorded.map { it.path }, contains("/a"))
        assertThat(Files.exists(dir.resolve("buffer.jsonl")), equalTo(false))
    }

    @Test
    fun `buffers while the database is down and never throws`() {
        val store = store()
        delegate.failWith = SQLException("IO Error: The Network Adapter could not establish the connection")

        store.record(sampleVisit(path = "/a"))
        store.record(sampleVisit(path = "/b", engaged = true))

        assertThat(delegate.recorded, empty())
        assertThat(Files.readAllLines(dir.resolve("buffer.jsonl")).size, equalTo(2))
    }

    @Test
    fun `flushes the backlog in arrival order once the database is back`() {
        val store = store()
        delegate.failWith = SQLException("down")
        store.record(sampleVisit(path = "/a"))
        store.record(sampleVisit(path = "/b"))

        delegate.failWith = null
        store.record(sampleVisit(path = "/c"))

        assertThat(delegate.recorded.map { it.path }, equalTo(listOf("/a", "/b", "/c")))
        assertThat(Files.exists(dir.resolve("buffer.jsonl")), equalTo(false))
    }

    @Test
    fun `a buffered visit survives the roundtrip whole, nulls included`() {
        val store = store()
        val visit = sampleVisit(path = "/x", engaged = true, country = null, city = null, asn = null, org = null, referrer = null)
        delegate.failWith = SQLException("down")
        store.record(visit)

        delegate.failWith = null
        store.record(sampleVisit(path = "/y"))

        assertThat(delegate.recorded.first(), equalTo(visit))
    }

    @Test
    fun `a mid-flush failure keeps the unflushed remainder buffered`() {
        val store = store()
        delegate.failWith = SQLException("down")
        store.record(sampleVisit(path = "/a"))
        store.record(sampleVisit(path = "/b"))

        // The delegate accepts one record then fails again.
        delegate.heal()
        delegate.failAfter(1)
        store.record(sampleVisit(path = "/c"))

        assertThat(delegate.recorded.map { it.path }, contains("/a"))
        assertThat(Files.readAllLines(dir.resolve("buffer.jsonl")).size, equalTo(2))

        delegate.heal()
        store.record(sampleVisit(path = "/d"))
        assertThat(delegate.recorded.map { it.path }, equalTo(listOf("/a", "/b", "/c", "/d")))
    }

    @Test
    fun `reads pass through to the delegate`() {
        val store = store()
        store.record(sampleVisit(path = "/a"))
        assertThat(store.recent(10).map { it.path }, contains("/a"))
    }
}
