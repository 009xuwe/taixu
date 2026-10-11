package top.wkbin.taixu.iteration.engine

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.iteration.engine.TaiXuDevBuildCoordinator.BuildStatus
import top.wkbin.taixu.iteration.engine.TaiXuDevBuildCoordinator.CliCommands
import top.wkbin.taixu.runtime.shell.CommandResult
import top.wkbin.taixu.runtime.shell.ShellCommand

/**
 * TaiXuDevBuildCoordinator 纯 JVM 单元测试：
 * 1. CLI 指令模板生成正确；
 * 2. runPipeline 状态机全路径（成功 / 触发失败 / run 未注册 / 构建失败 / 下载失败 / 摘要不匹配）。
 */
class TaiXuDevBuildCoordinatorTest {

    private companion object {
        // 真实 SHA-256 为 64 位十六进制；用合法长度样本以覆盖校验路径。
        const val SHA_OK = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"
        const val SHA_BAD = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
    }

    /** 按命令前缀路由的假执行器：模拟 gh CLI 在沙盒中的输出。 */
    private class FakeShellRunner : TaiXuDevBuildCoordinator.ShellRunner {
        val commands = mutableListOf<ShellCommand>()

        var triggerResult = CommandResult(0, "", "", 0)
        var latestRunResult = CommandResult(0, "42|completed|success", "", 0)
        val runStatusResults = ArrayDeque(listOf(CommandResult(0, "completed|success", "", 0)))
        var failedLogResult = CommandResult(0, "# Error log", "", 0)
        var downloadResult = CommandResult(0, "", "", 0)
        var checksumResult = CommandResult(0, "$SHA_OK  TaiXuDev-arm64-debug.apk", "", 0)
        var checksumFileResult = CommandResult(0, "$SHA_OK  output/TaiXuDev-arm64-debug.apk", "", 0)

        override suspend fun execute(command: ShellCommand): CommandResult {
            commands += command
            val line = command.commandLine
            return when {
                line.startsWith("gh workflow run") -> triggerResult
                line.startsWith("gh run list") -> latestRunResult
                line.startsWith("gh run view") && "--log-failed" in line -> failedLogResult
                line.startsWith("gh run view") -> runStatusResults.removeFirstOrNull()
                    ?: CommandResult(0, "completed|success", "", 0)
                line.startsWith("gh run download") -> downloadResult
                line.startsWith("sha256sum") -> checksumResult
                line.startsWith("cat ") -> checksumFileResult
                else -> CommandResult(0, "", "", 0)
            }
        }
    }

    private suspend fun TaiXuDevBuildCoordinator.runFast(
        branch: String = "feature/x",
        onStatus: (BuildStatus) -> Unit = {},
    ): BuildStatus = runPipeline(
        branch = branch,
        pollIntervalMs = 0L,
        maxPollAttempts = 5,
        maxRunWaitCycles = 3,
        runRegisterDelayMs = 0L,
        onStatus = onStatus,
    )

    @Test
    fun `cli command templates contain workflow branch artifact and output dir`() {
        val trigger = CliCommands.triggerWorkflow(branch = "feature/ci-build-coordinator")
        assertTrue(trigger.contains("gh workflow run taixudev-build.yml"))
        assertTrue(trigger.contains("--ref feature/ci-build-coordinator"))

        val latest = CliCommands.latestRun(branch = "feature/x")
        assertTrue(latest.contains("--workflow=taixudev-build.yml"))
        assertTrue(latest.contains("--branch feature/x"))
        assertTrue(latest.contains("databaseId"))

        assertTrue(CliCommands.runStatus(42L).contains("gh run view 42"))
        assertTrue(
            CliCommands.downloadArtifact(42L, "/sdcard/Download")
                .contains("gh run download 42 -n taixudev-apk -D /sdcard/Download"),
        )
        assertTrue(CliCommands.checkFailedLog(42L).contains("--log-failed"))
        assertEquals("TaiXuDev-arm64-debug.apk", CliCommands.APK_NAME)
    }

    @Test
    fun `pipeline happy path emits states and returns success with apk path and sha`() = runTest {
        val fake = FakeShellRunner().apply {
            runStatusResults.clear()
            runStatusResults += CommandResult(0, "in_progress|null", "", 0)
            runStatusResults += CommandResult(0, "completed|success", "", 0)
        }
        val coordinator = TaiXuDevBuildCoordinator(fake, sleep = { })

        val states = mutableListOf<BuildStatus>()
        val final = coordinator.runFast { states += it }

        assertTrue(final is BuildStatus.Success)
        final as BuildStatus.Success
        assertEquals("/sdcard/Download/TaiXuDev-arm64-debug.apk", final.apkPath)
        assertEquals(SHA_OK, final.sha256)

        // 状态迁移：Dispatching → Running → Downloading → Success
        assertTrue(states.first() is BuildStatus.Dispatching)
        assertTrue(states.any { it is BuildStatus.Running && it.runId == 42L })
        assertTrue(states.any { it is BuildStatus.Downloading })
        assertTrue(states.last() is BuildStatus.Success)

        // 触发命令携带分支
        assertTrue(fake.commands.first().commandLine.contains("--ref feature/x"))
    }

    @Test
    fun `pipeline fails when workflow dispatch fails`() = runTest {
        val fake = FakeShellRunner().apply {
            triggerResult = CommandResult(1, "", "gh: auth error", 0)
        }
        val coordinator = TaiXuDevBuildCoordinator(fake, sleep = { })

        val final = coordinator.runFast()

        assertTrue(final is BuildStatus.Failed)
        assertTrue((final as BuildStatus.Failed).error.contains("触发工作流失败"))
        // 触发失败后不应继续轮询/下载
        assertTrue(fake.commands.none { it.commandLine.startsWith("gh run download") })
    }

    @Test
    fun `pipeline fails when run never registers`() = runTest {
        val fake = FakeShellRunner().apply {
            latestRunResult = CommandResult(0, "", "", 0)
        }
        val coordinator = TaiXuDevBuildCoordinator(fake, sleep = { })

        val final = coordinator.runFast()

        assertTrue(final is BuildStatus.Failed)
        assertTrue((final as BuildStatus.Failed).error.contains("未能获取构建 run"))
    }

    @Test
    fun `pipeline fails with logs when build conclusion is failure`() = runTest {
        val fake = FakeShellRunner().apply {
            runStatusResults.clear()
            runStatusResults += CommandResult(0, "completed|failure", "", 0)
        }
        val coordinator = TaiXuDevBuildCoordinator(fake, sleep = { })

        val final = coordinator.runFast()

        assertTrue(final is BuildStatus.Failed)
        assertEquals("构建失败（conclusion=failure，run #42）", (final as BuildStatus.Failed).error)
        assertTrue((final as BuildStatus.Failed).logs.orEmpty().contains("Error log"))
    }

    @Test
    fun `pipeline fails when artifact download fails`() = runTest {
        val fake = FakeShellRunner().apply {
            downloadResult = CommandResult(1, "", "not found", 0)
        }
        val coordinator = TaiXuDevBuildCoordinator(fake, sleep = { })

        val final = coordinator.runFast()

        assertTrue(final is BuildStatus.Failed)
        assertTrue((final as BuildStatus.Failed).error.contains("下载 artifact 失败"))
    }

    @Test
    fun `pipeline fails on checksum mismatch`() = runTest {
        val fake = FakeShellRunner().apply {
            checksumResult = CommandResult(0, "$SHA_BAD  TaiXuDev-arm64-debug.apk", "", 0)
        }
        val coordinator = TaiXuDevBuildCoordinator(fake, sleep = { })

        val final = coordinator.runFast()

        assertTrue(final is BuildStatus.Failed)
        val failed = final as BuildStatus.Failed
        assertTrue(failed.error.contains("SHA-256 校验失败"))
        assertTrue(failed.error.contains(SHA_OK))
        assertTrue(failed.error.contains(SHA_BAD))
    }

    @Test
    fun `checksum commands run inside output dir`() = runTest {
        val fake = FakeShellRunner()
        val coordinator = TaiXuDevBuildCoordinator(fake, sleep = { })

        coordinator.runFast()

        val checksumCall = fake.commands.last { it.commandLine.startsWith("sha256sum") }
        assertEquals("/sdcard/Download", checksumCall.workingDirectory)
        val catCall = fake.commands.last { it.commandLine.startsWith("cat ") }
        assertEquals("/sdcard/Download", catCall.workingDirectory)
    }
}
