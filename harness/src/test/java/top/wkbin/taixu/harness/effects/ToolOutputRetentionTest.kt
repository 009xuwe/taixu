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
}