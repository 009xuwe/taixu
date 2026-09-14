package top.wkbin.taixu.ui.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.wkbin.taixu.runtime.WorkspaceManager

/** 正在执行的远程/分支操作 */
enum class GitOperation { PUSH, PULL, CHECKOUT, CREATE_BRANCH, DELETE_BRANCH, REFRESH }

data class GitUiState(
    val projectName: String = "",
    val projectPath: String = "",
    val initialized: Boolean = false,
    val isRepository: Boolean = false,
    val loading: Boolean = false,
    val commitsLoading: Boolean = false,
    val overview: GitOverview? = null,
    val commits: List<GitCommitRow> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
    val operation: GitOperation? = null,
    val progress: GitProgress? = null,
    val credentialHosts: List<String> = emptyList(),
) {
    val busy: Boolean get() = operation != null
}

@HiltViewModel
class GitViewModel @Inject constructor(
    private val workspaceManager: WorkspaceManager,
    private val gitManager: GitManager,
    private val credentialsStore: GitCredentialsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GitUiState())
    val uiState: StateFlow<GitUiState> = _uiState.asStateFlow()

    private var lastProjectName: String? = null

    /** 由 GitScreen 在进入时绑定项目；重复绑定同一项目不重复初始化 */
    fun bind(projectName: String) {
        if (lastProjectName == projectName && _uiState.value.initialized) return
        lastProjectName = projectName
        viewModelScope.launch {
            _uiState.update { it.copy(projectName = projectName, initialized = true, loading = true) }
            val project = runCatching {
                workspaceManager.listProjects().firstOrNull { it.name == projectName }
            }.getOrNull()
            if (project == null) {
                _uiState.update { it.copy(loading = false, error = "未找到工作区项目：$projectName") }
                return@launch
            }
            val isRepo = gitManager.isRepository(project.path)
            _uiState.update { it.copy(projectPath = project.path, isRepository = isRepo, loading = false) }
            if (isRepo) refreshInternal(showLoading = false)
        }
    }

    fun refresh() {
        if (_uiState.value.isRepository) refreshInternal(showLoading = true)
    }

    private fun refreshInternal(showLoading: Boolean) {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.REFRESH, loading = showLoading, commitsLoading = true, error = null) }
            val overview = runCatching { gitManager.loadOverview(path) }
            val commits = runCatching { gitManager.loadCommitGraph(path) }
            val hosts = runCatching { credentialsStore.listHosts() }.getOrDefault(emptyList())
            _uiState.update { state ->
                state.copy(
                    operation = null,
                    loading = false,
                    commitsLoading = false,
                    overview = overview.getOrNull(),
                    commits = commits.getOrNull().orEmpty(),
                    error = (overview.exceptionOrNull() ?: commits.exceptionOrNull())?.let { GitManager.friendlyError(it) },
                    credentialHosts = hosts,
                )
            }
        }
    }

    fun checkout(branch: GitBranchInfo) {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.CHECKOUT, error = null) }
            val result = gitManager.checkout(path, branch)
            handleOpResult(result)
        }
    }

    fun createBranch(name: String) {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy || name.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.CREATE_BRANCH, error = null) }
            handleOpResult(gitManager.createBranch(path, name.trim(), fromCurrent = true))
        }
    }

    fun deleteBranch(branch: GitBranchInfo) {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.DELETE_BRANCH, error = null) }
            handleOpResult(gitManager.deleteBranch(path, branch))
        }
    }

    fun push() {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.PUSH, progress = null, error = null) }
            val result = gitManager.push(path) { progress ->
                _uiState.update { it.copy(progress = progress) }
            }
            handleOpResult(result)
        }
    }

    fun pull() {
        val path = _uiState.value.projectPath
        if (path.isBlank() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(operation = GitOperation.PULL, progress = null, error = null) }
            val result = gitManager.pull(path) { progress ->
                _uiState.update { it.copy(progress = progress) }
            }
            handleOpResult(result)
        }
    }

    fun saveCredentials(host: String, username: String, token: String, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { credentialsStore.save(host.trim().lowercase(), username, token) }
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            notice = "已保存 $host 的凭据",
                            credentialHosts = (state.credentialHosts + host.trim().lowercase()).distinct().sorted(),
                        )
                    }
                    onDone()
                }
                .onFailure { failure ->
                    _uiState.update { it.copy(error = "凭据保存失败：${failure.message}") }
                }
        }
    }

    fun clearNotice() = _uiState.update { it.copy(notice = null) }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private fun handleOpResult(result: GitOpResult) {
        when (result) {
            is GitOpResult.Ok -> _uiState.update { it.copy(operation = null, progress = null, notice = result.message) }
            is GitOpResult.Failed -> _uiState.update { it.copy(operation = null, progress = null, error = result.message) }
        }
        refreshInternal(showLoading = false)
    }
}
