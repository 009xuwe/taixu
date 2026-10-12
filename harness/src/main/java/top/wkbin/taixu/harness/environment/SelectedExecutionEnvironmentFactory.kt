package top.wkbin.taixu.harness.environment

import top.wkbin.taixu.core.datastore.RemoteServerRepository
import top.wkbin.taixu.runtime.environment.*
import top.wkbin.taixu.runtime.remote.SshConnector

/** Explicit inherited distributions take precedence over the current global selection. */
class SelectedExecutionEnvironmentFactory(
    private val local: ExecutionEnvironmentFactory,
    private val servers: RemoteServerRepository,
    private val connector: SshConnector,
) : ExecutionEnvironmentFactory {
    override suspend fun open(owner: String, workspace: String, distribution: String?): ExecutionEnvironment {
        val target = distribution ?: servers.activeId.value?.let { "ssh:$it" }
        if (target?.startsWith("ssh:") != true) return local.open(owner, workspace, distribution)
        val configured = servers.servers.value.singleOrNull { it.distributionId == target }
            ?: error("绑定的远程服务器已移除，请选择环境并新建会话")
        val (server, credentials) = servers.connection(configured.id)
        // Project identifiers map beneath the configured remote root; phone absolute paths never cross over.
        val relative = workspace.removePrefix("/workspace").trim('/')
        require(!workspace.startsWith('/') || workspace == "/workspace" || workspace.startsWith("/workspace/")) { "非法远程项目路径" }
        require(relative.split('/').none { it == ".." } && '\u0000' !in relative)
        val cwd = server.workspace.trimEnd('/') + relative.takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty()
        return connector.openEnvironment(server, credentials, ExecutionEnvironmentId("ssh", target, cwd, owner), workspace.ifBlank { "/workspace" })
    }
}
