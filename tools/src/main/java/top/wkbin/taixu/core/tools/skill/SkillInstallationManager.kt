package top.wkbin.taixu.core.tools.skill

import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.database.AgentSkillRepository
import top.wkbin.taixu.core.model.AgentSkill
import top.wkbin.taixu.core.model.skill.AuditLevel
import top.wkbin.taixu.core.model.skill.SecurityAuditReport
import top.wkbin.taixu.core.model.skill.SkillCompatibilityResult
import top.wkbin.taixu.core.model.skill.SkillPackage
import java.io.File
import java.io.InputStream

/**
 * 技能安装审查上下文（包含解构后的技能包、端侧静态安全审计报告与兼容性评估结果）。
 */
data class SkillInstallInspection(
    val packageId: String,
    val pkg: SkillPackage,
    val auditReport: SecurityAuditReport,
    val compatibilityResult: SkillCompatibilityResult,
) {
    val isBlocked: Boolean get() = auditReport.isBlocked
    val canProceed: Boolean get() = !isBlocked
}

class SkillSecurityBlockedException(
    val report: SecurityAuditReport,
    message: String = "技能包未能通过端侧静态安全审查，已被系统阻断安装",
) : SecurityException(message)

/**
 * 技能安全审查与安装事务管理器（连接 ClawHub 市场、静态审计引擎与太墟持久化仓储）。
 */
class SkillInstallationManager(
    private val packageParser: SkillPackageParser,
    private val inspector: SkillPackageInspector,
    private val compatibilityEvaluator: SkillCompatibilityEvaluator,
    private val clawHubClient: ClawHubClient,
    private val agentSkillRepository: AgentSkillRepository,
) {

    /**
     * 准备并审查来自 ClawHub 市场的技能包。
     */
    suspend fun prepareMarketSkill(skillId: String): AppResult<SkillInstallInspection> {
        val downloadRes = clawHubClient.downloadPackage(skillId)
        if (downloadRes !is AppResult.Success) {
            return AppResult.Failure(top.wkbin.taixu.core.common.result.AppError(top.wkbin.taixu.core.common.result.ErrorCode.DOWNLOAD, "下载市场技能包失败"))
        }

        return runCatching {
            val inspection = inspectZipBytes(downloadRes.data, fallbackId = skillId)
            AppResult.Success(inspection)
        }.getOrElse { err ->
            AppResult.Failure(top.wkbin.taixu.core.common.result.AppError(top.wkbin.taixu.core.common.result.ErrorCode.SECURITY, err.message ?: "审查失败", err))
        }
    }

    /**
     * 对 ZIP 字节流执行解构与端侧静态安全审计。
     */
    fun inspectZipBytes(zipBytes: ByteArray, fallbackId: String = "custom_skill"): SkillInstallInspection {
        val pkg = packageParser.parseFromZip(zipBytes, fallbackId)
        return inspectPackage(pkg)
    }

    /**
     * 对本地技能目录执行解构与端侧静态安全审计。
     */
    fun inspectDirectory(dir: File): SkillInstallInspection {
        val pkg = packageParser.parseFromDirectory(dir)
        return inspectPackage(pkg)
    }

    /**
     * 对解构后的技能包执行完整的安全与兼容性评估。
     */
    fun inspectPackage(pkg: SkillPackage): SkillInstallInspection {
        val auditReport = inspector.inspect(pkg)
        val compatibility = compatibilityEvaluator.evaluate(pkg)

        return SkillInstallInspection(
            packageId = pkg.manifest.id,
            pkg = pkg,
            auditReport = auditReport,
            compatibilityResult = compatibility,
        )
    }

    /**
     * 提交安装：将经安全审查通过的技能包安全解压落盘，并注册到 AgentSkillRepository。
     *
     * @param inspection 审查上下文
     * @param targetSkillsDir 宿主技能安装根目录（通常为 attachments/skills）
     * @param guestPrefix 沙箱内挂载路径前缀（通常为 /attachments/skills）
     */
    suspend fun commitInstallation(
        inspection: SkillInstallInspection,
        targetSkillsDir: File,
        guestPrefix: String = "/attachments/skills",
    ): AgentSkill {
        if (inspection.isBlocked) {
            throw SkillSecurityBlockedException(inspection.auditReport)
        }

        val pkg = inspection.pkg
        val skillId = pkg.manifest.id
        val targetDir = File(targetSkillsDir, skillId).apply { mkdirs() }

        try {
            // 安全落盘所有资产
            pkg.rawFiles.forEach { (relPath, bytes) ->
                val safePath = relPath.trimStart('/')
                val destFile = File(targetDir, safePath)
                // 再次防御路径逃逸
                val canonicalDest = destFile.canonicalPath
                val canonicalTarget = targetDir.canonicalPath
                if (!canonicalDest.startsWith(canonicalTarget + File.separator) && canonicalDest != canonicalTarget) {
                    throw SecurityException("检测到非法的文件写入逃逸: $relPath")
                }
                destFile.parentFile?.mkdirs()
                destFile.writeBytes(bytes)
            }

            val guestPath = guestPrefix.trimEnd('/') + "/$skillId"
            val composedPrompt = pkg.templates.composeSystemPrompt(resourceGuestPath = guestPath)

            val agentSkill = AgentSkill(
                id = "custom_$skillId",
                name = pkg.manifest.name,
                description = pkg.manifest.description,
                systemPrompt = composedPrompt,
                triggerCommand = pkg.manifest.triggerCommand,
                iconName = pkg.manifest.icon,
                isEnabled = true,
                isBuiltin = false,
                isImmutable = false,
                category = pkg.manifest.category,
                resourcePath = targetDir.absolutePath,
            )

            agentSkillRepository.addCustom(agentSkill)
            return agentSkill
        } catch (e: Throwable) {
            // 失败时安全回滚清理已写入的目录
            targetDir.deleteRecursively()
            throw e
        }
    }
}
