package top.wkbin.taixu.harness

import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceFileAccessTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `edit rejects files larger than the bounded in-memory limit`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val large = root.resolve("large.txt")
        RandomAccessFile(large, "rw").use { it.setLength(WorkspaceFileAccess.MAX_EDIT_BYTES + 1) }

        val result = WorkspaceFileAccess(root).edit("large.txt", "a", "b")

        assertTrue(result.isFailure)
        assertTrue(result.errorOrNull()?.message.orEmpty().contains("文件过大"))
    }

    @Test
    fun `write limit is measured in utf8 bytes rather than characters`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val multibyte = "界".repeat(WorkspaceFileAccess.MAX_WRITE_BYTES / 2)

        val result = WorkspaceFileAccess(root).write("too-large.txt", multibyte)

        assertTrue(result.isFailure)
        assertTrue(result.errorOrNull()?.message.orEmpty().contains("内容过长"))
    }

    @Test
    fun `large spill is read with explicit streaming page`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val spillDir = root.resolve(".taixu-outputs").also { it.mkdirs() }
        val spill = spillDir.resolve("tool-large.txt")
        spill.bufferedWriter().use { writer ->
            repeat(40_000) { writer.appendLine("line-$it-${"x".repeat(32)}") }
        }
        assertTrue(spill.length() > WorkspaceFileAccess.MAX_READ_BYTES)

        val result = WorkspaceFileAccess(root).read(".taixu-outputs/tool-large.txt", offset = 20_001, limit = 2)

        assertTrue(result.isSuccess)
        val content = result.getOrNull().orEmpty()
        assertTrue(content.contains("line-20000-"))
        assertTrue(content.contains("line-20001-"))
        assertTrue(!content.contains("line-19999-"))
    }
}
