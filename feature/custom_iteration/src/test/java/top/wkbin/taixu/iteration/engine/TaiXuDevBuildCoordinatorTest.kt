package top.wkbin.taixu.iteration.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TaiXuDevBuildCoordinator 的 CLI 指令模板契约。
 */
class TaiXuDevBuildCoordinatorTest {

    @Test
    fun triggerWorkflowTargetsTaixuDevWorkflowOnGivenBranch() {
        assertEquals(
            "gh workflow run taixudev-build.yml --ref feature/demo",
            TaiXuDevBuildCoordinator.CliCommands.triggerWorkflow(branch = "feature/demo"),
        )
    }

    @Test
    fun triggerWorkflowDefaultsToMainBranch() {
        assertEquals(
            "gh workflow run taixudev-build.yml --ref main",
            TaiXuDevBuildCoordinator.CliCommands.triggerWorkflow(),
        )
    }

    @Test
    fun watchWorkflowUsesExitStatusSoFailuresSurface() {
        assertEquals(
            "gh run watch 12345 --exit-status",
            TaiXuDevBuildCoordinator.CliCommands.watchWorkflow("12345"),
        )
    }

    @Test
    fun downloadArtifactFetchesApkToDeviceDownloadDirByDefault() {
        assertEquals(
            "gh run download 12345 -n taixudev-apk -D /storage/emulated/0/Download",
            TaiXuDevBuildCoordinator.CliCommands.downloadArtifact("12345"),
        )
    }

    @Test
    fun downloadArtifactHonoursCustomOutputDir() {
        assertEquals(
            "gh run download 12345 -n taixudev-apk -D /tmp/out",
            TaiXuDevBuildCoordinator.CliCommands.downloadArtifact("12345", "/tmp/out"),
        )
    }

    @Test
    fun checkFailedLogScopesToFailingSteps() {
        assertEquals(
            "gh run view 12345 --log-failed",
            TaiXuDevBuildCoordinator.CliCommands.checkFailedLog("12345"),
        )
    }

    @Test
    fun dispatchingStatusCarriesBranch() {
        val status: TaiXuDevBuildCoordinator.BuildStatus =
            TaiXuDevBuildCoordinator.BuildStatus.Dispatching("feature/demo")

        assertEquals("feature/demo", status.branch)
    }
}
