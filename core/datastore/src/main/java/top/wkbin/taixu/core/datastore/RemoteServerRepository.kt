package top.wkbin.taixu.core.datastore

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.model.RemoteServer
import top.wkbin.taixu.core.model.SshCredentials
import top.wkbin.taixu.core.security.SecretManager
import java.io.File

interface RemoteServerRepository {
    val servers: StateFlow<List<RemoteServer>>
    val activeId: StateFlow<String?>
    suspend fun credentials(id: String): SshCredentials
    suspend fun connection(id: String): Pair<RemoteServer, SshCredentials> =
        checkNotNull(servers.value.singleOrNull { it.id == id }) { "服务器配置已移除" } to credentials(id)
    suspend fun save(server: RemoteServer, credentials: SshCredentials)
    suspend fun select(id: String?)
    suspend fun remove(id: String)
}

/** Whole snapshot is encrypted, atomically replaced and excluded from Android backup. */
class EncryptedRemoteServerRepository(context: Context, private val secrets: SecretManager) : RemoteServerRepository {
    @Serializable private data class Entry(val server: RemoteServer, val credentials: SshCredentials)
    @Serializable private data class Snapshot(val entries: List<Entry> = emptyList(), val active: String? = null)
    private val file = AtomicFile(File(context.noBackupFilesDir, "remote-servers.enc"))
    private val mutex = Mutex()
    private val json = Json
    private var snapshot: Snapshot = if (file.baseFile.exists()) {
        // 该单例首次注入发生在首页/工坊首帧组合时：密钥失效或存档损坏不能抛异常拖垮启动，
        // 降级为空快照（保留原存档，重新添加连接后会覆盖）。
        val encrypted = file.openRead().use { String(it.readBytes(), Charsets.UTF_8) }
        secrets.decrypt(encrypted)
            ?.let { plaintext -> runCatching { json.decodeFromString(Snapshot.serializer(), plaintext) }.getOrNull() }
            ?: Snapshot()
    } else Snapshot()
    private val serverState = MutableStateFlow(snapshot.entries.map { it.server })
    private val activeState = MutableStateFlow(snapshot.active)
    override val servers: StateFlow<List<RemoteServer>> = serverState
    override val activeId: StateFlow<String?> = activeState

    override suspend fun credentials(id: String) = mutex.withLock {
        snapshot.entries.single { it.server.id == id }.credentials
    }
    override suspend fun connection(id: String) = mutex.withLock {
        val entry = snapshot.entries.singleOrNull { it.server.id == id } ?: error("服务器配置已移除")
        entry.server to entry.credentials
    }
    override suspend fun save(server: RemoteServer, credentials: SshCredentials) = mutate {
        server.validate()
        require(server.fingerprint.startsWith("SHA256:")) { "请先验证服务器主机指纹" }
        require(credentials.secret.isNotBlank()) { "请输入密码或私钥" }
        copy(entries = entries.filterNot { it.server.id == server.id } + Entry(server, credentials))
    }
    override suspend fun select(id: String?) = mutate {
        require(id == null || entries.any { it.server.id == id }) { "服务器不存在" }
        copy(active = id)
    }
    override suspend fun remove(id: String) = mutate {
        copy(entries = entries.filterNot { it.server.id == id }, active = active.takeUnless { it == id })
    }
    private suspend fun mutate(update: Snapshot.() -> Snapshot) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = snapshot.update()
            val bytes = secrets.encrypt(json.encodeToString(Snapshot.serializer(), next)).toByteArray(Charsets.UTF_8)
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) }
            catch (t: Throwable) { file.failWrite(stream); throw t }
            snapshot = next
            serverState.value = next.entries.map { it.server }
            activeState.value = next.active
        }
    }
}
