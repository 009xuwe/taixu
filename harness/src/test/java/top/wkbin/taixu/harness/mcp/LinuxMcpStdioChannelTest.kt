package top.wkbin.taixu.harness.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.runtime.shell.LinuxSession
import top.wkbin.taixu.runtime.shell.TerminalOutput
import top.wkbin.taixu.runtime.shell.TerminalStream

class LinuxMcpStdioChannelTest {

    /** output 永不完成的会话：构造"泵挂起在 lines.send 上"的泄漏前提（消费方已离场）。 */
    private class EndlessSession : LinuxSession {
        override val isAlive = true
        override val output = flow {
            var i = 0
            while (true) {
                emit(TerminalOutput(TerminalStream.STDOUT, "{\"resp\":$i}\n"))
                delay(10)
            }
        }
        override suspend fun write(data: ByteArray) {}
        override suspend fun resize(columns: Int, rows: Int) {}
        override suspend fun interrupt() {}
        override suspend fun close() {}
    }

    /** 输出一行后正常完成的会话：锁定泵的正常收尾路径。 */
    private class CompletingSession : LinuxSession {
        override val isAlive = true
        override val output = flow {
            emit(TerminalOutput(TerminalStream.STDOUT, "{\"resp\":1}\n"))
        }
        override suspend fun write(data: ByteArray) {}
        override suspend fun resize(columns: Int, rows: Int) {}
        override suspend fun interrupt() {}
        override suspend fun close() {}
    }

    @Test
    fun `close unwinds a pump stalled on a full send buffer`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // 容量 1：泵发出第 2 行即挂起在 send 上，session.close() 无法 resume 它——
        // close() 若不取消 scope，泵与 Channel 永久泄漏（每次销毁忙连接漏一个）
        val channel = LinuxMcpStdioChannel("srv", EndlessSession(), scope = scope, bufferCapacity = 1)
        delay(200)
        channel.close()
        val drained = withTimeoutOrNull(2_000L) {
            while (!channel.incoming.receiveCatching().isClosed) {
                // 逐条排空缓冲，直到拿到关闭标记
            }
            true
        }
        assertTrue("close() must terminate the pump and close the lines channel", drained == true)
        scope.cancel()
    }

    @Test
    fun `pump closes the lines channel when the session output completes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val channel = LinuxMcpStdioChannel("srv", CompletingSession(), scope = scope, bufferCapacity = 8)
        val drained = withTimeoutOrNull(2_000L) {
            while (!channel.incoming.receiveCatching().isClosed) {
                // 正常完成路径同样要拿到关闭标记
            }
            true
        }
        assertTrue("output completion must close the lines channel", drained == true)
        scope.cancel()
    }
}
