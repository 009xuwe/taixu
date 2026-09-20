package top.wkbin.taixu.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.core.model.AgentSkill
import top.wkbin.taixu.harness.SkillSuggestion

class SkillSuggestionActionsTest {

    private val customSkill = AgentSkill(
        id = "custom_abc12345",
        name = "旧技能",
        description = "旧描述",
        systemPrompt = "旧正文",
        triggerCommand = "/old",
        isBuiltin = false,
        category = "自定义",
    )

    private val builtinSkill = AgentSkill(
        id = "git_workflow",
        name = "Git 敏捷工作流",
        description = "内置",
        systemPrompt = "内置正文",
        isBuiltin = true,
    )

    private fun suggestion(
        action: String = "create",
        targetSkillId: String? = null,
        trigger: String? = "review",
    ) = SkillSuggestion(
        id = "sug-1",
        createdAt = 1L,
        action = action,
        skillName = " 代码审查 ",
        description = " 沉淀本次审查步骤 ",
        systemPrompt = " 第二人称指导 ",
        triggerCommand = trigger,
        targetSkillId = targetSkillId,
        reason = "本次多步审查可复用",
    )

    @Test
    fun `createNew ignores target and always creates`() {
        val existing = SkillSuggestionActions.resolveExisting(
            suggestion(action = "update", targetSkillId = customSkill.id),
            createNew = true,
            skills = listOf(customSkill),
        )
        assertNull(existing)
        val skill = SkillSuggestionActions.toCustomSkill(suggestion(), existing = null) { "custom_newid" }
        assertEquals("custom_newid", skill.id)
        assertEquals("代码审查", skill.name)
        assertEquals("沉淀本次审查步骤", skill.description)
        assertEquals("第二人称指导", skill.systemPrompt)
        assertEquals("/review", skill.triggerCommand)
        assertTrue(skill.isEnabled)
        assertTrue(!skill.isBuiltin)
        assertEquals("自定义", skill.category)
    }

    @Test
    fun `update copies onto existing custom skill id`() {
        val existing = SkillSuggestionActions.resolveExisting(
            suggestion(action = "update", targetSkillId = customSkill.id),
            createNew = false,
            skills = listOf(customSkill, builtinSkill),
        )
        assertEquals(customSkill.id, existing?.id)
        val skill = SkillSuggestionActions.toCustomSkill(
            suggestion(action = "update", targetSkillId = customSkill.id, trigger = null),
            existing,
        )
        assertEquals(customSkill.id, skill.id)
        assertEquals("代码审查", skill.name)
        assertEquals("/old", skill.triggerCommand)
        assertTrue(!skill.isBuiltin)
    }

    @Test
    fun `update of builtin or missing target falls back to create`() {
        assertNull(
            SkillSuggestionActions.resolveExisting(
                suggestion(action = "update", targetSkillId = builtinSkill.id),
                createNew = false,
                skills = listOf(builtinSkill),
            ),
        )
        assertNull(
            SkillSuggestionActions.resolveExisting(
                suggestion(action = "update", targetSkillId = "missing"),
                createNew = false,
                skills = listOf(customSkill),
            ),
        )
        val created = SkillSuggestionActions.toCustomSkill(
            suggestion(action = "update", targetSkillId = builtinSkill.id),
            existing = null,
        ) { "custom_fallback" }
        assertEquals("custom_fallback", created.id)
        assertTrue(!created.isBuiltin)
    }

    @Test
    fun `blank description uses settings-style default on create`() {
        val skill = SkillSuggestionActions.toCustomSkill(
            SkillSuggestion(
                id = "sug-2",
                createdAt = 1L,
                action = "create",
                skillName = "新技能",
                description = "  ",
                systemPrompt = "正文",
            ),
            existing = null,
        ) { "custom_blank" }
        assertEquals("自定义技能", skill.description)
    }
}
