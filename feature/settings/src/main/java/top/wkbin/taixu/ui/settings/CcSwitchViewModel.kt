package top.wkbin.taixu.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.database.AiModelEntity
import top.wkbin.taixu.core.database.AiModelRepository
import top.wkbin.taixu.core.database.ToolSettingsRepository
import top.wkbin.taixu.core.model.CcAgentState
import top.wkbin.taixu.core.model.CcAgentType
import top.wkbin.taixu.core.model.CcProviderProfile
import top.wkbin.taixu.core.model.CcSwitchDaemonStatus
import top.wkbin.taixu.core.network.CcSwitchClient
import top.wkbin.taixu.core.tools.ProviderRepository
import top.wkbin.taixu.core.tools.ToolManager
import top.wkbin.taixu.runtime.LinuxRuntime
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject

data class CcSwitchUiState(
    val isInstalled: Boolean = true,
    val isDaemonRunning: Boolean = false,
    val isOperating: Boolean = false,
    val autoStartEnabled: Boolean = false,
    val servicePort: Int = 19870,
    val daemonStatus: CcSwitchDaemonStatus? = null,
    val agents: List<CcAgentState> = defaultAgents(),
    val providers: List<CcProviderProfile> = emptyList(),
    val taiXuModels: List<AiModelEntity> = emptyList(),
    val deviceLanIp: String? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val switchingAgent: CcAgentState? = null,
    val upgradingAgent: CcAgentState? = null,
) {
    val loopbackUrl: String get() = "http://127.0.0.1:$servicePort"
    val lanUrl: String? get() = deviceLanIp?.let { "http://$it:$servicePort" }
    val preferredWebUrl: String get() = lanUrl ?: loopbackUrl

    companion object {
        fun defaultAgents(): List<CcAgentState> = listOf(
            CcAgentState(
                type = CcAgentType.CLAUDE_CODE,
                installed = true,
                currentVersion = "1.0.0",
                latestVersion = "1.0.0",
                activeProviderName = "默认 Anthropic / 中转",
                description = "Anthropic 官方全自动代码探索与工程构建 CLI",
            ),
            CcAgentState(
                type = CcAgentType.OPENCLAW,
                installed = true,
                currentVersion = "0.1.0",
                latestVersion = "0.1.0",
                activeProviderName = "默认网关模型",
                running = true,
                servicePort = 18789,
                webPath = "/",
                description = "本地自动化工作流与 Agent 控制台中枢",
            ),
            CcAgentState(
                type = CcAgentType.HERMES,
                installed = true,
                currentVersion = "0.1.0",
                latestVersion = "0.1.0",
                activeProviderName = "Nous / 本地模型",
                servicePort = 9119,
                webPath = "/",
                description = "可扩展本地 Agent 调度与执行引擎",
            ),
            CcAgentState(
                type = CcAgentType.CODEX,
                installed = false,
                currentVersion = null,
                latestVersion = "1.0.0",
                description = "OpenCode / Codex 智能代码辅助与补全",
            ),
        )
    }
}

@HiltViewModel
class CcSwitchViewModel @Inject constructor(
    private val toolManager: ToolManager,
    private val ccSwitchClient: CcSwitchClient,
    private val aiModelDao: AiModelRepository,
    private val providerRepository: ProviderRepository,
    private val toolSettingsRepository: ToolSettingsRepository,
    private val linuxRuntime: LinuxRuntime,
    private val logger: AppLogger,
) : ViewModel() {

    private val toolId = "cc-switch"

    private val _isOperating = MutableStateFlow(false)
    private val _isDaemonRunning = MutableStateFlow(false)
    private val _daemonStatus = MutableStateFlow<CcSwitchDaemonStatus?>(null)
    private val _agents = MutableStateFlow<List<CcAgentState>>(CcSwitchUiState.defaultAgents())
    private val _providers = MutableStateFlow<List<CcProviderProfile>>(emptyList())
    private val _deviceLanIp = MutableStateFlow<String?>(null)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _successMessage = MutableStateFlow<String?>(null)
    private val _switchingAgent = MutableStateFlow<CcAgentState?>(null)
    private val _upgradingAgent = MutableStateFlow<CcAgentState?>(null)

    val uiState: StateFlow<CcSwitchUiState> = combine(
        linuxRuntime.activeDistroId,
        _isOperating,
        _isDaemonRunning,
        _daemonStatus,
        _agents,
        _providers,
        aiModelDao.observeAll(),
        _deviceLanIp,
        _errorMessage,
        _successMessage,
        _switchingAgent,
        _upgradingAgent,
    ) { values ->
        val distroId = values[0] as String
        val operating = values[1] as Boolean
        val daemonRunning = values[2] as Boolean
        val daemonStatus = values[3] as CcSwitchDaemonStatus?
        @Suppress("UNCHECKED_CAST")
        val agents = values[4] as List<CcAgentState>
        @Suppress("UNCHECKED_CAST")
        val providers = values[5] as List<CcProviderProfile>
        @Suppress("UNCHECKED_CAST")
        val models = values[6] as List<AiModelEntity>
        val lanIp = values[7] as String?
        val err = values[8] as String?
        val success = values[9] as String?
        val switching = values[10] as CcAgentState?
        val upgrading = values[11] as CcAgentState?

        CcSwitchUiState(
            isInstalled = true,
            isDaemonRunning = daemonRunning,
            isOperating = operating,
            autoStartEnabled = false,
            daemonStatus = daemonStatus,
            agents = agents,
            providers = providers,
            taiXuModels = models,
            deviceLanIp = lanIp,
            errorMessage = err,
            successMessage = success,
            switchingAgent = switching,
            upgradingAgent = upgrading,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CcSwitchUiState())

    init {
        refreshStatus()
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(3500)
                try {
                    val running = toolManager.isGatewayRunning(toolId)
                    val lanIp = detectLanIp()
                    withContext(Dispatchers.Main.immediate) {
                        _isDaemonRunning.value = running
                        _deviceLanIp.value = lanIp
                    }
                    if (running) {
                        fetchDaemonData()
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val lanIp = detectLanIp()
            val running = toolManager.isGatewayRunning(toolId)
            withContext(Dispatchers.Main.immediate) {
                _isDaemonRunning.value = running
                _deviceLanIp.value = lanIp
            }
            if (running) {
                fetchDaemonData()
            }
        }
    }

    private suspend fun fetchDaemonData() {
        val statusRes = ccSwitchClient.getStatus()
        val agentsRes = ccSwitchClient.getAgents()
        val providersRes = ccSwitchClient.getProviders()

        withContext(Dispatchers.Main.immediate) {
            statusRes.onSuccess { _daemonStatus.value = it }
            agentsRes.onSuccess { if (it.isNotEmpty()) _agents.value = it }
            providersRes.onSuccess { _providers.value = it }
        }
    }

    fun startDaemon() {
        _isOperating.value = true
        _errorMessage.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                toolManager.startGateway(toolId)
                delay(1000)
                val running = toolManager.isGatewayRunning(toolId)
                withContext(Dispatchers.Main.immediate) {
                    _isDaemonRunning.value = running
                    _successMessage.value = "CC-Switch 守护进程已启动，端口 19870"
                }
                fetchDaemonData()
            } catch (e: Exception) {
                logger.w("Failed to start cc-switch daemon: ${e.message}", e)
                withContext(Dispatchers.Main.immediate) {
                    _errorMessage.value = "启动中枢守护进程失败：${e.message}"
                }
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun stopDaemon() {
        _isOperating.value = true
        _errorMessage.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                toolManager.stopGateway(toolId)
                delay(500)
                val running = toolManager.isGatewayRunning(toolId)
                withContext(Dispatchers.Main.immediate) {
                    _isDaemonRunning.value = running
                    _successMessage.value = "CC-Switch 守护进程已停止"
                }
            } catch (e: Exception) {
                logger.w("Failed to stop cc-switch daemon: ${e.message}", e)
                withContext(Dispatchers.Main.immediate) {
                    _errorMessage.value = "停止中枢守护进程失败：${e.message}"
                }
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun restartDaemon() {
        _isOperating.value = true
        _errorMessage.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                toolManager.restartGateway(toolId)
                delay(1000)
                val running = toolManager.isGatewayRunning(toolId)
                withContext(Dispatchers.Main.immediate) {
                    _isDaemonRunning.value = running
                    _successMessage.value = "CC-Switch 守护进程已重启"
                }
                fetchDaemonData()
            } catch (e: Exception) {
                logger.w("Failed to restart cc-switch daemon: ${e.message}", e)
                withContext(Dispatchers.Main.immediate) {
                    _errorMessage.value = "重启失败：${e.message}"
                }
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun openSwitchProviderDialog(agent: CcAgentState) {
        _switchingAgent.value = agent
    }

    fun openUpgradeDialog(agent: CcAgentState) {
        _upgradingAgent.value = agent
    }

    fun dismissDialogs() {
        _switchingAgent.value = null
        _upgradingAgent.value = null
    }

    fun dismissMessage() {
        _errorMessage.value = null
        _successMessage.value = null
    }

    fun switchAgentProvider(agent: CcAgentState, provider: CcProviderProfile) {
        viewModelScope.launch {
            _switchingAgent.value = null
            _isOperating.value = true
            try {
                val res = ccSwitchClient.switchAgentProvider(agent.type.id, provider.id)
                if (res.isSuccess) {
                    // Update local state optimistic
                    _agents.value = _agents.value.map {
                        if (it.type == agent.type) {
                            it.copy(
                                activeProviderId = provider.id,
                                activeProviderName = provider.name,
                                activeModelName = provider.selectedModel,
                            )
                        } else it
                    }
                    _successMessage.value = "已将 ${agent.type.displayName} 切换至 ${provider.name}"
                } else {
                    _errorMessage.value = "切换 Provider 失败：${res.exceptionOrNull()?.message}"
                }
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun importTaiXuModel(model: AiModelEntity) {
        viewModelScope.launch {
            _isOperating.value = true
            try {
                val apiKey = if (model.secretRef.isNotBlank()) {
                    providerRepository.readModelApiKey(model.secretRef).orEmpty()
                } else ""
                val profile = CcProviderProfile(
                    id = model.id,
                    name = model.name,
                    protocol = if (model.provider.contains("anthropic", ignoreCase = true)) "ANTHROPIC" else "OPENAI",
                    baseUrl = model.baseUrl,
                    apiKey = apiKey,
                    maskedApiKey = if (apiKey.length > 8) "${apiKey.take(4)}...${apiKey.takeLast(4)}" else "***",
                    selectedModel = model.model,
                    availableModels = listOf(model.model),
                    isCustom = true,
                )
                val saveRes = ccSwitchClient.saveProvider(profile)
                if (saveRes.isSuccess) {
                    _providers.value = _providers.value.filterNot { it.id == profile.id } + profile
                    _successMessage.value = "已从太墟模型库同步：${model.name}"
                } else {
                    _errorMessage.value = "同步到 CC-Switch 失败：${saveRes.exceptionOrNull()?.message}"
                }
            } finally {
                _isOperating.value = false
            }
        }
    }

    fun installOrUpgradeAgent(agent: CcAgentState, targetVersion: String?) {
        _upgradingAgent.value = null
        _isOperating.value = true
        viewModelScope.launch {
            try {
                val res = ccSwitchClient.installOrUpdateAgent(agent.type.id, targetVersion)
                if (res.isSuccess) {
                    _successMessage.value = "${agent.type.displayName} 版本变更任务已提交"
                    fetchDaemonData()
                } else {
                    _errorMessage.value = "操作失败：${res.exceptionOrNull()?.message}"
                }
            } finally {
                _isOperating.value = false
            }
        }
    }

    private fun detectLanIp(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces.asSequence()) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses.asSequence()) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (!host.isNullOrBlank() && host != "127.0.0.1") {
                            return host
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
