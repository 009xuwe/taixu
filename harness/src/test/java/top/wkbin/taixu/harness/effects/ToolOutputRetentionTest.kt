package top.wkbin.taixu.harness.effects

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolOutputRetentionTest {

    @Test
    fun `command build and process logs keep the tail`() {
        assertEquals(OutputRetention.TAIL, ToolOutputRetention.forTool("base"))
        assertEquals(OutputRetention.TAIL, ToolOutputRetention.forTool("process"))
        assertEquals(OutputRetention.TAIL, ToolOutputRetention.forTool("build_script"))
        assertEquals(OutputRetention.TAIL, ToolOutputRetention.forTool("BASE"))
        assertEquals(OutputRetention.TAIL, ToolOutputRetention.forTool(" build_script "))
    }

    @Test
    fun `read and search keep the head`() {
        assertEquals(OutputRetention.HEAD, ToolOutputRetention.forTool("read"))
        assertEquals(OutputRetention.HEAD, ToolOutputRetention.forTool("history_search"))
        assertEquals(OutputRetention.HEAD, ToolOutputRetention.forTool(null))
    }

    @Test
    fun `head truncation keeps whole lines within budget`() {
        val text = "aaaa\nbbbb\ncccc\n"
        // 预算 7 落在第二行内：回退到第一个换行处，只保留 "aaaa"
        assertEquals("aaaa", keepHeadWholeLines(text, 7))
        // 单行超预算时退化为硬截断
        assertEquals("aaaa", keepHeadWholeLines("aaaaaaaaaa", 4))
        // 未超预算原样返回
        assertEquals(text, keepHeadWholeLines(text, 100))
    }

    @Test
    fun `tail truncation keeps whole lines within budget`() {
        val text = "aaaa\nbbbb\ncccc\n"
        // 预算 7 从末尾回退：从第二个换行之后开始，保留 "cccc\n"
        assertEquals("cccc\n", keepTailWholeLines(text, 7))
        // 单行超预算时退化为硬截断
        assertEquals("aaaa", keepTailWholeLines("aaaaaaaaaa", 4))
        assertEquals(text, keepTailWholeLines(text, 100))
    }
}