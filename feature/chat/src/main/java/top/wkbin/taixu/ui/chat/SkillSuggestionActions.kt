package top.wkbin.taixu.ui.chat

import java.util.UUID
import top.wkbin.taixu.core.model.AgentSkill
import top.wkbin.taixu.harness.SkillSuggestion

/**
 * 把技能进化建议转成 [AgentSkill]，口径与设置页新增自定义技能对齐：
 * 更新走既有自定义技能 id（内置技能不可覆盖），创建则生成 `custom_` 前缀 id。
 */
internal object SkillSuggestionActions {
    fun resolveExisting(
        suggestion: SkillSuggestion,
        createNew: Boolean,
        skills: List<AgentSkill>,
    ): AgentSkill? {
        if (createNew) return null
        val targetId = suggestion.targetSkillId?.takeIf { it.isNotBlank() } ?: return null
        return skills.firstOrNull { it.id == targetId }?.takeUnless { it.isBuiltin }
    }

    fun toCustomSkill(
        suggestion: SkillSuggestion,
        existing: AgentSkill?,
        newId: () -> String = { "custom_" + UUID.randomUUID().toString().take(8) },
    ): AgentSkill {
        val trimmedName = suggestion.skillName.trim()
        val trimmedPrompt = suggestion.systemPrompt.trim()
        val trigger = suggestion.triggerCommand?.trim()?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("/")) it else "/$it" }
        return if (existing != null && !existing.isBuiltin) {
            existing.copy(
                name = trimmedName,
                description = suggestion.description.trim().ifBlank { existing.description },
                systemPrompt = trimmedPrompt,
                triggerCommand = trigger ?: existing.triggerCommand,
            )
        } else {
            AgentSkill(
                id = newId(),
                name = trimmedName,
                description = suggestion.description.trim().ifBlank { "自定义技能" },
                systemPrompt = trimmedPrompt,
                triggerCommand = trigger,
                iconName = "Code",
                isEnabled = true,
                isBuiltin = false,
                category = "自定义",
            )
        }
    }
}
