package ai.abbas.app.inference

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Random
import kotlin.concurrent.thread

/**
 * Verifies that [ModelDownloader] reports progress *while* streaming, not only
 * at 0% and 100%. Regression guard for the "bar stays at 0% until done" bug
 * (a bare `input.copyTo(output)` emitted nothing in between).
 *
 * Fully JVM: the downloader touches no Android APIs, so we can exercise it
 * against a raw socket that serves a fixed body with a real Content-Length.
 * (`com.sun.net.httpserver` is not on the Android compile classpath.)
 */
class ModelDownloaderProgressTest {

    private val payload = ByteArray(6 * 1024 * 1024).also { Random(42).nextBytes(it) }

    private lateinit var server: ServerSocket
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "gguf-test-server") {
            runCatching {
                server.accept().use { sock ->
                    // Drain request headers (GET, no body).
                    val lines = sock.getInputStream().bufferedReader(Charsets.US_ASCII)
                    while (true) {
                        val line = lines.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val head = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Content-Length: ${payload.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    sock.getOutputStream().apply {
                        write(head.toByteArray(Charsets.US_ASCII))
                        write(payload)
                        flush()
                    }
                }
            }
        }
        baseUrl = "http://127.0.0.1:${server.localPort}/"
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    @Test
    fun progressIsReportedIncrementallyAndFileLandsIntact() = runBlocking<Unit> {
        val dir = File(System.getProperty("java.io.tmpdir"), "dl-test-${System.nanoTime()}")

        val progress = ModelDownloader()
            .downloadModel(dir, baseUrl, "model.gguf")
            .toList()
            .map { it.progress }

        // File written byte-for-byte.
        assertEquals(payload.size.toLong(), File(dir, "model.gguf").length())

        // Started at 0, ended at 1.
        assertEquals(0f, progress.first(), 0f)
        assertEquals(1f, progress.last(), 0f)

        // The whole point: several distinct in-flight values strictly between 0 and 1.
        val intermediates = progress.filter { it > 0f && it < 1f }
        assertTrue(
            "expected >=2 intermediate progress samples, got $intermediates (all=$progress)",
            intermediates.size >= 2
        )
        assertTrue(
            "expected distinct intermediate values, got $intermediates",
            intermediates.distinct().size >= 2
        )

        // Monotonic non-decreasing.
        assertTrue(
            "progress must never go backwards: $progress",
            progress.zipWithNext().all { (a, b) -> b >= a }
        )

        dir.deleteRecursively()
    }
}
