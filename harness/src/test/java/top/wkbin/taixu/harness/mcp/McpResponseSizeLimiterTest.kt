package top.wkbin.taixu.harness.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class McpResponseSizeLimiterTest {

    @Test
    fun smallResponseReturnsInlinePayload() {
        val text = """{"jsonrpc":"2.0","id":"1","result":{"status":"ok"}}"""
        val input = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

        val result = McpResponseSizeLimiter.readBounded(
            input = input,
            maxInlineBytes = 1024,
            maxSpillBytes = 4096,
        )

        assertTrue(result is McpResponseSizeLimiter.Payload.Inline)
        val inline = result as McpResponseSizeLimiter.Payload.Inline
        assertEquals(text, inline.text)
        assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), inline.bytes)
    }

    @Test
    fun mediumResponseSpillsToDiskWithPreview() {
        val tempDir = File.createTempFile("mcp_test_dir_", "").apply {
            delete()
            mkdirs()
        }
        try {
            val largeText = "A".repeat(2000)
            val input = ByteArrayInputStream(largeText.toByteArray(Charsets.UTF_8))

            val result = McpResponseSizeLimiter.readBounded(
                input = input,
                maxInlineBytes = 500,
                maxSpillBytes = 3000,
                spillDirectory = tempDir,
            )

            assertTrue("Expected Spilled payload, was $result", result is McpResponseSizeLimiter.Payload.Spilled)
            val spilled = result as McpResponseSizeLimiter.Payload.Spilled
            assertEquals(2000L, spilled.totalBytes)
            assertTrue(spilled.file.exists())
            assertEquals(2000L, spilled.file.length())
            assertTrue(spilled.previewText.startsWith("AAAAA"))
            assertTrue(spilled.formatForToolResult().contains("已自动流式转存至文件"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun giantResponseTriggersCircuitBreaker() {
        val giantData = ByteArray(10_000) { 1 }
        val input = ByteArrayInputStream(giantData)

        val result = McpResponseSizeLimiter.readBounded(
            input = input,
            maxInlineBytes = 500,
            maxSpillBytes = 2000,
        )

        assertTrue("Expected CircuitBroken payload, was $result", result is McpResponseSizeLimiter.Payload.CircuitBroken)
        val breaker = result as McpResponseSizeLimiter.Payload.CircuitBroken
        assertTrue(breaker.formatErrorMessage().contains("熔断拦截"))
    }

    @Test
    fun unusableSpillDirectoryDegradesToCircuitBreakerInsteadOfThrowing() {
        // 传入一个普通文件充当"目录"：mkdirs 必然失败，验证降级为熔断而非抛 IOException
        val notADirectory = File.createTempFile("mcp_test_not_a_dir_", ".txt")
        try {
            val input = ByteArrayInputStream("A".repeat(1000).toByteArray(Charsets.UTF_8))
            val result = McpResponseSizeLimiter.readBounded(
                input = input,
                maxInlineBytes = 200,
                maxSpillBytes = 2000,
                spillDirectory = notADirectory,
            )

            assertTrue("Expected CircuitBroken payload, was $result", result is McpResponseSizeLimiter.Payload.CircuitBroken)
            val breaker = result as McpResponseSizeLimiter.Payload.CircuitBroken
            assertTrue(breaker.formatErrorMessage().contains("熔断拦截"))
        } finally {
            notADirectory.delete()
        }
    }

    @Test
    fun cleanupSpillsDoesNotDeleteForeignFiles() {
        val tempDir = File.createTempFile("mcp_test_dir_foreign_", "").apply {
            delete()
            mkdirs()
        }
        try {
            val foreignFile = File(tempDir, "user_precious_data.txt").apply {
                writeText("important")
                setLastModified(System.currentTimeMillis() - 48 * 60 * 60 * 1000L)
            }
            val staleSpillFile = File(tempDir, "mcp_spill_old.txt").apply {
                writeText("old spill")
                setLastModified(System.currentTimeMillis() - 48 * 60 * 60 * 1000L)
            }

            val input = ByteArrayInputStream("A".repeat(1000).toByteArray(Charsets.UTF_8))
            val result = McpResponseSizeLimiter.readBounded(
                input = input,
                maxInlineBytes = 200,
                maxSpillBytes = 2000,
                spillDirectory = tempDir,
            )

            assertTrue(result is McpResponseSizeLimiter.Payload.Spilled)
            assertTrue("Foreign user file must NOT be deleted", foreignFile.exists())
            assertTrue("Stale spill file starting with prefix should be cleaned up", !staleSpillFile.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
