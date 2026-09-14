package top.wkbin.taixu.harness.mcp

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.database.McpServerRepository
import top.wkbin.taixu.core.model.McpConnectionState
import top.wkbin.taixu.core.model.McpServerConfig
import top.wkbin.taixu.core.model.McpToolInfo
import top.wkbin.taixu.core.model.McpTransportType
import top.wkbin.taixu.harness.events.AgentEventLogger
import top.wkbin.taixu.runtime.LinuxRuntime
import kotlin.time.Duration.Companion.milliseconds

/** Thin MCP registry coordinator; transports own protocol and process details. */
@Singleton
class McpManager @Inject constructor(
    private val repository: McpServerRepository,
    private val stdio: McpStdioTransport,
    private val http: McpHttpTransport,
    private val commandBuilder: McpCommandBuilder,
    private val linuxRuntime: LinuxRuntime,
    private val logger: AppLogger,
    private val agentEventLogger: AgentEventLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class CachedTools(val fingerprint: String, val tools: List<McpToolInfo>)
    private val cache = ConcurrentHashMap<String, CachedTools>()
    private val discoveryMutexes = ConcurrentHashMap<String, Mutex>()
    private val lastErrors = ConcurrentHashMap<String, String>()
    /** B1: server id → 最近一次 executeTool 绑定使用的 workspace，供 discover/check 路径复用 */
    private val lastBoundWorkspaces = ConcurrentHashMap<String, String>()
    private val _connectionStates = MutableStateFlow<Map<String, McpConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, McpConnectionState>> = _connectionStates.asStateFlow()

    init {
        // 启动时后台异步预热已启用的 MCP 服务，提前填充缓存，用户首次发消息直接 0ms 命中
        scope.launch {
            runCatching { getActiveMcpTools() }
        }
    }

    fun getLastError(serverId: String): String? = lastErrors[serverId]

    suspend fun checkConnection(server: McpServerConfig): Boolean = withContext(Dispatchers.IO) {
        // B1: 与 executeTool 一致先做 workspace 绑定再建 transport，
        // 保证 check 与执行路径看到相同 fingerprint，避免误判连接失效而重启进程
        val bound = boundConfig(server)
        withTimeoutOrNull(DISCOVERY_TIMEOUT_MS.milliseconds) { transport(bound).check(bound) } ?: false
    }

    suspend fun refreshConnections() = withContext(Dispatchers.IO) {
        val servers = repository.servers.first()
        servers.filterNot { it.isEnabled }.forEach { server ->
            cache.remove(server.id)
            lastErrors.remove(server.id)
            closeTransportConnection(server)
        }
        _connectionStates.value = servers.associate { it.id to if (it.isEnabled) McpConnectionState.CHECKING else McpConnectionState.UNKNOWN }
        coroutineScope {
            servers.filter { it.isEnabled }.map { server ->
                launch {
                    val state = if (checkConnection(server)) McpConnectionState.ONLINE else McpConnectionState.OFFLINE
                    _connectionStates.update { it + (server.id to state) }
                }
            }.joinAll()
        }
    }

    suspend fun getActiveMcpTools(): List<McpToolInfo> = withContext(Dispatchers.IO) {
        val servers = repository.servers.first()
        servers.filterNot { it.isEnabled }.forEach { server ->
            cache.remove(server.id)
            lastBoundWorkspaces.remove(server.id)
            closeTransportConnection(server)
        }
        val enabledServers = servers.filter { it.isEnabled }
        if (enabledServers.isEmpty()) return@withContext emptyList()

        val needsLinux = enabledServers.any { it.transportType == McpTransportType.STDIO }
        val linuxReady = !needsLinux || awaitLinuxRuntimeReady(linuxRuntime.state)

        coroutineScope {
            enabledServers.map { server ->
                async {
                    if (server.transportType == McpTransportType.STDIO && !linuxReady) {
                        lastErrors[server.id] = RUNTIME_NOT_READY_MSG
                        state(server.id, McpConnectionState.OFFLINE)
                        logger.w("MCP[${server.name}] 工具发现推迟：Linux runtime 尚未就绪，下一轮对话将重试")
                        return@async emptyList()
                    }
                    // B1: 用绑定后的配置计算 fingerprint 并发现，与 executeTool 路径一致，避免指纹乒乓
                    val bound = boundConfig(server)
                    val fingerprint = fingerprint(bound)
                    cache[server.id]?.takeIf { it.fingerprint == fingerprint }?.tools
                        ?: discoveryMutexes.getOrPut(server.id) { Mutex() }.withLock {
                            cache[server.id]?.takeIf { it.fingerprint == fingerprint }?.tools ?: run {
                                // 总超时兜底：沙箱会话拉起或 MCP 进程挂起时不能阻塞每轮对话（挂起是无日志的），
                                // 超时按失败处理，本轮不注入该服务工具，下一轮重试。
                                agentEventLogger.log(DISCOVERY_LOG_SESSION, "McpDiscovery", "MCP[${server.name}] 工具发现开始（transport=${server.transportType}）")
                                val startedAt = System.currentTimeMillis()
                                cancellableResult { discoverWithTimeout(bound) }.onSuccess {
                                    agentEventLogger.log(
                                        DISCOVERY_LOG_SESSION,
                                        "McpDiscovery",
                                        "MCP[${server.name}] 发现 ${it.size} 个工具，耗时 ${System.currentTimeMillis() - startedAt}ms",
                                    )
                                }.onFailure {
                                    agentEventLogger.log(
                                        DISCOVERY_LOG_SESSION,
                                        "McpDiscovery",
                                        "MCP[${server.name}] 工具发现失败，耗时 ${System.currentTimeMillis() - startedAt}ms：${it.message ?: it::class.simpleName}",
                                        it,
                                    )
                                }
                            }.onSuccess {
                                cache[server.id] = CachedTools(fingerprint, it)
                                lastErrors.remove(server.id)
                                state(server.id, McpConnectionState.ONLINE)
                            }.onFailure {
                                val msg = it.message ?: "工具发现异常"
                                lastErrors[server.id] = msg
                                // 静默失败会让"模型不调用 MCP 工具"无从排查，这里必须留下线索；
                                // 冷却期内的重复失败只记一行，不再打整段堆栈刷屏。
                                val inCooldown = msg.contains("冷却中")
                                logger.w(
                                    "MCP[${server.name}] 工具发现失败，本轮对话不注入该服务的工具: $msg",
                                    if (inCooldown) null else it,
                                )
                                cache.remove(server.id)
                                state(server.id, McpConnectionState.OFFLINE)
                            }.getOrDefault(emptyList())
                        }
                }
            }.awaitAll().flatten()
        }
    }

    suspend fun discoverTools(server: McpServerConfig): List<McpToolInfo> = withContext(Dispatchers.IO) {
        val bound = boundConfig(server)
        transport(bound).discover(bound)
    }

    suspend fun testServer(server: McpServerConfig): Result<List<McpToolInfo>> = withContext(Dispatchers.IO) {
        // B1: 测试路径同样按绑定后配置发现并写缓存，保证缓存 fingerprint 与其他路径一致
        val bound = boundConfig(server)
        cancellableResult { discoverWithTimeout(bound) }
        .onSuccess { cache[server.id] = CachedTools(fingerprint(bound), it); state(server.id, McpConnectionState.ONLINE) }
        .onFailure { cache.remove(server.id); state(server.id, McpConnectionState.OFFLINE) }
    }

    suspend fun executeTool(
        fullToolName: String,
        arguments: JsonObject,
        workspace: String = "",
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!fullToolName.startsWith("mcp__")) return@withContext false to "无效的 MCP 工具名称：$fullToolName"
        val tool = getActiveMcpTools().firstOrNull { McpToolApiName.matches(it, fullToolName) }
            ?: return@withContext false to "未找到 MCP 工具：$fullToolName"
        val server = repository.servers.first().firstOrNull { it.id == tool.serverId && it.isEnabled }
            ?: return@withContext false to "未找到 MCP 服务：${tool.serverId}"
        val bound = commandBuilder.bindWorkspaceRepository(server, workspace)
        // B1: 记录本次 workspace，使后续 discover/check 路径使用同一绑定配置，
        // 各路径 fingerprint 一致，避免 STDIO 进程被指纹乒乓反复重启
        lastBoundWorkspaces[server.id] = workspace
        return@withContext cancellableResult { transport(bound).execute(bound, tool.name, arguments) }
            .onFailure { logger.e("MCP[${server.name}] 工具 ${tool.name} 执行异常: ${it.message}", it) }
            .onSuccess { (ok, output) ->
                if (!ok) logger.w("MCP[${server.name}] 工具 ${tool.name} 返回错误: $output".take(500))
            }
            .getOrElse { false to "MCP 工具执行异常：${it.message ?: it::class.simpleName}" }
    }

    private suspend fun discoverWithTimeout(server: McpServerConfig): List<McpToolInfo> =
        withTimeoutOrNull(DISCOVERY_TIMEOUT_MS.milliseconds) { transport(server).discover(server) }
            ?: error("工具发现超时（${DISCOVERY_TIMEOUT_MS / 1000}s）：沙箱会话或 MCP 进程可能已挂起")

    private suspend fun <T> cancellableResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }

    private fun state(id: String, state: McpConnectionState) { _connectionStates.update { it + (id to state) } }

    /**
     * B1: 按 executeTool 最近一次使用的 workspace 绑定配置，使 discover/check/test
     * 与执行路径计算 fingerprint 时看到同一份配置（否则 STDIO 指纹乒乓导致进程反复重启）。
     */
    private fun boundConfig(server: McpServerConfig): McpServerConfig =
        commandBuilder.bindWorkspaceRepository(server, lastBoundWorkspaces[server.id] ?: "")

    /** B10: 禁用/删除的 server 关闭其常驻传输资源（STDIO 进程 / HTTP legacy SSE 会话） */
    private suspend fun closeTransportConnection(server: McpServerConfig) {
        when (server.transportType) {
            McpTransportType.STDIO -> stdio.closeConnection(server.id)
            McpTransportType.SSE -> http.closeSession(server.id)
        }
    }

    private fun fingerprint(server: McpServerConfig): String =
        "${server.transportType}|${server.serverUrl.trim()}|${server.command}|${server.args}|${server.env.toSortedMap()}"
    private fun transport(server: McpServerConfig): McpTransport = when (server.transportType) {
        McpTransportType.STDIO -> stdio
        McpTransportType.SSE -> http
    }

    private companion object {
        /** 单服务器工具发现总超时：覆盖沙箱会话拉起 + initialize + tools/list，超时即本轮跳过注入。 */
        const val DISCOVERY_TIMEOUT_MS = 8_000L

        /** 工具发现无会话上下文，agent 事件日志用占位 sessionId。 */
        const val DISCOVERY_LOG_SESSION = "-"

        const val RUNTIME_NOT_READY_MSG = "Linux runtime is not ready. Call initialize() first."
    }
}
