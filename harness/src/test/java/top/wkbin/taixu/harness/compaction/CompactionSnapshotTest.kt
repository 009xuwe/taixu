package top.wkbin.taixu.harness.compaction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wkbin.taixu.core.database.AppDatabase
import top.wkbin.taixu.core.database.HarnessEntryEntity
import top.wkbin.taixu.core.database.RoomHarnessRuntimeRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.HarnessTool
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage

/** latestSnapshot 快照 API：UI 折叠透明度横幅的数据源契约（真实 Room）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompactionSnapshotTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: RoomHarnessRuntimeRepository
    private lateinit var compaction: CompactionManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        val store = top.wkbin.taixu.harness.session.SessionTreeStore(
            repository,
            Json,
            top.wkbin.taixu.core.common.logging.AppLogger(context, top.wkbin.taixu.core.common.logging.SensitiveDataRedactor { it }),
        )
        compaction = CompactionManager(repository, Json, store)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedUserMessages(sessionId: String, count: Int) {
        repository.ensureLane(sessionId, "main")
        repeat(count) { index ->
            repository.appendToLane(
                sessionId,
                "main",
                HarnessEntryEntity(
                    id = "$sessionId-m$index",
                    sessionId = sessionId,
                    parentId = repository.findLane(sessionId, "main")!!.leafId,
                    createdAt = index.toLong(),
                    entryType = "message",
                    customType = "user",
                    payloadJson = Json.encodeToString(
                        HarnessMessage.serializer(),
                        UserMessage(id = "$sessionId-m$index", createdAt = index.toLong(), text = "消息 $index"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `no compaction yields null snapshot`() = runBlocking {
        seedUserMessages("s-fresh", 3)
        assertNull(compaction.latestSnapshot("s-fresh"))
    }

    @Test
    fun `provider projection does not apply the live UI entry limit`() = runBlocking {
        seedUserMessages("s-long", 625)

        val projected = compaction.project("s-long")

        assertEquals(625, projected.messages.size)
        assertEquals("s-long-m0", projected.messages.first().id)
    }

    @Test
    fun `snapshot exposes folded count and summary after compact`() = runBlocking {
        val sessionId = "s-snap"
        seedUserMessages(sessionId, 6)
        val projected = compaction.project(sessionId)
        assertEquals(6, projected.messages.size)

        compaction.compact(sessionId, projected, keepFromIndex = 4)
        val snapshot = compaction.latestSnapshot(sessionId)!!
        assertEquals(4, snapshot.foldedMessageCount)
        assertTrue(snapshot.summary.isNotBlank())
        assertTrue(snapshot.createdAt > 0)
    }

    @Test
    fun `latest snapshot across rounds reports cumulative folded count`() = runBlocking {
        val sessionId = "s-cumulative"
        seedUserMessages(sessionId, 10)
        var context = compaction.project(sessionId)
        compaction.compact(sessionId, context, keepFromIndex = 5)
        context = compaction.project(sessionId)
        compaction.compact(sessionId, context, keepFromIndex = 2)

        val snapshot = compaction.latestSnapshot(sessionId)!!
        // 多轮压缩下 foldedMessageCount 为历次累计：第一轮折叠 5 条 + 第二轮折叠 2 条
        assertEquals(7, snapshot.foldedMessageCount)
        // 横幅展示的必须是“当前生效”的压缩状态：快照摘要与第二轮投影一致
        assertEquals(compaction.project(sessionId).summary, snapshot.summary)
    }

    @Test
    fun `rolling summary keeps newly folded facts after previous summary reaches cap`() = runBlocking {
        val sessionId = "s-rolling"
        repository.ensureLane(sessionId, "main")
        val latestMarker = "LATEST_DECISION_USE_SQL_QUERY"
        // 折叠段必须真实存在于 lane 上（compact 会按分支前缀对账，拒绝折叠 lane 外的消息）
        listOf(
            UserMessage(id = "latest", createdAt = 1, text = "关键决定：$latestMarker"),
            UserMessage(id = "retained", createdAt = 2, text = "continue"),
        ).forEach { message ->
            repository.appendToLane(
                sessionId,
                "main",
                HarnessEntryEntity(
                    id = message.id,
                    sessionId = sessionId,
                    parentId = repository.findLane(sessionId, "main")!!.leafId,
                    createdAt = message.createdAt,
                    entryType = "message",
                    customType = "user",
                    payloadJson = Json.encodeToString(HarnessMessage.serializer(), message),
                ),
            )
        }
        val context = CompactedContext(
            summary = "old-context ".repeat(600),
            messages = compaction.project(sessionId).messages,
        )

        val compacted = compaction.compact(sessionId, context, keepFromIndex = 1)

        assertTrue(compacted.summary.orEmpty().contains(latestMarker))
        assertTrue(compacted.summary.orEmpty().length <= 16_000)
    }

    @Test
    fun `mechanical compaction accumulates programmatic file footprints`() = runBlocking {
        val sessionId = "s-file-footprints"
        repository.ensureLane(sessionId, "main")
        suspend fun append(message: HarnessMessage) {
            repository.appendToLane(
                sessionId,
                "main",
                HarnessEntryEntity(
                    id = message.id,
                    sessionId = sessionId,
                    parentId = repository.findLane(sessionId, "main")!!.leafId,
                    createdAt = message.createdAt,
                    entryType = "message",
                    customType = null,
                    payloadJson = Json.encodeToString(HarnessMessage.serializer(), message),
                ),
            )
        }
        append(UserMessage("u1", 1L, "改一下网络层"))
        append(ToolCall("c-read", 2L, HarnessTool.READ, buildJsonObject { put("path", "core/network/A.kt") }, rawToolName = "read"))
        append(ToolResult("r-read", 3L, "c-read", success = true, output = "ok"))
        append(ToolCall("c-write", 4L, HarnessTool.WRITE, buildJsonObject { put("path", "core/network/B.kt") }, rawToolName = "write"))
        append(ToolResult("r-write", 5L, "c-write", success = true, output = "ok"))
        append(AssistantText("a1", 6L, "完成"))

        val context = compaction.project(sessionId)
        val compacted = compaction.compact(sessionId, context, keepFromIndex = context.messages.size - 1)

        // 无 summarizer → 机械摘要路径，也必须程序化携带累计文件足迹
        val summary = compacted.summary.orEmpty()
        assertTrue(summary.contains("<read-files>"))
        assertTrue(summary.contains("core/network/A.kt"))
        assertTrue(summary.contains("<modified-files>"))
        assertTrue(summary.contains("core/network/B.kt"))
    }
}
