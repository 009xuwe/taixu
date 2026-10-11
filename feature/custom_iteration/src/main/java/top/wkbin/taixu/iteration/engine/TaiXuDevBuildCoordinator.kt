package top.wkbin.taixu.iteration.engine

import kotlinx.coroutines.delay
import top.wkbin.taixu.runtime.shell.CommandResult
import top.wkbin.taixu.runtime.shell.ShellCommand

/**
 * TaiXuDevBuildCoordinator — 调度 GitHub Actions 云端构建与本地产物校验。
 *
 * 设计要点：
 * 1. 沙盒命令执行抽象为 [ShellRunner]：生产环境绑定 LinuxRuntime（遵循 runtime 模块
 *    ShellCommand/CommandResult 标准契约），纯 JVM 单元测试注入假执行器；
 * 2. 全流程 [runPipeline]：触发 workflow → 解析 run id → 轮询状态 → 下载 artifact →
 *    SHA-256 校验，每一步的状态迁移通过 [BuildStatus] 回调给 UI 层；
 * 3. UI 层只渲染状态，不做业务判断；失败细节（原始日志）只进 [BuildStatus.Failed.logs]，
 *    由调用方决定是否展示。
 */
class TaiXuDevBuildCoordinator(
    private val shellRunner: ShellRunner,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    /** 沙盒命令执行器抽象：生产绑定 LinuxRuntime，测试注入 Fake。 */
    fun interface ShellRunner {
        suspend fun execute(command: ShellCommand): CommandResult
    }

    sealed interface BuildStatus {
        data object Idle : BuildStatus
        data class Dispatching(val branch: String) : BuildStatus
        data class Running(val runId: Long, val statusText: String) : BuildStatus
        data class Downloading(val runId: Long, val progress: Int) : BuildStatus
        data class Success(val apkPath: String, val sha256: String) : BuildStatus
        data class Failed(val error: String, val logs: String? = null) : BuildStatus
    }

    /**
     * 构建相关的核心 CLI 指令模板（纯字符串生成，便于单元测试）。
     */
    object CliCommands {
        const val WORKFLOW = "taixudev-build.yml"
        const val ARTIFACT = "taixudev-apk"
        const val APK_NAME = "TaiXuDev-arm64-debug.apk"
        const val DEFAULT_OUTPUT_DIR = "/sdcard/Download"

        fun triggerWorkflow(workflowName: String = WORKFLOW, branch: String = "main"): String =
            "gh workflow run $workflowName --ref $branch"

        /** 兼容旧模板：实时跟踪（长阻塞，App 内请改用 [runStatus] 轮询）。 */
        fun watchWorkflow(runId: Long): String =
            "gh run watch $runId --exit-status"

        /**
         * 触发后解析最新 run，输出 "databaseId|status|conclusion"。
         * conclusion 为 null 时 jq tostring 输出 "null"，避免引号嵌套转义。
         */
        fun latestRun(workflowName: String = WORKFLOW, branch: String = "main"): String =
            "gh run list --workflow=$workflowName --branch $branch --limit 1 " +
                "--json databaseId,status,conclusion " +
                "--jq '.[0] | (.databaseId|tostring) + \"|\" + .status + \"|\" + (.conclusion|tostring)'"

        /** 轮询单个 run 状态，输出 "status|conclusion"。 */
        fun runStatus(runId: Long): String =
            "gh run view $runId --json status,conclusion " +
                "--jq '.status + \"|\" + (.conclusion|tostring)'"

        fun downloadArtifact(runId: Long, outputDir: String = DEFAULT_OUTPUT_DIR): String =
            "gh run download $runId -n $ARTIFACT -D $outputDir"

        /** 计算下载产物实际摘要（在 outputDir 内执行）。 */
        fun computeChecksum(outputDir: String = DEFAULT_OUTPUT_DIR): String =
            "sha256sum $APK_NAME"

        /** 读取 CI 生成的期望摘要文件（在 outputDir 内执行）。 */
        fun readChecksumFile(outputDir: String = DEFAULT_OUTPUT_DIR): String =
            "cat $APK_NAME.sha256"

        fun checkFailedLog(runId: Long): String =
            "gh run view $runId --log-failed"
    }

    /**
     * 端到端构建流水线：触发 → 等 run 注册 → 轮询 → 下载 → 校验。
     * 全程通过 [onStatus] 上报状态，最终返回终态（[BuildStatus.Success] 或 [BuildStatus.Failed]）。
     *
     * @param branch 触发构建的特性分支
     * @param outputDir APK 下载目录（沙盒内可见的手机下载目录）
     * @param pollIntervalMs 构建状态轮询间隔
     * @param maxPollAttempts 构建轮询上限（超过即超时失败）
     * @param maxRunWaitCycles 触发后等待 run 注册的轮询次数
     * @param runRegisterDelayMs run 注册轮询间隔
     */
    suspend fun runPipeline(
        branch: String,
        outputDir: String = CliCommands.DEFAULT_OUTPUT_DIR,
        pollIntervalMs: Long = 10_000L,
        maxPollAttempts: Int = 120,
        maxRunWaitCycles: Int = 12,
        runRegisterDelayMs: Long = 5_000L,
        onStatus: (BuildStatus) -> Unit,
    ): BuildStatus {
        val statusCallback: (BuildStatus) -> Unit = onStatus

        // 1. 触发工作流
        statusCallback(BuildStatus.Dispatching(branch))
        val trigger = execute(
            commandLine = CliCommands.triggerWorkflow(branch = branch),
            timeoutMs = 60_000L,
        )
        if (!trigger.isSuccess) {
            return BuildStatus.Failed(
                error = "触发工作流失败（exit=${trigger.exitCode}）：${trigger.stderr.trim().take(300)}",
                logs = trigger.stderr.take(2_000),
            )
        }

        // 2. 等待 run 注册（workflow_dispatch 后 run 需要数秒才会出现在 run list）
        var runId = -1L
        repeat(maxRunWaitCycles) {
            val list = execute(CliCommands.latestRun(branch = branch), timeoutMs = 60_000L)
            val parsed = parseLatestRun(list.stdout)
            if (parsed != null) {
                runId = parsed
                return@repeat
            }
            sleep(runRegisterDelayMs)
        }
        if (runId <= 0L) {
            return BuildStatus.Failed("未能获取构建 run：触发后长时间未在 $branch 分支上发现新的构建记录")
        }

        // 3. 轮询构建状态直至完成或超时
        var statusText = "queued"
        var conclusion = "null"
        for (attempt in 1..maxPollAttempts) {
            val status = execute(CliCommands.runStatus(runId), timeoutMs = 60_000L)
            if (status.isSuccess) {
                val parts = status.stdout.trim().split("|")
                statusText = parts.getOrNull(0)?.trim() ?: "unknown"
                conclusion = parts.getOrNull(1)?.trim() ?: "null"
                statusCallback(BuildStatus.Running(runId, statusText))
                if (statusText == "completed") break
            }
            if (attempt < maxPollAttempts) sleep(pollIntervalMs)
        }
        if (statusText != "completed") {
            return BuildStatus.Failed("构建超时未完成（最后状态：$statusText，run #$runId）")
        }
        if (conclusion != "success") {
            val logs = execute(CliCommands.checkFailedLog(runId), timeoutMs = 60_000L)
            return BuildStatus.Failed(
                error = "构建失败（conclusion=$conclusion，run #$runId）",
                logs = logs.stdout.take(4_000).ifBlank { null },
            )
        }

        // 4. 下载 artifact
        statusCallback(BuildStatus.Downloading(runId, 0))
        val download = execute(
            commandLine = CliCommands.downloadArtifact(runId, outputDir),
            timeoutMs = 600_000L,
        )
        if (!download.isSuccess) {
            return BuildStatus.Failed(
                error = "下载 artifact 失败（exit=${download.exitCode}）：${download.stderr.trim().take(300)}",
            )
        }

        // 5. SHA-256 校验：CI 记录的是仓库相对路径，不能直接 sha256sum -c，
        //    因此分别读取期望摘要与实际摘要后做字符串比对。
        val expected = execute(
            commandLine = CliCommands.readChecksumFile(outputDir),
            workingDirectory = outputDir,
            timeoutMs = 30_000L,
        ).stdout.trim().substringBefore(" ").take(64)
        val actual = execute(
            commandLine = CliCommands.computeChecksum(outputDir),
            workingDirectory = outputDir,
            timeoutMs = 120_000L,
        ).stdout.trim().substringBefore(" ").take(64)
        if (expected.length != 64 || actual.length != 64 || !expected.equals(actual, ignoreCase = true)) {
            return BuildStatus.Failed(
                error = "APK SHA-256 校验失败：期望 $expected，实际 $actual",
            )
        }

        val apkPath = "${outputDir.trimEnd('/')}/${CliCommands.APK_NAME}"
        val success = BuildStatus.Success(apkPath = apkPath, sha256 = actual)
        statusCallback(success)
        return success
    }

    private suspend fun execute(
        commandLine: String,
        workingDirectory: String = "/root",
        timeoutMs: Long = ShellCommand.DEFAULT_TIMEOUT_MS,
    ): CommandResult = shellRunner.execute(
        ShellCommand(
            commandLine = commandLine,
            workingDirectory = workingDirectory,
            timeoutMs = timeoutMs,
        ),
    )

    /** 解析 "databaseId|status|conclusion" 输出，非法或空输出返回 null。 */
    private fun parseLatestRun(stdout: String): Long? {
        val first = stdout.trim().lineSequence().firstOrNull() ?: return null
        val id = first.substringBefore("|").trim().toLongOrNull() ?: return null
        return if (id > 0L) id else null
    }
}
