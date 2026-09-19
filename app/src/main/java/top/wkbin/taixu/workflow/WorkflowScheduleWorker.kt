package top.wkbin.taixu.workflow

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import top.wkbin.taixu.core.model.workflow.WorkflowRunTrigger
import top.wkbin.taixu.harness.workflow.WorkflowRunManager
import top.wkbin.taixu.harness.workflow.WorkflowScheduleRepository
import top.wkbin.taixu.service.WorkflowForegroundService

/**
 * 定时计划到点执行：读计划 → 取定义 → 交给 WorkflowRunManager 启动（trigger=SCHEDULE）。
 * 运行启动后 Worker 立即返回成功；存活由前台服务接管（Application 联动启动）。
 * 运行时未就绪（如从未安装沙箱）会落一条 FAILED 历史并通知用户，绝不自动下载 RootFS。
 */
@HiltWorker
class WorkflowScheduleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val scheduleRepository: WorkflowScheduleRepository,
    private val runManager: WorkflowRunManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val scheduleId = inputData.getString(KEY_SCHEDULE_ID)
        if (scheduleId == null) {
            Log.w(TAG, "缺少 scheduleId 输入，丢弃本次触发")
            return Result.failure()
        }
        val schedule = scheduleRepository.findSchedule(scheduleId)
            ?: return Result.failure().also { Log.w(TAG, "计划不存在（可能已删除）：$scheduleId") }
        if (!schedule.enabled) return Result.success()

        val definition = scheduleRepository.findDefinition(schedule.workflowId)
        if (definition == null) {
            Log.w(TAG, "工作流定义缺失：${schedule.workflowId}")
            return Result.failure()
        }

        val result = runManager.start(
            definition = definition,
            variables = scheduleRepository.decodeVariables(schedule.variablesJson),
            workspacePath = schedule.workspacePath.ifBlank { "/workspace" },
            trigger = WorkflowRunTrigger.Schedule(scheduleId),
        )
        scheduleRepository.updateRunInfo(scheduleId, result.executionId, System.currentTimeMillis())
        if (!result.accepted) {
            Log.w(TAG, "定时计划 ${schedule.name} 启动失败：${result.failureReason}")
        }
        // 双保险：Application 的 running 联动可能慢于 Worker 结束，这里直接拉起前台保活
        if (result.accepted) {
            WorkflowForegroundService.start(applicationContext)
        }
        return Result.success()
    }

    companion object {
        const val KEY_SCHEDULE_ID = "key_schedule_id"
        private const val TAG = "WorkflowScheduleWorker"
    }
}
