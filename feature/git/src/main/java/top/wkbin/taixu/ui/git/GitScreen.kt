package top.wkbin.taixu.ui.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.taixu.feature.git.R
import top.wkbin.taixu.ui.components.RuntimeAlertDialog
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeCircularProgressIndicator
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconButton
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeTextButton

/**
 * 分支管理（参考 MGit）：
 * 分支列表/切换/新建/删除 + 多色提交记录树（哈希/作者/时间）+ HTTPS Token 推送拉取。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(
    projectName: String,
    onBack: () -> Unit,
    viewModel: GitViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(projectName) { viewModel.bind(projectName) }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearNotice()
        }
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showCreateBranch by remember { mutableStateOf(false) }
    var showCredentials by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<GitBranchInfo?>(null) }

    // 与 TerminalScreen/CodeEditorScreen 对齐：不透明背景，避免转场动画期间下层页面透出
    // （无背景时转场中下层 ChatScreen 仍全量渲染并可见，既视觉怪异又掉帧）
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RuntimeIconButton(onClick = onBack) {
                RuntimeIcon(RuntimeIconName.Back, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.fgit_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = projectName,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RuntimeIconButton(onClick = { viewModel.refresh() }) {
                RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(19.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RuntimeIconButton(onClick = { showCredentials = true }) {
                RuntimeIcon(RuntimeIconName.Key, Modifier.size(19.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        when {
            state.loading && state.overview == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RuntimeCircularProgressIndicator(Modifier.size(26.dp))
                    Text(
                        stringResource(R.string.fgit_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            !state.isRepository -> NotARepoContent()

            else -> {
                val overview = state.overview
                if (overview != null) {
                    RepoHeaderCard(
                        overview = overview,
                        busy = state.busy,
                        operation = state.operation,
                        progress = state.progress,
                        onPull = viewModel::pull,
                        onPush = viewModel::push,
                    )
                }

                state.error?.let { errorText ->
                    RuntimeCard(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentPadding = PaddingValues(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                    ) {
                        Text(
                            errorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(stringResource(R.string.fgit_tab_branches)) },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(R.string.fgit_tab_commits)) },
                    )
                }

                when (selectedTab) {
                    0 -> BranchesTab(
                        state = state,
                        onCheckout = viewModel::checkout,
                        onDelete = { deleteTarget = it },
                        onCreate = { showCreateBranch = true },
                    )
                    else -> CommitsTab(state)
                }
            }
        }
        }
    }

    if (showCreateBranch) {
        CreateBranchDialog(
            currentBranch = state.overview?.currentBranch.orEmpty(),
            onDismiss = { showCreateBranch = false },
            onCreate = { name ->
                showCreateBranch = false
                viewModel.createBranch(name)
            },
        )
    }

    if (showCredentials) {
        CredentialsDialog(
            remoteUrl = state.overview?.remoteUrl.orEmpty(),
            savedHosts = state.credentialHosts,
            onDismiss = { showCredentials = false },
            onSave = { host, user, token ->
                viewModel.saveCredentials(host, user, token) { showCredentials = false }
            },
        )
    }

    deleteTarget?.let { branch ->
        RuntimeAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.fgit_delete_branch_title)) },
            text = { Text(stringResource(R.string.fgit_delete_branch_confirm, branch.shortName)) },
            confirmButton = {
                RuntimeTextButton(onClick = {
                    viewModel.deleteBranch(branch)
                    deleteTarget = null
                }) { Text(stringResource(R.string.fgit_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                RuntimeTextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.fgit_cancel)) }
            },
        )
    }
}

// ----------------------------------------------------------------------
// 非仓库空态
// ----------------------------------------------------------------------

@Composable
private fun NotARepoContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            RuntimeIcon(
                RuntimeIconName.GitBranch,
                Modifier.size(44.dp),
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Text(
                stringResource(R.string.fgit_not_repo_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.fgit_not_repo_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

// ----------------------------------------------------------------------
// 仓库概览卡片：分支 + 领先落后 + 远程地址 + 推送/拉取
// ----------------------------------------------------------------------

@Composable
private fun RepoHeaderCard(
    overview: GitOverview,
    busy: Boolean,
    operation: GitOperation?,
    progress: GitProgress?,
    onPull: () -> Unit,
    onPush: () -> Unit,
) {
    RuntimeCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuntimeIcon(RuntimeIconName.GitBranch, Modifier.size(17.dp), MaterialTheme.colorScheme.primary)
                Text(
                    text = overview.currentBranch,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (overview.isDetached) {
                    MiniBadge(stringResource(R.string.fgit_detached), MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                if (overview.aheadCount > 0) {
                    MiniBadge("↑${overview.aheadCount}", Color(0xFF2E9E5B))
                }
                if (overview.behindCount > 0) {
                    MiniBadge("↓${overview.behindCount}", Color(0xFF3F8FFF))
                }
                if (overview.uncommittedChanges > 0 || overview.untrackedFiles > 0) {
                    MiniBadge(
                        stringResource(
                            R.string.fgit_dirty_count,
                            overview.uncommittedChanges + overview.untrackedFiles,
                        ),
                        Color(0xFFB25E00),
                    )
                }
            }

            if (overview.remoteUrl.isNotBlank()) {
                Text(
                    text = overview.remoteUrl,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = stringResource(R.string.fgit_no_remote),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (busy && (operation == GitOperation.PUSH || operation == GitOperation.PULL)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    progress?.let { p ->
                        Text(
                            text = p.title + if (p.total > 0) "  ${p.completed}/${p.total}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (p.total > 0) {
                            LinearProgressIndicator(
                                progress = { (p.completed.toFloat() / p.total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                        }
                    } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip(
                    icon = RuntimeIconName.Download,
                    label = stringResource(R.string.fgit_pull),
                    enabled = !busy && overview.remoteUrl.isNotBlank(),
                    tint = Color(0xFF3F8FFF),
                    onClick = onPull,
                    modifier = Modifier.weight(1f),
                )
                ActionChip(
                    icon = RuntimeIconName.ArrowUp,
                    label = stringResource(R.string.fgit_push),
                    enabled = !busy && overview.remoteUrl.isNotBlank(),
                    tint = Color(0xFF2E9E5B),
                    onClick = onPush,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ActionChip(
    icon: RuntimeIconName,
    label: String,
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = if (enabled) tint.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            RuntimeIcon(icon, Modifier.size(15.dp), if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ----------------------------------------------------------------------
// 分支页签
// ----------------------------------------------------------------------

@Composable
private fun BranchesTab(
    state: GitUiState,
    onCheckout: (GitBranchInfo) -> Unit,
    onDelete: (GitBranchInfo) -> Unit,
    onCreate: () -> Unit,
) {
    val overview = state.overview ?: return
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.fgit_local_branches, overview.localBranches.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    onClick = onCreate,
                    enabled = !state.busy,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.Plus, Modifier.size(13.dp), MaterialTheme.colorScheme.primary)
                        Text(
                            stringResource(R.string.fgit_new_branch),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        if (overview.localBranches.isEmpty()) {
            item { EmptyHint(stringResource(R.string.fgit_no_branches)) }
        } else {
            items(overview.localBranches, key = { it.fullName }) { branch ->
                BranchRow(
                    branch = branch,
                    busy = state.busy,
                    isCheckoutLoading = state.operation == GitOperation.CHECKOUT,
                    onCheckout = { onCheckout(branch) },
                    onDelete = { onDelete(branch) },
                )
            }
        }

        if (overview.remoteBranches.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.fgit_remote_branches, overview.remoteBranches.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }
            items(overview.remoteBranches, key = { it.fullName }) { branch ->
                BranchRow(
                    branch = branch,
                    busy = state.busy,
                    isCheckoutLoading = false,
                    onCheckout = { onCheckout(branch) },
                    onDelete = null,
                )
            }
        }
    }
}

@Composable
private fun BranchRow(
    branch: GitBranchInfo,
    busy: Boolean,
    isCheckoutLoading: Boolean,
    onCheckout: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val tint = if (branch.isCurrent) {
        MaterialTheme.colorScheme.primary
    } else if (branch.isRemote) {
        Color(0xFF3F8FFF)
    } else {
        Color(0xFF7C4DFF)
    }
    RuntimeCard(
        containerColor = if (branch.isCurrent) tint.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = if (branch.isCurrent) tint.copy(alpha = 0.55f) else Color.Transparent,
        contentPadding = PaddingValues(10.dp),
        onClick = if (!busy && !branch.isCurrent) onCheckout else null,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RuntimeIcon(
                if (branch.isRemote) RuntimeIconName.Globe else RuntimeIconName.GitBranch,
                Modifier.size(17.dp),
                tint,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        branch.shortName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (branch.isCurrent) MiniBadge(stringResource(R.string.fgit_current), tint)
                }
                Text(
                    text = branch.commitId.take(7),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isCheckoutLoading && !branch.isCurrent) {
                RuntimeCircularProgressIndicator(Modifier.size(16.dp))
            } else if (onDelete != null && !branch.isCurrent && !busy) {
                RuntimeIconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                    RuntimeIcon(RuntimeIconName.Trash, Modifier.size(15.dp), MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// 提交记录页签
// ----------------------------------------------------------------------

@Composable
private fun CommitsTab(state: GitUiState) {
    if (state.commitsLoading && state.commits.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            RuntimeCircularProgressIndicator(Modifier.size(24.dp))
        }
        return
    }
    if (state.commits.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyHint(stringResource(R.string.fgit_no_commits))
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 32.dp),
    ) {
        items(state.commits, key = { it.hash }) { row ->
            CommitGraphRow(row)
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    RuntimeCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ----------------------------------------------------------------------
// 对话框
// ----------------------------------------------------------------------

@Composable
private fun CreateBranchDialog(
    currentBranch: String,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fgit_new_branch)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (currentBranch.isBlank()) {
                        stringResource(R.string.fgit_create_branch_hint)
                    } else {
                        stringResource(R.string.fgit_create_branch_from, currentBranch)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim().take(64) },
                    label = { Text(stringResource(R.string.fgit_branch_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            RuntimeTextButton(
                onClick = { if (name.isNotBlank()) onCreate(name) },
            ) { Text(stringResource(R.string.fgit_create)) }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) { Text(stringResource(R.string.fgit_cancel)) }
        },
    )
}

@Composable
private fun CredentialsDialog(
    remoteUrl: String,
    savedHosts: List<String>,
    onDismiss: () -> Unit,
    onSave: (host: String, username: String, token: String) -> Unit,
) {
    val defaultHost = remember(remoteUrl) {
        if (remoteUrl.isBlank()) "" else GitCredentialsStore.extractHost(remoteUrl)
    }
    var host by remember(defaultHost) { mutableStateOf(defaultHost) }
    var username by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fgit_credentials_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.fgit_credentials_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (remoteUrl.isNotBlank()) {
                    Text(
                        stringResource(R.string.fgit_credentials_remote, remoteUrl),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim().take(100) },
                    label = { Text(stringResource(R.string.fgit_host)) },
                    placeholder = { Text("github.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.take(100) },
                    label = { Text(stringResource(R.string.fgit_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.take(500) },
                    label = { Text(stringResource(R.string.fgit_token)) },
                    placeholder = { Text(stringResource(R.string.fgit_token_placeholder)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (savedHosts.isNotEmpty()) {
                    Text(
                        stringResource(R.string.fgit_saved_hosts, savedHosts.joinToString("、")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            RuntimeTextButton(
                onClick = { if (host.isNotBlank() && username.isNotBlank() && token.isNotBlank()) onSave(host, username, token) },
            ) { Text(stringResource(R.string.fgit_save)) }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) { Text(stringResource(R.string.fgit_cancel)) }
        },
    )
}

@Composable
private fun MiniBadge(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}
