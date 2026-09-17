package top.wkbin.taixu.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.taixu.ui.chat.ChatScreen
import top.wkbin.taixu.ui.chat.ChatViewModel
import top.wkbin.taixu.ui.components.MainDestination
import top.wkbin.taixu.ui.components.RuntimeBottomBar
import top.wkbin.taixu.ui.theme.LocalLiquidGlassBackdrop
import top.wkbin.taixu.ui.developer.DeveloperScreen
import top.wkbin.taixu.ui.developer.AdbLogcatScreen
import top.wkbin.taixu.ui.home.HomeScreen
import top.wkbin.taixu.ui.settings.AgentSettingsScreen
import top.wkbin.taixu.ui.settings.ModelEditorScreen
import top.wkbin.taixu.ui.settings.ModelProfilesScreen
import top.wkbin.taixu.ui.settings.LocalLlmScreen
import top.wkbin.taixu.ui.settings.SettingsScreen
import top.wkbin.taixu.ui.settings.SettingsViewModel
import top.wkbin.taixu.ui.iteration.CustomIterationScreen
import top.wkbin.taixu.ui.terminal.TerminalScreen
import top.wkbin.taixu.ui.browser.BrowserScreen
import top.wkbin.taixu.ui.workspace.CodeEditorScreen
import top.wkbin.taixu.ui.workspace.WorkspaceExplorerScreen
import top.wkbin.taixu.ui.workspace.WorkspaceScreen
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavEntryBuilder
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppDestination : NavKey

@Serializable data object HomeDestination : AppDestination
@Serializable data object AgentDestination : AppDestination
@Serializable data object WorkspaceDestination : AppDestination
@Serializable data object WorkshopSettingsDestination : AppDestination
@Serializable data object WorkshopEnvironmentSettingsDestination : AppDestination
@Serializable data object WorkshopSigningSettingsDestination : AppDestination
@Serializable data class WorkshopScriptEditorDestination(val type: String) : AppDestination
@Serializable data class WorkspaceExplorerDestination(val projectName: String, val initialPath: String = "") : AppDestination
@Serializable data class CodeEditorDestination(val projectName: String, val relativePath: String) : AppDestination
@Serializable data object SettingsDestination : AppDestination
@Serializable data object AgentEcoSettingsDestination : AppDestination
@Serializable data object LinuxEnvSettingsDestination : AppDestination
@Serializable data object AppearanceSettingsDestination : AppDestination
@Serializable data object SystemDevSettingsDestination : AppDestination
@Serializable data object AboutCommunityDestination : AppDestination
@Serializable data object SponsorDestination : AppDestination
@Serializable data object AgentSettingsDestination : AppDestination
@Serializable data object AgentSubagentSettingsDestination : AppDestination
@Serializable data object AgentSkillSettingsDestination : AppDestination
@Serializable data object McpSettingsDestination : AppDestination
@Serializable data object ToolCenterDestination : AppDestination
@Serializable data object CcSwitchDestination : AppDestination
@Serializable data class ToolDetailDestination(val toolId: String) : AppDestination
@Serializable data object DistroManagementDestination : AppDestination
@Serializable data object StorageMountSettingsDestination : AppDestination
@Serializable data object StorageUsageDestination : AppDestination
@Serializable data object AppManagementDestination : AppDestination
@Serializable data object EnvironmentVariableSettingsDestination : AppDestination
@Serializable data object SshSettingsDestination : AppDestination
@Serializable data object FtpSettingsDestination : AppDestination
@Serializable data object ModelProfilesDestination : AppDestination
@Serializable data object LocalLlmDestination : AppDestination
@Serializable data class ModelEditorDestination(val modelId: String? = null) : AppDestination
@Serializable data object QuickPhrasesDestination : AppDestination
@Serializable data object StatsDestination : AppDestination
@Serializable data object PermissionGuideDestination : AppDestination
@Serializable data object DeveloperDestination : AppDestination
@Serializable data object AdbLogcatDestination : AppDestination
@Serializable data object CustomIterationDestination : AppDestination
@Serializable data class TerminalDestination(val toolId: String = "", val project: String = "") : AppDestination
@Serializable data object BrowserDestination : AppDestination
@Serializable data class GitRepositoryDestination(val projectName: String) : AppDestination
@Serializable data class WorkflowDestination(
    val projectName: String = "",
    val workflowId: String? = null,
    val initialVariables: Map<String, String> = emptyMap(),
) : AppDestination

/**
 * 太墟核心导航分发系统
 * 采用 miuix-nav（连续栈深度驱动），为每个 Tab 独立维护持久回退栈与状态生命周期
 */
@Composable
fun TaiXuNavHost(
    globalNavigationBus: top.wkbin.taixu.core.common.navigation.GlobalNavigationBus? = null,
) {
    // Root tab entries are removed from composition when another tab becomes active. Keep the
    // conversation owner at the Activity scope so switching back to 智枢 does not rebuild Hilt's
    // graph, restore the latest session, and restart its initialization skeleton on every visit.
    val chatViewModel: ChatViewModel = hiltViewModel()
    // 浏览器引擎是全局单例：BrowserViewModel 同样挂到 Activity 作用域，
    // 使智枢内嵌浏览器面板与独立浏览器页共享同一份 tab/URL/共浏览状态。
    val browserViewModel: top.wkbin.taixu.ui.browser.BrowserViewModel = hiltViewModel()
    val browserUiState by browserViewModel.uiState.collectAsStateWithLifecycle()
    // SettingsViewModel owns dozens of eagerly shared DataStore/database streams and performs
    // repository initialization. Let the settings navigation graph share the Activity-scoped
    // instance instead of constructing that whole graph once for every nav entry.
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    // 显式传入路由超类型 AppDestination：rememberNavBackStack 的序列化 Saver 按 T 捕获，
    // 若推断为具体子类型，后续 push 其它子类会在状态保存时序列化失败
    val homeStack = rememberNavBackStack<AppDestination>(HomeDestination)
    val agentStack = rememberNavBackStack<AppDestination>(AgentDestination)
    val workspaceStack = rememberNavBackStack<AppDestination>(WorkspaceDestination)
    val settingsStack = rememberNavBackStack<AppDestination>(SettingsDestination)
    var pendingHealingTask by remember { mutableStateOf<HealingTask?>(null) }
    var selectedMain by rememberSaveable { mutableStateOf(MainDestination.Home) } // 默认进入太墟开辟主界
    var lastNavTime by remember { mutableLongStateOf(0L) }
    var navTransitionLockedUntil by remember { mutableLongStateOf(0L) }

    fun isNavTransitionLocked(): Boolean =
        System.currentTimeMillis() < navTransitionLockedUntil

    fun lockNavTransition() {
        navTransitionLockedUntil =
            System.currentTimeMillis() + NAV_TRANSITION_LOCK_MS
    }

    /** Programmatic stack mutation (bus / workflow) — still transition-locks. */
    fun NavBackStack.pushRaw(destination: NavKey, lock: Boolean = true) {
        if (isNavTransitionLocked()) return
        if (lastOrNull() == destination) return
        lastNavTime = System.currentTimeMillis()
        add(destination)
        if (lock) lockNavTransition()
    }

    LaunchedEffect(chatViewModel) {
        chatViewModel.workflowLaunchRequests.collect { request ->
            selectedMain = MainDestination.Agent
            agentStack.pushRaw(
                WorkflowDestination(request.projectName, request.workflowId, request.initialVariables),
            )
        }
    }

    LaunchedEffect(globalNavigationBus) {
        globalNavigationBus?.events?.collect { target ->
            when (target) {
                top.wkbin.taixu.core.common.navigation.AppNavigationTarget.AdbLogcat -> {
                    selectedMain = MainDestination.Settings
                    if (settingsStack.lastOrNull() != AdbLogcatDestination) {
                        if (settingsStack.lastOrNull() == SettingsDestination) {
                            settingsStack.pushRaw(SystemDevSettingsDestination, lock = false)
                        }
                        if (settingsStack.lastOrNull() == SystemDevSettingsDestination) {
                            settingsStack.pushRaw(AdbLogcatDestination)
                        } else if (settingsStack.lastOrNull() != AdbLogcatDestination) {
                            settingsStack.pushRaw(AdbLogcatDestination)
                        }
                    }
                    globalNavigationBus.clearLatest(target)
                }
            }
        }
    }

    val activeStack = when (selectedMain) {
        MainDestination.Home -> homeStack
        MainDestination.Agent -> agentStack
        MainDestination.Workspace -> workspaceStack
        MainDestination.Settings -> settingsStack
    }

    fun navigateMain(destination: MainDestination) {
        // Tab swaps are instantaneous (SaveableStateProvider swap below); do not transition-lock them.
        selectedMain = destination
    }

    fun NavBackStack.push(from: NavKey, destination: NavKey) {
        if (isNavTransitionLocked()) return
        val now = System.currentTimeMillis()
        if (now - lastNavTime < 120L) return
        if (lastOrNull() == from && lastOrNull() != destination) {
            lastNavTime = now
            add(destination)
            lockNavTransition()
        }
    }

    fun popBack() {
        if (isNavTransitionLocked()) return
        val now = System.currentTimeMillis()
        if (now - lastNavTime < 120L) return
        if (activeStack.size <= 1) return
        lastNavTime = now
        activeStack.removeLastOrNull()
        lockNavTransition()
    }

    @Composable
    fun GuardedEntry(
        destination: NavKey,
        content: @Composable () -> Unit,
    ) {
        val isActive = destination == activeStack.lastOrNull()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isActive) Modifier
                    else Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                )
        ) {
            content()
        }
    }

    val appEntryContent: NavEntryBuilder.() -> Unit = {
            entry<HomeDestination> {
                GuardedEntry(HomeDestination) {
                    HomeScreen(
                        onNavigate = ::navigateMain,
                        onOpenTerminal = { homeStack.push(HomeDestination, TerminalDestination()) },
                        onOpenToolCenter = { homeStack.push(HomeDestination, ToolCenterDestination) },
                    )
                }
            }
            entry<AgentDestination> {
                GuardedEntry(AgentDestination) {
                    LaunchedEffect(pendingHealingTask) {
                        pendingHealingTask?.let { task ->
                            chatViewModel.startHealingTask(task.title, task.prompt)
                            pendingHealingTask = null
                        }
                    }
                    ChatScreen(
                        viewModel = chatViewModel,
                        onNavigate = ::navigateMain,
                        // 内嵌终端面板非独立导航节点，无返回目标：隐藏顶栏返回箭头，避免点击无反馈
                        terminalPane = { project -> TerminalScreen(onBack = {}, project = project, showBackButton = false) },
                        // 内嵌浏览器面板：与独立浏览器页共享 Activity 级 BrowserViewModel，
                        // 手机端左右滑动切换对话/浏览器，宽屏双栏可切"终端/浏览器"
                        browserPane = { onExit ->
                            top.wkbin.taixu.ui.browser.BrowserPane(
                                viewModel = browserViewModel,
                                onExit = onExit,
                            )
                        },
                        browserActivityTick = browserUiState.activityTick,
                        browserBackPressed = { browserViewModel.handleBackImmediate() },
                        onOpenFile = { projectName, relativePath ->
                            agentStack.push(AgentDestination, CodeEditorDestination(projectName, relativePath))
                        },
                        onOpenRepository = { projectName ->
                            agentStack.push(AgentDestination, GitRepositoryDestination(projectName))
                        },
                    )
                }
            }
            entry<WorkspaceDestination> {
                GuardedEntry(WorkspaceDestination) {
                    WorkspaceScreen(
                        onNavigate = ::navigateMain,
                        onOpenExplorer = { projectName -> workspaceStack.push(WorkspaceDestination, WorkspaceExplorerDestination(projectName)) },
                        onOpenTerminal = { project -> workspaceStack.push(WorkspaceDestination, TerminalDestination(project = project)) },
                        onOpenToolCenter = { workspaceStack.push(WorkspaceDestination, ToolCenterDestination) },
                        onOpenWorkshopSettings = { workspaceStack.push(WorkspaceDestination, WorkshopSettingsDestination) },
                        onOpenWorkflows = { projectName -> workspaceStack.push(WorkspaceDestination, WorkflowDestination(projectName)) },
                    )
                }
            }
            entry<WorkflowDestination> { destination ->
                GuardedEntry(destination) {
                    top.wkbin.taixu.ui.workflow.WorkflowScreen(
                        projectName = destination.projectName,
                        initialWorkflowId = destination.workflowId,
                        initialVariables = destination.initialVariables,
                        onBack = ::popBack,
                    )
                }
            }
            entry<WorkshopSettingsDestination> {
                GuardedEntry(WorkshopSettingsDestination) {
                    top.wkbin.taixu.ui.workspace.WorkshopSettingsScreen(
                        onBack = ::popBack,
                        onOpenEnvironment = { workspaceStack.push(WorkshopSettingsDestination, WorkshopEnvironmentSettingsDestination) },
                        onOpenSigning = { workspaceStack.push(WorkshopSettingsDestination, WorkshopSigningSettingsDestination) },
                        onEditScript = { type -> workspaceStack.push(WorkshopSettingsDestination, WorkshopScriptEditorDestination(type.name)) },
                    )
                }
            }
            entry<WorkshopEnvironmentSettingsDestination> {
                GuardedEntry(WorkshopEnvironmentSettingsDestination) {
                    top.wkbin.taixu.ui.workspace.WorkshopEnvironmentSettingsScreen(onBack = ::popBack)
                }
            }
            entry<WorkshopSigningSettingsDestination> {
                GuardedEntry(WorkshopSigningSettingsDestination) {
                    top.wkbin.taixu.ui.workspace.WorkshopSigningScreen(onBack = ::popBack)
                }
            }
            entry<WorkshopScriptEditorDestination> { destination ->
                GuardedEntry(destination) {
                    top.wkbin.taixu.ui.workspace.WorkshopScriptEditorScreen(
                        type = top.wkbin.taixu.ui.workspace.WorkshopScriptType.valueOf(destination.type),
                        onBack = ::popBack,
                    )
                }
            }
            entry<WorkspaceExplorerDestination> { destination ->
                GuardedEntry(destination) {
                    WorkspaceExplorerScreen(
                        projectName = destination.projectName,
                        initialPath = destination.initialPath,
                        onBack = ::popBack,
                        onOpenFile = { relativePath ->
                            workspaceStack.push(destination, CodeEditorDestination(destination.projectName, relativePath))
                        },
                        onOpenTerminal = { project ->
                            workspaceStack.push(destination, TerminalDestination(project = project))
                        },
                    )
                }
            }
            entry<CodeEditorDestination> { destination ->
                GuardedEntry(destination) {
                    CodeEditorScreen(
                        projectName = destination.projectName,
                        relativePath = destination.relativePath,
                        onBack = ::popBack,
                    )
                }
            }
            entry<SettingsDestination> {
                GuardedEntry(SettingsDestination) {
                    SettingsScreen(
                        onNavigate = ::navigateMain,
                        onOpenAgentEco = { settingsStack.push(SettingsDestination, AgentEcoSettingsDestination) },
                        onOpenLinuxEnv = { settingsStack.push(SettingsDestination, LinuxEnvSettingsDestination) },
                        onOpenAppearance = { settingsStack.push(SettingsDestination, AppearanceSettingsDestination) },
                        onOpenSystemDev = { settingsStack.push(SettingsDestination, SystemDevSettingsDestination) },
                        onOpenAboutCommunity = { settingsStack.push(SettingsDestination, AboutCommunityDestination) },
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<AppearanceSettingsDestination> {
                GuardedEntry(AppearanceSettingsDestination) {
                    top.wkbin.taixu.ui.settings.AppearanceSettingsScreen(
                        onBack = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<AgentEcoSettingsDestination> {
                GuardedEntry(AgentEcoSettingsDestination) {
                    top.wkbin.taixu.ui.settings.AgentEcoSettingsScreen(
                        onBack = ::popBack,
                        onOpenModelProfiles = { settingsStack.push(AgentEcoSettingsDestination, ModelProfilesDestination) },
                        onOpenLocalLlm = { settingsStack.push(AgentEcoSettingsDestination, LocalLlmDestination) },
                        onOpenCcSwitch = { settingsStack.push(AgentEcoSettingsDestination, CcSwitchDestination) },
                        onOpenToolCenter = { settingsStack.push(AgentEcoSettingsDestination, ToolCenterDestination) },
                        onOpenAgentSettings = { settingsStack.push(AgentEcoSettingsDestination, AgentSettingsDestination) },
                        onOpenSubagentSettings = { settingsStack.push(AgentEcoSettingsDestination, AgentSubagentSettingsDestination) },
                        onOpenSkillSettings = { settingsStack.push(AgentEcoSettingsDestination, AgentSkillSettingsDestination) },
                        onOpenMcpSettings = { settingsStack.push(AgentEcoSettingsDestination, McpSettingsDestination) },
                        onOpenQuickPhrases = { settingsStack.push(AgentEcoSettingsDestination, QuickPhrasesDestination) },
                        onOpenStats = { settingsStack.push(AgentEcoSettingsDestination, StatsDestination) },
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<LinuxEnvSettingsDestination> {
                GuardedEntry(LinuxEnvSettingsDestination) {
                    top.wkbin.taixu.ui.settings.LinuxEnvironmentSettingsScreen(
                        onBack = ::popBack,
                        onOpenDistroManagement = { settingsStack.push(LinuxEnvSettingsDestination, DistroManagementDestination) },
                        onOpenStorageMounts = { settingsStack.push(LinuxEnvSettingsDestination, StorageMountSettingsDestination) },
                        onOpenStorageUsage = { settingsStack.push(LinuxEnvSettingsDestination, StorageUsageDestination) },
                        onOpenAppManagement = { settingsStack.push(LinuxEnvSettingsDestination, AppManagementDestination) },
                        onOpenEnvironmentVariables = { settingsStack.push(LinuxEnvSettingsDestination, EnvironmentVariableSettingsDestination) },
                        onOpenSshSettings = { settingsStack.push(LinuxEnvSettingsDestination, SshSettingsDestination) },
                        onOpenFtpSettings = { settingsStack.push(LinuxEnvSettingsDestination, FtpSettingsDestination) },
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<SystemDevSettingsDestination> {
                GuardedEntry(SystemDevSettingsDestination) {
                    top.wkbin.taixu.ui.settings.SystemDevSettingsScreen(
                        onBack = ::popBack,
                        onOpenDeveloper = { settingsStack.push(SystemDevSettingsDestination, DeveloperDestination) },
                        onOpenAdbLogcat = { settingsStack.push(SystemDevSettingsDestination, AdbLogcatDestination) },
                        onOpenCustomIteration = { settingsStack.push(SystemDevSettingsDestination, CustomIterationDestination) },
                        onOpenPermissionGuide = { settingsStack.push(SystemDevSettingsDestination, PermissionGuideDestination) },
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<PermissionGuideDestination> {
                GuardedEntry(PermissionGuideDestination) {
                    top.wkbin.taixu.ui.settings.permission.PermissionGuideScreen(
                        onBack = ::popBack,
                    )
                }
            }
            entry<AboutCommunityDestination> {
                GuardedEntry(AboutCommunityDestination) {
                    top.wkbin.taixu.ui.settings.AboutCommunityScreen(
                        onBack = ::popBack,
                        onOpenSponsor = { settingsStack.push(AboutCommunityDestination, SponsorDestination) },
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<SponsorDestination> {
                GuardedEntry(SponsorDestination) {
                    top.wkbin.taixu.ui.settings.SponsorScreen(onBack = ::popBack)
                }
            }
            entry<DistroManagementDestination> {
                GuardedEntry(DistroManagementDestination) {
                    top.wkbin.taixu.ui.settings.DistroManagementScreen(
                        onBack = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<AgentSettingsDestination> {
                GuardedEntry(AgentSettingsDestination) {
                    AgentSettingsScreen(
                        onBack = ::popBack,
                        category = top.wkbin.taixu.ui.settings.AgentSettingsCategory.EXECUTION,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<AgentSubagentSettingsDestination> {
                GuardedEntry(AgentSubagentSettingsDestination) {
                    AgentSettingsScreen(onBack = ::popBack, category = top.wkbin.taixu.ui.settings.AgentSettingsCategory.SUBAGENTS, viewModel = settingsViewModel)
                }
            }
            entry<AgentSkillSettingsDestination> {
                GuardedEntry(AgentSkillSettingsDestination) {
                    AgentSettingsScreen(onBack = ::popBack, category = top.wkbin.taixu.ui.settings.AgentSettingsCategory.SKILLS, viewModel = settingsViewModel)
                }
            }
            entry<McpSettingsDestination> {
                GuardedEntry(McpSettingsDestination) {
                    top.wkbin.taixu.ui.settings.McpSettingsScreen(
                        onBack = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<ToolCenterDestination> {
                GuardedEntry(ToolCenterDestination) {
                    top.wkbin.taixu.ui.settings.ToolCenterScreen(
                        onBack = ::popBack,
                        onLaunchPty = { toolId -> activeStack.push(ToolCenterDestination, TerminalDestination(toolId = toolId)) },
                        onOpenToolDetail = { toolId -> activeStack.push(ToolCenterDestination, ToolDetailDestination(toolId = toolId)) },
                        onStartAiHealing = { toolId, toolName, logs ->
                            val prompt = top.wkbin.taixu.ui.settings.ToolSelfHealingHelper.buildHealingPrompt(toolId, toolName, logs)
                            pendingHealingTask = HealingTask("🔧 自愈: $toolName", prompt)
                            selectedMain = MainDestination.Agent
                        },
                    )
                }
            }
            entry<CcSwitchDestination> {
                GuardedEntry(CcSwitchDestination) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    top.wkbin.taixu.ui.settings.CcSwitchScreen(
                        onBack = ::popBack,
                        onLaunchTerminal = { executable -> activeStack.push(CcSwitchDestination, TerminalDestination(toolId = executable)) },
                        onOpenBrowser = { url ->
                            val targetUrl = url.ifBlank { "http://127.0.0.1:19870" }
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(targetUrl)).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching {
                                context.startActivity(intent)
                            }.onFailure {
                                android.widget.Toast.makeText(context, "无法唤起外部浏览器: ${it.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
            entry<ToolDetailDestination> { destination ->
                GuardedEntry(destination) {
                    top.wkbin.taixu.ui.settings.ToolDetailScreen(
                        toolId = destination.toolId,
                        onBack = ::popBack,
                        onLaunchTerminal = { toolId -> activeStack.push(destination, TerminalDestination(toolId = toolId)) },
                        onStartAiHealing = { toolId, toolName, logs ->
                            val prompt = top.wkbin.taixu.ui.settings.ToolSelfHealingHelper.buildHealingPrompt(toolId, toolName, logs)
                            pendingHealingTask = HealingTask("🔧 自愈: $toolName", prompt)
                            selectedMain = MainDestination.Agent
                        },
                    )
                }
            }
            entry<StorageMountSettingsDestination> {
                GuardedEntry(StorageMountSettingsDestination) {
                    top.wkbin.taixu.ui.settings.StorageMountSettingsScreen(
                        onBack = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<StorageUsageDestination> {
                GuardedEntry(StorageUsageDestination) {
                    top.wkbin.taixu.ui.settings.StorageUsageScreen(onBack = ::popBack)
                }
            }
            entry<AppManagementDestination> {
                GuardedEntry(AppManagementDestination) {
                    top.wkbin.taixu.ui.settings.AppManagementScreen(onBack = ::popBack)
                }
            }
            entry<EnvironmentVariableSettingsDestination> {
                GuardedEntry(EnvironmentVariableSettingsDestination) {
                    top.wkbin.taixu.ui.settings.EnvironmentVariableSettingsScreen(
                        onBack = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<SshSettingsDestination> {
                GuardedEntry(SshSettingsDestination) {
                    top.wkbin.taixu.ui.settings.SshSettingsScreen(onBack = ::popBack)
                }
            }
            entry<FtpSettingsDestination> {
                GuardedEntry(FtpSettingsDestination) {
                    top.wkbin.taixu.ui.settings.FtpSettingsScreen(onBack = ::popBack)
                }
            }
            entry<ModelProfilesDestination> {
                GuardedEntry(ModelProfilesDestination) {
                    ModelProfilesScreen(
                        onBack = ::popBack,
                        onCreate = { settingsStack.push(ModelProfilesDestination, ModelEditorDestination()) },
                        onEdit = { modelId -> settingsStack.push(ModelProfilesDestination, ModelEditorDestination(modelId)) },
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<LocalLlmDestination> {
                GuardedEntry(LocalLlmDestination) {
                    LocalLlmScreen(
                        onBack = ::popBack,
                        onOpenEngine = { settingsStack.push(LocalLlmDestination, ToolDetailDestination("llama-cpp")) },
                    )
                }
            }
            entry<ModelEditorDestination> { destination ->
                GuardedEntry(destination) {
                    ModelEditorScreen(
                        modelId = destination.modelId,
                        onBack = ::popBack,
                        onSaved = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<QuickPhrasesDestination> {
                GuardedEntry(QuickPhrasesDestination) {
                    top.wkbin.taixu.ui.settings.QuickPhrasesScreen(
                        onBack = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<StatsDestination> {
                GuardedEntry(StatsDestination) {
                    top.wkbin.taixu.ui.settings.stats.StatsScreen(onBack = ::popBack)
                }
            }
            entry<DeveloperDestination> {
                GuardedEntry(DeveloperDestination) {
                    DeveloperScreen(onBack = ::popBack)
                }
            }
            entry<AdbLogcatDestination> {
                GuardedEntry(AdbLogcatDestination) {
                    AdbLogcatScreen(onBack = ::popBack)
                }
            }
            entry<CustomIterationDestination> {
                GuardedEntry(CustomIterationDestination) {
                    CustomIterationScreen(
                        onBack = ::popBack,
                        onNavigateToChat = { prompt ->
                            pendingHealingTask = HealingTask("🚀 自定义迭代", prompt)
                            selectedMain = MainDestination.Agent
                        },
                    )
                }
            }
            entry<TerminalDestination> { destination ->
                GuardedEntry(destination) {
                    TerminalScreen(onBack = ::popBack, project = destination.project)
                }
            }
            entry<BrowserDestination> {
                GuardedEntry(BrowserDestination) {
                    BrowserScreen(onBack = ::popBack, viewModel = browserViewModel)
                }
            }
            entry<GitRepositoryDestination> { destination ->
                GuardedEntry(destination) {
                    top.wkbin.taixu.ui.git.GitScreen(
                        projectName = destination.projectName,
                        onBack = ::popBack,
                    )
                }
            }
    }

    val density = LocalDensity.current
    val liquidGlassBackdrop = LocalLiquidGlassBackdrop.current
    val showLiquidBottomBar = liquidGlassBackdrop != null &&
        activeStack.size == 1 &&
        WindowInsets.ime.getBottom(density) == 0
    // Tab 切换会整体卸载 NavDisplay 的组合。用 hoisted SaveableStateHolder 按 Tab 键包裹：
    // 卸载时 miuix-nav 内部的 ViewModel 注册表键（rememberSaveable 随机键）与全部 entry
    // saveable 状态被 holder 保留，切回时还原 → 同一注册表键 → 从 Activity store 找回
    // NavEntryViewModelStores，各 Tab 的 entry ViewModel / 状态不丢失。
    val tabStateHolder = rememberSaveableStateHolder()
    val navSystemCornerRadius = rememberNavSystemCornerRadius()
    // 侧滑返回手势开关：关闭时回落到无 dismissDirection 的 MiuixDefault（仅按钮/系统返回）
    val navSwipeBackEnabled by settingsViewModel.navSwipeBackEnabled.collectAsStateWithLifecycle()
    Box(modifier = Modifier.fillMaxSize()) {
        // App background under NavDisplay so a rare uncovered frame never shows window black.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            // SaveableStateProvider(selectedMain)：换 Tab 即替换 NavDisplay（不做跨栈动画），
            // 同时把被卸载 Tab 的导航状态托管给 tabStateHolder。
            tabStateHolder.SaveableStateProvider(selectedMain) {
                NavDisplay(
                    backStack = activeStack,
                    modifier = Modifier.fillMaxSize(),
                    onBack = ::popBack,
                    transition = if (navSwipeBackEnabled) TaixuNavTransition else NavTransitions.MiuixDefault,
                    effects = NavDisplayEffects(
                        cornerClipRadius = navSystemCornerRadius,
                        blockInputDuringTransition = true,
                    ),
                    content = appEntryContent,
                )
            }
        }
        if (liquidGlassBackdrop != null) {
            // Keep the expensive glass layers composed while a secondary destination is open.
            // Recreating both backdrop render layers in the same frame as the root screen was
            // the main source of pop-navigation stalls. Moving the retained bar off-screen also
            // prevents its invisible click targets from intercepting the secondary page.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .zIndex(if (showLiquidBottomBar) 1f else -1f)
                    .graphicsLayer {
                        alpha = if (showLiquidBottomBar) 1f else 0f
                        translationY = if (showLiquidBottomBar) 0f else size.height
                    },
            ) {
                RuntimeBottomBar(
                    selected = selectedMain,
                    onNavigate = ::navigateMain,
                )
            }
        }
    }
}

private data class HealingTask(
    val title: String,
    val prompt: String,
)

/**
 * 导航转场：复用 NavTransitions.MiuixDefault 的几何（全宽滑入 + 1/4 宽视差 + 轻微透明衰减），
 * 并声明 dismissDirection 开启应用内侧滑返回手势——手指向右滑 1:1 跟手拖出顶层页面，
 * 松手按速度优先/位置兜底判定提交或回弹。根 entry（栈深 1）由宿主自动禁用该手势。
 */
private val TaixuNavTransition: NavTransition =
    object : NavTransition by NavTransitions.MiuixDefault {
        override val dismissDirection: NavSwipeDirection = NavSwipeDirection.LeftToRight
    }

/**
 * 导航转场期间锁定新导航的时长：miuix-nav 程序化 settle 为 500ms tween + 40ms 余量，
 * 防止转场中连续入栈导致的栈错乱与视觉跳变。
 */
private const val NAV_TRANSITION_LOCK_MS = 540L
