package top.wkbin.taixu.harness.session

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.harness.HarnessTool
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolCallMode
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage

class ApiMessageProjectorTest {

    private fun messages() = listOf(
        UserMessage(id = "u1", createdAt = 1, text = "看看这张图"),
        ToolCall(id = "c1", createdAt = 2, tool = HarnessTool.READ, args = buildJsonObject {}),
        ToolResult(
            id = "r1",
            createdAt = 3,
            toolCallId = "c1",
            success = true,
            output = "已读取图片文件 chart.png",
            imageDataUrl = "data:image/png;base64,AAAA",
        ),
    )

    @Test
    fun `vision bridge appends image message when vision enabled`() {
        val projected = ApiMessageProjector.project(messages(), ToolCallMode.NATIVE, visionEnabled = true)
        val imageMessage = projected.last()
        assertEquals("user", imageMessage.role)
        assertEquals(listOf("data:image/png;base64,AAAA"), imageMessage.imageUrls)
        assertTrue(projected.any { it.role == "tool" })
    }

    @Test
    fun `vision bridge is skipped when vision disabled`() {
        val projected = ApiMessageProjector.project(messages(), ToolCallMode.NATIVE, visionEnabled = false)
        assertTrue(projected.none { it.imageUrls.isNotEmpty() })
    }
}