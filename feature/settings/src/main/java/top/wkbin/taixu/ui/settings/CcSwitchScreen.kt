package top.wkbin.taixu.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.taixu.core.database.AiModelEntity
import top.wkbin.taixu.core.model.CcAgentState
import top.wkbin.taixu.core.model.CcAgentType
import top.wkbin.taixu.core.model.CcProviderProfile
import top.wkbin.taixu.ui.components.InfoRow
import top.wkbin.taixu.ui.components.NoticeBanner
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeButton
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeCircularProgressIndicator
import top.wkbin.taixu.ui.components.RuntimeFilledTonalButton
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconButton
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeOutlinedButton
import top.wkbin.taixu.ui.components.RuntimeTextButton
import top.wkbin.taixu.ui.components.RuntimeTopBar
import top.wkbin.taixu.ui.components.StatusBadge

/**
 * 太墟 · CC-Switch 智能体中枢主界面
 *
 * 核心功能：
 * - 守护进程与反代服务启停监控（端口 19870）
 * - Claude Code / OpenClaw / Hermes / Codex 矩阵管理
 * - 一键热切换各 Agent 的 Provider（支持从太墟本地模型库同步）
 * - 版本查看与一键升降级
 * - 实时安装与编译构建日志监视（支持一键复制全量报错）
 * - Token 记账与反向代理状态监控
 * - 原生 PTY 终端拉起与系统外部浏览器 Web 控制台访问
 */
@Composable
fun CcSwitchScreen(
    onBack: () -> Unit,
    onLaunchTerminal: (executable: String) -> Unit,
    onOpenBrowser: (url: String) -> Unit,
    viewModel: CcSwitchViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            RuntimeTopBar(
                title = "智能体中枢 (CC-Switch)",
                statusText = if (state.isDaemonRunning) "中枢运行中 · 19870" else "中枢未启动",
                onBack = onBack,
                actions = {
                    RuntimeIconButton(
                        onClick = { viewModel.refreshStatus() },
                        enabled = !state.isOperating,
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Refresh,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 提示横幅
            state.errorMessage?.let { error ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NoticeBanner(
                        text = error,
                        isError = true,
                        onDismiss = { viewModel.dismissMessage() },
                    )
                    if (state.installLogs.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            RuntimeOutlinedButton(
                                onClick = { viewModel.showInstallLogs() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                RuntimeIcon(name = RuntimeIconName.Terminal, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("查看并复制完整报错日志", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            state.successMessage?.let { msg ->
                NoticeBanner(
                    text = msg,
                    isError = false,
                    onDismiss = { viewModel.dismissMessage() },
                )
            }

            // 1. 守护进程卡片
            DaemonServiceCard(
                state = state,
                onStart = { viewModel.startDaemon() },
                onStop = { viewModel.stopDaemon() },
                onRestart = { viewModel.restartDaemon() },
                onResetPassword = { viewModel.resetWebPassword(it) },
                onOpenWebConsole = {
                    val targetUrl = state.loopbackUrl.ifBlank { state.preferredWebUrl }
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching {
                        context.startActivity(intent)
                    }.onFailure {
                        onOpenBrowser(targetUrl)
                    }
                },
            )

            // 2. Token & 代理记账看板
            state.daemonStatus?.let { daemon ->
                TokenUsageCard(daemon = daemon)
            }

            // 3. 本地环境检查矩阵
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "本地环境检查",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (state.isDaemonRunning) "由 CC-Switch 纳管各 CLI 与 Agent 运行环境" else "启动中枢后自动检查环境与版本",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.installLogs.isNotEmpty()) {
                        RuntimeOutlinedButton(
                            onClick = { viewModel.showInstallLogs() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            RuntimeIcon(name = RuntimeIconName.Terminal, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("安装日志", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                        }
                    }
                    if (state.isDaemonRunning) {
                        RuntimeFilledTonalButton(
                            onClick = { viewModel.refreshEnvironment() },
                            enabled = !state.isOperating && !state.isCheckingEnvironment,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            if (state.isCheckingEnvironment) {
                                RuntimeCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("检测中...", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                            } else {
                                RuntimeIcon(name = RuntimeIconName.Refresh, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("刷新检测", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }

            state.agents.forEach { agent ->
                AgentCard(
                    agent = agent,
                    isDaemonRunning = state.isDaemonRunning,
                    isOperating = state.isOperating,
                    isInstalling = state.installingAgentType == agent.type,
                    onSwitchProvider = { viewModel.openSwitchProviderDialog(agent) },
                    onInstall = { viewModel.installOrUpgradeAgent(agent, targetVersion = null) },
                    onUpgrade = { viewModel.installOrUpgradeAgent(agent, targetVersion = agent.latestVersion) },
                    onShowLogs = { viewModel.showInstallLogs() },
                )
            }

            // 底部高级同步区
            AdvancedSyncCard(
                taiXuModels = state.taiXuModels,
                onImportModel = { viewModel.importTaiXuModel(it) },
            )
        }
    }

    // 切换 Provider 弹窗
    state.switchingAgent?.let { agent ->
        SwitchProviderDialog(
            agent = agent,
            providers = state.providers,
            taiXuModels = state.taiXuModels,
            onDismiss = { viewModel.dismissDialogs() },
            onSelectProvider = { provider -> viewModel.switchAgentProvider(agent, provider) },
            onImportAndSelect = { model ->
                viewModel.importTaiXuModel(model)
            },
        )
    }

    // 版本管理 / 升级弹窗
    state.upgradingAgent?.let { agent ->
        UpgradeVersionDialog(
            agent = agent,
            onDismiss = { viewModel.dismissDialogs() },
            onConfirmUpgrade = { version -> viewModel.installOrUpgradeAgent(agent, version) },
        )
    }

    // 安装与构建实时日志弹窗
    if (state.showLogDialog) {
        InstallLogDialog(
            agentName = state.logAgentTitle,
            logs = state.installLogs,
            isOperating = state.isOperating,
            isError = state.errorMessage != null,
            onDismiss = { viewModel.dismissInstallLogs() },
            onClear = { viewModel.clearInstallLogs() },
            onCopy = { logsText ->
                copyText(context, logsText, "已复制完整安装日志 (${state.installLogs.size} 行)")
            },
        )
    }
}

@Composable
private fun DaemonServiceCard(
    state: CcSwitchUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onResetPassword: (String) -> Unit,
    onOpenWebConsole: () -> Unit,
) {
    val context = LocalContext.current
    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (state.isDaemonRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (state.isDaemonRunning) "CC-Switch 运行中" else "CC-Switch 未启动",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                StatusBadge(
                    text = "端口 ${state.servicePort}",
                    color = if (state.isDaemonRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (state.isDaemonRunning) {
                    "CC-Switch 正在监听本地端口与沙箱环境，负责反向代理、流式协议转换与各 CLI 多模型统一切源。"
                } else {
                    "中枢尚未运行。启动后将自动对沙箱环境进行实时检测，获取已安装 CLI、最新版本及模型源状态。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.isDaemonRunning) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "本地代理地址: http://127.0.0.1:${state.servicePort}${state.deviceLanIp?.let { " · 局域网: http://$it:${state.servicePort}" } ?: ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Web 控制台访问凭据 (HTTP Basic 认证)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            RuntimeTextButton(
                                onClick = {
                                    val creds = "${state.webUsername} / ${state.webPassword}"
                                    copyText(context, creds, "已复制控制台账号密码: $creds")
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text("一键复制", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "账号：${state.webUsername}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "密码：${state.webPassword}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "💡 外部浏览器访问提示登录时输入上方账号密码即可",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            RuntimeTextButton(
                                onClick = { onResetPassword("admin123") },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            ) {
                                Text("重置密码", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            if (state.isDaemonRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RuntimeFilledTonalButton(
                        onClick = onRestart,
                        enabled = !state.isOperating,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    ) {
                        RuntimeIcon(name = RuntimeIconName.Refresh, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "重启",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    RuntimeOutlinedButton(
                        onClick = onStop,
                        enabled = !state.isOperating,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    ) {
                        RuntimeIcon(name = RuntimeIconName.Close, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "停止",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    RuntimeButton(
                        onClick = {
                            val creds = "${state.webUsername} / ${state.webPassword}"
                            copyText(context, creds, "已复制登录凭据: $creds")
                            onOpenWebConsole()
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    ) {
                        RuntimeIcon(name = RuntimeIconName.OpenInNew, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "控制台",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            } else {
                RuntimeButton(
                    onClick = onStart,
                    enabled = !state.isOperating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isOperating) {
                        RuntimeCircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        RuntimeIcon(name = RuntimeIconName.Play, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("启动中枢服务 (端口 ${state.servicePort})")
                }
            }
        }
    }
}

@Composable
private fun TokenUsageCard(daemon: top.wkbin.taixu.core.model.CcSwitchDaemonStatus) {
    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "今日反代 Token 记账",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${daemon.todayTokens.requestCount} 次请求",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("输入 Token", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${daemon.todayTokens.promptTokens}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("输出 Token", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${daemon.todayTokens.completionTokens}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("总消耗 Tokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${daemon.todayTokens.totalTokens}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
@Composable
private fun AgentCard(
    agent: CcAgentState,
    isDaemonRunning: Boolean,
    isOperating: Boolean,
    isInstalling: Boolean,
    onSwitchProvider: () -> Unit,
    onInstall: () -> Unit,
    onUpgrade: () -> Unit,
    onShowLogs: () -> Unit,
) {
    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 1. 顶部标题与状态徽章行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = agent.type.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = agent.type.category,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            maxLines = 1,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 状态徽章（独立占据右上角，不与长标题挤压）
                when {
                    !isDaemonRunning -> {
                        StatusBadge(
                            text = "中枢未启动",
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    agent.isChecking -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RuntimeCircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "检测中...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    agent.hasUpdate -> {
                        StatusBadge(
                            text = "可升级",
                            color = Color(0xFFFF9800),
                        )
                    }
                    agent.installed -> {
                        StatusBadge(
                            text = "已是最新",
                            color = Color(0xFF4CAF50),
                        )
                    }
                    else -> {
                        StatusBadge(
                            text = "未安装",
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. 版本与环境信息表格容器（参照 CC-Switch 官方检查界面，左右对齐、从容大气）
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 当前版本
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "当前版本",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = when {
                                !isDaemonRunning -> "--"
                                agent.isChecking -> "检测中..."
                                agent.installed -> agent.currentVersion ?: "已安装"
                                else -> "未安装"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                !isDaemonRunning -> MaterialTheme.colorScheme.outline
                                agent.installed -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.outline
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // 最新版本
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "最新版本",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = when {
                                !isDaemonRunning -> "--"
                                agent.isChecking -> "加载中..."
                                else -> agent.latestVersion ?: agent.type.defaultLatestVersion.ifBlank { "--" }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (agent.hasUpdate) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // 当前绑定的模型提供商（若中枢已启动）
                    if (isDaemonRunning) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "绑定模型源",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = agent.activeProviderName ?: if (agent.installed) "默认网关/中转" else "未绑定",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = if (agent.activeProviderName != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // 3. 底部操作栏
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$ ${agent.type.defaultExecutable}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.width(8.dp))

                if (isDaemonRunning) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isInstalling) {
                            RuntimeOutlinedButton(
                                onClick = onShowLogs,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                RuntimeCircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("安装中...", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                            }
                        } else if (!agent.installed) {
                            RuntimeOutlinedButton(
                                onClick = onInstall,
                                enabled = !isOperating,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                RuntimeIcon(name = RuntimeIconName.Download, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("安装", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                            }
                        } else if (agent.hasUpdate) {
                            RuntimeFilledTonalButton(
                                onClick = onUpgrade,
                                enabled = !isOperating,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                RuntimeIcon(name = RuntimeIconName.ArrowUp, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("升级", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                            }
                        }

                        RuntimeButton(
                            onClick = onSwitchProvider,
                            enabled = !isOperating && !isInstalling,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("一键切源", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                        }
                    }
                } else {
                    Text(
                        text = "启动中枢后管理",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun AdvancedSyncCard(
    taiXuModels: List<AiModelEntity>,
    onImportModel: (AiModelEntity) -> Unit,
) {
    RuntimeCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "与太墟模型库深度互通",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "太墟内已配置 ${taiXuModels.size} 个模型服务商。点击下方卡片可直接一键导入为 CC-Switch 的纳管 Provider，无需重复填写 Key 与 Base URL。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            taiXuModels.take(4).forEach { model ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable { onImportModel(model) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Text("${model.provider} · ${model.model}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("导入至 CC-Switch", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchProviderDialog(
    agent: CcAgentState,
    providers: List<CcProviderProfile>,
    taiXuModels: List<AiModelEntity>,
    onDismiss: () -> Unit,
    onSelectProvider: (CcProviderProfile) -> Unit,
    onImportAndSelect: (AiModelEntity) -> Unit,
) {
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("为 ${agent.type.displayName} 切换模型源") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "已配置的 CC-Switch 预设源：",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                if (providers.isEmpty()) {
                    Text(
                        text = "暂未配置独立的 Provider，可直接从下方太墟模型库选择一键导入并绑定。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                providers.forEach { provider ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectProvider(provider) },
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(provider.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text("${provider.protocol} · ${provider.selectedModel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            RuntimeTextButton(onClick = { onSelectProvider(provider) }) {
                                Text("应用")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "从太墟已存模型一键绑定：",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                taiXuModels.forEach { model ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onImportAndSelect(model) },
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(model.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("${model.provider} · ${model.model}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            RuntimeTextButton(onClick = { onImportAndSelect(model) }) {
                                Text("同步并切换")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            RuntimeTextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun UpgradeVersionDialog(
    agent: CcAgentState,
    onDismiss: () -> Unit,
    onConfirmUpgrade: (String) -> Unit,
) {
    var versionInput by remember { mutableStateOf(agent.latestVersion ?: "") }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (agent.installed) "升级 ${agent.type.displayName}" else "安装 ${agent.type.displayName}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InfoRow(label = "当前版本", value = agent.currentVersion ?: "未安装")
                InfoRow(label = "推荐版本", value = agent.latestVersion ?: agent.type.defaultLatestVersion.ifBlank { "latest" })

                OutlinedTextField(
                    value = versionInput,
                    onValueChange = { versionInput = it },
                    label = { Text("目标版本 (如 1.0.0，留空即最新)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "确认后将在沙箱内通过包管理器执行相应版本部署与配置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            RuntimeButton(
                onClick = { onConfirmUpgrade(versionInput.trim()) },
            ) {
                Text(if (agent.installed) "确认升级" else "确认安装")
            }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

private fun copyText(context: Context, text: String, toast: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("TaiXu", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

@Composable
private fun InstallLogDialog(
    agentName: String,
    logs: List<String>,
    isOperating: Boolean,
    isError: Boolean,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val scrollState = rememberScrollState()
    androidx.compose.runtime.LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RuntimeIcon(RuntimeIconName.Terminal, modifier = Modifier.size(18.dp))
                    Text(
                        text = "$agentName 日志",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                when {
                    isOperating -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RuntimeCircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("执行中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    isError -> {
                        StatusBadge(text = "异常/失败", color = MaterialTheme.colorScheme.error)
                    }
                    logs.isNotEmpty() -> {
                        StatusBadge(text = "已完成", color = Color(0xFF4CAF50))
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    color = Color(0xFF161616),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 360.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (logs.isEmpty()) {
                            Text(
                                text = "暂无命令输出日志。点击安装或升级后，将在沙箱内实时捕获并输出详细构建过程...",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = Color(0xFF888888),
                                modifier = Modifier.padding(12.dp),
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollState)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                logs.forEach { line ->
                                    val textColor = when {
                                        line.contains("[-] ") || line.contains("ERR!") || line.contains("error:") || line.contains("FAILED") -> Color(0xFFEF5350)
                                        line.contains("[+] ") -> Color(0xFF81C784)
                                        line.contains("[!] ") || line.contains("WARN") -> Color(0xFFFFB74D)
                                        line.contains("[*] ") || line.contains("[步骤 ") -> Color(0xFF64B5F6)
                                        line.contains("[PATH] ") -> Color(0xFFBA68C8)
                                        else -> Color(0xFFDCDCDC)
                                    }
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp,
                                        ),
                                        color = textColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (logs.isNotEmpty()) {
                    RuntimeFilledTonalButton(
                        onClick = onClear,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("清空", style = MaterialTheme.typography.labelSmall)
                    }
                    RuntimeButton(
                        onClick = { onCopy(logs.joinToString("\n")) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        RuntimeIcon(name = RuntimeIconName.Copy, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("复制全部日志", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                } else {
                    RuntimeButton(onClick = onDismiss) {
                        Text("确定")
                    }
                }
            }
        },
        dismissButton = {
            if (logs.isNotEmpty()) {
                RuntimeTextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        },
    )
}

