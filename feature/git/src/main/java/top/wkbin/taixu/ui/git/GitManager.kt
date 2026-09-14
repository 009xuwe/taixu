package top.wkbin.taixu.ui.git

import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.errors.CannotDeleteCurrentBranchException
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.api.errors.InvalidRemoteException
import org.eclipse.jgit.api.errors.RefAlreadyExistsException
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryBuilder
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

/** 当前路径不是 Git 仓库 */
class GitNotARepoException : IOException("NOT_A_GIT_REPO")

/** 操作结果：成功带摘要文案，失败带友好错误 */
sealed class GitOpResult {
    data class Ok(val message: String) : GitOpResult()
    data class Failed(val message: String) : GitOpResult()
}

/**
 * Git 操作服务（MGit 同款 JGit 技术栈）：
 * - 直接在宿主侧打开工作区目录的仓库，不依赖沙箱内安装 git；
 * - 显式注入空的 system/user 配置，规避 Android 上 user.home / /etc/gitconfig 缺失的问题；
 * - push/pull 走 HTTPS + Token（按 host 从 GitCredentialsStore 取）。
 */
@Singleton
class GitManager @Inject constructor(
    private val credentialsStore: GitCredentialsStore,
) {

    fun isRepository(hostPath: String): Boolean = runCatching {
        RepositoryBuilder().findGitDir(File(hostPath)).gitDir != null
    }.getOrDefault(false)

    private fun openRepository(hostPath: String): Git {
        val dir = File(hostPath)
        val gitDir = RepositoryBuilder().findGitDir(dir).gitDir
            ?: throw GitNotARepoException()
        // Android 无 /etc/gitconfig 与常规 user.home：JGit 对不存在的系统/用户配置会容忍跳过，
        // 提交身份在 ensureUserIdent 中写入仓库本地 config 兜底。
        return Git(
            RepositoryBuilder()
                .setGitDir(gitDir)
                .setMustExist(true)
                .build(),
        )
    }

    private inline fun <T> withGit(hostPath: String, block: (Git) -> T): T {
        val git = openRepository(hostPath)
        try {
            return block(git)
        } finally {
            git.close()
        }
    }

    /** pull 产生合并提交时需要提交者身份：本地 config 缺失则补默认值 */
    private fun ensureUserIdent(repository: Repository) {
        val config = repository.config
        var dirty = false
        if (config.getString("user", null, "name") == null) {
            config.setString("user", null, "name", "TaiXu")
            dirty = true
        }
        if (config.getString("user", null, "email") == null) {
            config.setString("user", null, "email", "taixu@device.local")
            dirty = true
        }
        if (dirty) config.save()
    }

    // ------------------------------------------------------------------
    // 概览：当前分支 / 分支列表 / 远程地址 / 领先落后 / 工作区状态
    // ------------------------------------------------------------------

    suspend fun loadOverview(hostPath: String): GitOverview = withContext(Dispatchers.IO) {
        withGit(hostPath) { git ->
            val repo = git.repository
            val currentBranch = repo.branch ?: ""
            val detached = repo.findRef(Constants.HEAD)?.isSymbolic != true
            val headId = repo.resolve(Constants.HEAD)

            val allRefs = runCatching { git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call() }
                .getOrDefault(emptyList())
            val local = mutableListOf<GitBranchInfo>()
            val remote = mutableListOf<GitBranchInfo>()
            for (ref: Ref in allRefs) {
                val commitId = ref.objectId?.name() ?: continue
                when {
                    ref.name.startsWith(Constants.R_HEADS) -> local += GitBranchInfo(
                        fullName = ref.name,
                        shortName = ref.name.removePrefix(Constants.R_HEADS),
                        isCurrent = !detached && ref.name == Constants.R_HEADS + currentBranch,
                        isRemote = false,
                        commitId = commitId,
                    )
                    ref.name.startsWith(Constants.R_REMOTES) -> remote += GitBranchInfo(
                        fullName = ref.name,
                        shortName = ref.name.removePrefix(Constants.R_REMOTES),
                        isCurrent = false,
                        isRemote = true,
                        commitId = commitId,
                    )
                }
            }

            val remoteUrl = runCatching {
                git.remoteList().call()
                    .firstOrNull { it.name == "origin" } ?: git.remoteList().call().firstOrNull()
            }.getOrNull()?.urIs?.firstOrNull()?.toString() ?: ""

            val status = runCatching { git.status().call() }.getOrNull()
            val uncommitted = status?.let {
                it.added.size + it.changed.size + it.removed.size + it.modified.size +
                    it.conflicting.size + it.missing.size
            } ?: 0

            var ahead = 0
            var behind = 0
            if (!detached && headId != null) {
                val tracking = BranchConfig(repo.config, currentBranch).trackingBranch
                val remoteId = tracking?.let { repo.resolve(it) }
                if (remoteId != null) {
                    RevWalk(repo).use { walk ->
                        walk.markStart(walk.parseCommit(headId))
                        walk.markUninteresting(walk.parseCommit(remoteId))
                        ahead = walk.count()
                    }
                    RevWalk(repo).use { walk ->
                        walk.markStart(walk.parseCommit(remoteId))
                        walk.markUninteresting(walk.parseCommit(headId))
                        behind = walk.count()
                    }
                }
            }

            GitOverview(
                currentBranch = if (detached) headId?.name()?.take(7) ?: "detached" else currentBranch,
                isDetached = detached,
                localBranches = local.sortedWith(compareByDescending<GitBranchInfo> { it.isCurrent }.thenBy { it.shortName }),
                remoteBranches = remote.sortedBy { it.shortName },
                remoteUrl = remoteUrl,
                aheadCount = ahead,
                behindCount = behind,
                uncommittedChanges = uncommitted,
                untrackedFiles = status?.untracked?.size ?: 0,
            )
        }
    }

    // ------------------------------------------------------------------
    // 提交记录树（MGit 风格泳道图）
    // ------------------------------------------------------------------

    suspend fun loadCommitGraph(hostPath: String, limit: Int = 500): List<GitCommitRow> =
        withContext(Dispatchers.IO) {
            withGit(hostPath) { git ->
                val repo = git.repository

                // 提交 → refs（分支/远程/标签）标注
                val labels = mutableMapOf<String, MutableList<GitRefLabel>>()
                fun putLabel(targetName: String, label: GitRefLabel) {
                    labels.getOrPut(targetName) { mutableListOf() } += label
                }
                repo.exactRef(Constants.HEAD)?.objectId?.let { putLabel(it.name(), GitRefLabel("HEAD", GitRefType.HEAD)) }
                runCatching { repo.refDatabase.getRefsByPrefix("refs/") }.getOrDefault(emptyList())
                    .forEach { ref ->
                        val target = ref.peeledObjectId ?: ref.objectId ?: return@forEach
                        val label = when {
                            ref.name.startsWith(Constants.R_HEADS) ->
                                GitRefLabel(ref.name.removePrefix(Constants.R_HEADS), GitRefType.BRANCH)
                            ref.name.startsWith(Constants.R_REMOTES) ->
                                GitRefLabel(ref.name.removePrefix(Constants.R_REMOTES), GitRefType.REMOTE)
                            ref.name.startsWith(Constants.R_TAGS) ->
                                GitRefLabel(ref.name.removePrefix(Constants.R_TAGS), GitRefType.TAG)
                            else -> null
                        }
                        if (label != null) putLabel(target.name(), label)
                    }

                val headId = repo.resolve(Constants.HEAD) ?: return@withGit emptyList()
                RevWalk(repo).use { walk ->
                    walk.sort(RevSort.TOPO)
                    walk.sort(RevSort.COMMIT_TIME_DESC)
                    walk.markStart(walk.parseCommit(headId))
                    // 所有本地分支也纳入起点，形成完整拓扑森林
                    runCatching { repo.refDatabase.getRefsByPrefix(Constants.R_HEADS) }
                        .getOrDefault(emptyList())
                        .forEach { ref -> ref.objectId?.let { walk.markStart(walk.parseCommit(it)) } }

                    val commits = mutableListOf<RevCommit>()
                    for (c in walk) {
                        commits += c
                        if (commits.size >= limit) break
                    }
                    buildGraphRows(commits, labels)
                }
            }
        }

    /** 泳道布局：经典 active-lane 算法，输出每行绘制几何信息（见 [GitCommitRow]）。 */
    private fun buildGraphRows(
        commits: List<RevCommit>,
        labels: Map<String, List<GitRefLabel>>,
    ): List<GitCommitRow> {
        val lanes = mutableListOf<RevCommit>()
        val rows = mutableListOf<GitCommitRow>()
        for (commit in commits) {
            if (commit !in lanes) lanes += commit
            val positions = mutableListOf<Int>()
            lanes.forEachIndexed { index, lane -> if (lane === commit) positions += index }
            val myLane = positions.first()
            val parents = commit.parents

            val preStraight = lanes.indices.filter { it !in positions }
            val mergeIn = positions.drop(1)

            // 收缩：多余泳道并入 myLane（合并点）
            mergeIn.sortedDescending().forEach { lanes.removeAt(it) }

            val parentLanes: List<Int>
            val postStraight: List<Int>
            if (parents.isEmpty()) {
                lanes.removeAt(myLane)
                parentLanes = emptyList()
                postStraight = lanes.indices.toList()
            } else {
                lanes[myLane] = parents[0]
                parents.drop(1).forEachIndexed { offset, parent ->
                    lanes.add(myLane + 1 + offset, parent)
                }
                parentLanes = (0 until parents.size).map { myLane + it }
                postStraight = lanes.indices.filter { it !in parentLanes }
            }

            rows += GitCommitRow(
                hash = commit.id.name(),
                shortHash = commit.abbreviate(7).name(),
                subject = commit.shortMessage,
                author = commit.authorIdent?.name.orEmpty(),
                commitTime = commit.commitTime * 1000L,
                refs = labels[commit.id.name()].orEmpty(),
                lane = myLane,
                preStraight = preStraight,
                mergeInPositions = mergeIn,
                parentLanes = parentLanes,
                postStraight = postStraight,
                laneCount = maxOf(lanes.size, myLane + 1),
            )
        }
        return rows
    }

    // ------------------------------------------------------------------
    // 分支操作
    // ------------------------------------------------------------------

    suspend fun checkout(hostPath: String, branch: GitBranchInfo): GitOpResult = withContext(Dispatchers.IO) {
        runCatching {
            withGit(hostPath) { git ->
                val repo = git.repository
                if (branch.isRemote) {
                    // 远程分支：在本地创建同名跟踪分支（等价 git checkout <name> 的 DWIM 行为）
                    val localName = branch.shortName.substringAfter('/')
                    git.checkout()
                        .setCreateBranch(true)
                        .setName(localName)
                        .setStartPoint(branch.fullName)
                        .call()
                    repo.config.setString("branch", localName, "remote", "origin")
                    repo.config.setString("branch", localName, "merge", "refs/heads/$localName")
                    repo.config.save()
                    GitOpResult.Ok("已切换到 $localName（跟踪 ${branch.shortName}）")
                } else {
                    git.checkout().setName(branch.shortName).call()
                    GitOpResult.Ok("已切换到 ${branch.shortName}")
                }
            }
        }.getOrElse { GitOpResult.Failed(friendlyError(it)) }
    }

    suspend fun createBranch(hostPath: String, name: String, fromCurrent: Boolean): GitOpResult =
        withContext(Dispatchers.IO) {
            runCatching {
                withGit(hostPath) { git ->
                    git.branchCreate()
                        .setName(name)
                        .setStartPoint(if (fromCurrent) Constants.HEAD else "origin/${git.repository.branch}")
                        .call()
                    GitOpResult.Ok("已创建分支 $name")
                }
            }.getOrElse { GitOpResult.Failed(friendlyError(it)) }
        }

    suspend fun deleteBranch(hostPath: String, branch: GitBranchInfo): GitOpResult = withContext(Dispatchers.IO) {
        runCatching {
            withGit(hostPath) { git ->
                val results = git.branchDelete().setBranchNames(branch.fullName).call()
                if (results.isEmpty()) {
                    GitOpResult.Failed("未能删除 ${branch.shortName}（可能包含未合并提交）")
                } else {
                    GitOpResult.Ok("已删除分支 ${branch.shortName}")
                }
            }
        }.getOrElse { GitOpResult.Failed(friendlyError(it)) }
    }

    // ------------------------------------------------------------------
    // 推送 / 拉取（HTTPS + Token）
    // ------------------------------------------------------------------

    private suspend fun credentialsProviderFor(hostPath: String): Pair<String?, UsernamePasswordCredentialsProvider?> =
        withContext(Dispatchers.IO) {
            val remoteUrl = runCatching {
                withGit(hostPath) { git ->
                    git.remoteList().call().firstOrNull { it.name == "origin" }?.urIs?.firstOrNull()?.toString() ?: ""
                }
            }.getOrDefault("")
            if (remoteUrl.isBlank()) return@withContext null to null
            val host = GitCredentialsStore.extractHost(remoteUrl)
            val cred = credentialsStore.get(host)
            host to cred?.let { UsernamePasswordCredentialsProvider(it.first, it.second) }
        }

    suspend fun push(hostPath: String, onProgress: (GitProgress) -> Unit): GitOpResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val (host, cp) = credentialsProviderFor(hostPath)
                withGit(hostPath) { git ->
                    val repo = git.repository
                    if (repo.findRef(Constants.HEAD)?.isSymbolic != true) {
                        return@withGit GitOpResult.Failed("当前处于分离头指针状态，请先切回分支再推送")
                    }
                    val branch = repo.branch ?: return@withGit GitOpResult.Failed("无法确定当前分支")
                    val results = git.push()
                        .setRemote("origin")
                        .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
                        .apply { cp?.let { setCredentialsProvider(it) } }
                        .setProgressMonitor(progressMonitor(onProgress))
                        .call()
                    val updates = results.flatMap { it.remoteUpdates }
                    val ok = updates.count { it.status == RemoteRefUpdate.Status.OK || it.status == RemoteRefUpdate.Status.UP_TO_DATE }
                    val rejected = updates.filter { it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE }
                    when {
                        updates.isEmpty() -> GitOpResult.Ok("推送完成（无变更）")
                        rejected.isEmpty() -> GitOpResult.Ok("已推送 $branch → origin（$ok 个引用）")
                        else -> GitOpResult.Failed("推送被拒绝：${rejected.first().status}" + rejected.firstOrNull()?.message.orEmpty().let { if (it.isBlank()) "" else " · $it" })
                    }
                }
            }.getOrElse { GitOpResult.Failed(friendlyError(it)) }
        }

    suspend fun pull(hostPath: String, onProgress: (GitProgress) -> Unit): GitOpResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val (host, cp) = credentialsProviderFor(hostPath)
                withGit(hostPath) { git ->
                    val repo = git.repository
                    ensureUserIdent(repo)
                    val branch = repo.branch ?: return@withGit GitOpResult.Failed("无法确定当前分支")
                    val tracking = BranchConfig(repo.config, branch).trackingBranch
                    val cmd = git.pull().setRemote("origin")
                    if (tracking == null) cmd.setRemoteBranchName(branch)
                    cp?.let { cmd.setCredentialsProvider(it) }
                    cmd.setProgressMonitor(progressMonitor(onProgress))
                    val result = cmd.call()

                    val merge = result.mergeResult
                    when (merge?.mergeStatus) {
                        org.eclipse.jgit.api.MergeResult.MergeStatus.ALREADY_UP_TO_DATE ->
                            GitOpResult.Ok("已是最新，无需拉取")
                        org.eclipse.jgit.api.MergeResult.MergeStatus.FAST_FORWARD ->
                            GitOpResult.Ok("已拉取并快进到 ${merge.newHead?.name()?.take(7)}")
                        org.eclipse.jgit.api.MergeResult.MergeStatus.MERGED ->
                            GitOpResult.Ok("已拉取并合并（产生合并提交）")
                        org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING ->
                            GitOpResult.Failed("拉取完成但存在冲突（${merge.conflicts.size} 个文件），请手动解决")
                        else -> GitOpResult.Ok("拉取完成")
                    }
                }
            }.getOrElse { GitOpResult.Failed(friendlyError(it)) }
        }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun progressMonitor(onProgress: (GitProgress) -> Unit): org.eclipse.jgit.lib.ProgressMonitor =
        object : org.eclipse.jgit.lib.ProgressMonitor {
            private var title = ""
            private var total = 0
            private var done = 0

            override fun start(totalTasks: Int) = Unit

            override fun beginTask(name: String, totalWork: Int) {
                title = name
                total = totalWork
                done = 0
                onProgress(GitProgress(name, 0, totalWork))
            }

            override fun update(completed: Int) {
                done += completed
                onProgress(GitProgress(title, done, total))
            }

            override fun endTask() = Unit

            override fun isCancelled(): Boolean = false

            override fun showDuration(enabled: Boolean) = Unit
        }

    companion object {
        fun friendlyError(e: Throwable): String = when (e) {
            is GitNotARepoException -> "NOT_A_GIT_REPO"
            is RepositoryNotFoundException -> "NOT_A_GIT_REPO"
            is CannotDeleteCurrentBranchException -> "不能删除当前所在的分支，请先切换到其他分支"
            is CheckoutConflictException -> "工作区有未提交改动与目标分支冲突，请先提交或暂存"
            is RefAlreadyExistsException -> "分支已存在"
            is InvalidRemoteException -> "未配置 origin 远程仓库，无法推送/拉取"
            is TransportException -> {
                val message = e.message.orEmpty()
                when {
                    message.contains("not authorized", true) || message.contains("Authentication", true) ->
                        "认证失败：请在凭据设置中配置该仓库 host 的 用户名 + Personal Access Token"
                    message.contains("Support for password authentication was removed", true) ->
                        "GitHub 已停用密码认证：请使用 Personal Access Token 作为密码"
                    message.contains("not found", true) ->
                        "远程仓库不存在或无访问权限（私有仓库需配置 Token）"
                    else -> message.ifBlank { e.toString() }
                }
            }
            else -> e.message?.ifBlank { null } ?: e.toString()
        }
    }
}
