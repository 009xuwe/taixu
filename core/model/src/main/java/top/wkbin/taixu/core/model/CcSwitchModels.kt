package top.wkbin.taixu.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class CcAgentType(val id: String, val displayName: String, val defaultExecutable: String) {
    CLAUDE_CODE("claude-code", "Claude Code", "claude"),
    OPENCLAW("openclaw", "OpenClaw", "openclaw"),
    HERMES("hermes-agent", "Hermes Agent", "hermes"),
    CODEX("codex", "Codex / OpenCode", "codex");

    companion object {
        fun fromId(id: String): CcAgentType? = entries.firstOrNull { it.id == id }
    }
}

@Serializable
data class CcAgentState(
    val type: CcAgentType,
    val installed: Boolean = false,
    val currentVersion: String? = null,
    val latestVersion: String? = null,
    val availableVersions: List<String> = emptyList(),
    val activeProviderId: String? = null,
    val activeProviderName: String? = null,
    val activeModelName: String? = null,
    val running: Boolean = false,
    val servicePort: Int? = null,
    val webPath: String? = null,
    val description: String = "",
)

@Serializable
data class CcProviderProfile(
    val id: String,
    val name: String,
    val protocol: String = "OPENAI", // "OPENAI" or "ANTHROPIC"
    val baseUrl: String = "",
    val apiKey: String = "",
    val maskedApiKey: String = "",
    val selectedModel: String = "",
    val availableModels: List<String> = emptyList(),
    val isCustom: Boolean = false,
    val iconName: String? = null,
)

@Serializable
data class CcTokenUsage(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
    val totalCostEstimateCny: Double = 0.0,
    val requestCount: Long = 0,
)

@Serializable
data class CcSwitchDaemonStatus(
    val running: Boolean = false,
    val port: Int = 19870,
    val version: String = "1.0.0",
    val proxyEnabled: Boolean = true,
    val uptimeSeconds: Long = 0,
    val totalTokens: CcTokenUsage = CcTokenUsage(),
    val todayTokens: CcTokenUsage = CcTokenUsage(),
)
