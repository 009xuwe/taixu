package top.wkbin.taixu.core.model.workflow

object BuiltinWorkflows {
    val all: List<WorkflowDefinition>
        get() = listOf(releaseApk, installGeneratedApk, buildDoctor, buildRepair, reverseAudit, atomicCommit).map(WorkflowLayout::arrange)

    fun find(id: String): WorkflowDefinition? = all.firstOrNull { it.id == id }

    private fun chain(vararg ids: String): List<WorkflowEdge> = ids.toList().zipWithNext().mapIndexed { index, pair ->
        WorkflowEdge("edge_$index", pair.first, "success", pair.second)
    }

    val releaseApk = WorkflowDefinition(
        id = "release_apk_direct_install",
        name = "打包并安装 APK",
        description = "检查仓库、构建 ARM64 Release APK，并在确认后交给宿主安装。",
        category = "Android",
        isBuiltin = true,
        trigger = WorkflowTrigger.Manual("/wf release_apk_direct_install"),
        nodes = listOf(
            WorkflowNode("start", WorkflowNodeType.TRIGGER, "开始", canvasX = 40f, canvasY = 80f),
            WorkflowNode("git", WorkflowNodeType.BASH_COMMAND, "检查 Git 状态", config = mapOf("command" to "git status --short"), canvasX = 260f, canvasY = 80f),
            WorkflowNode("build", WorkflowNodeType.TAIXU_BUILD, "构建 Release APK", config = mapOf("projectType" to "android", "task" to "assembleRelease"), timeoutSeconds = 3600, canvasX = 500f, canvasY = 80f),
            WorkflowNode("approve", WorkflowNodeType.HUMAN_APPROVAL, "确认安装", description = "构建完成后确认是否安装到宿主设备。", canvasX = 740f, canvasY = 80f),
            WorkflowNode("install", WorkflowNodeType.HOST_ACTION, "安装 APK", config = mapOf("action" to "install-apk", "artifactFrom" to "build"), timeoutSeconds = 600, canvasX = 980f, canvasY = 80f),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "完成", canvasX = 1220f, canvasY = 80f),
        ),
        edges = chain("start", "git", "build", "approve", "install", "done"),
    )

    val buildDoctor = WorkflowDefinition(
        id = "mobile_build_doctor",
        name = "移动构建环境诊断",
        description = "运行 taixu-build doctor 与 analyze，输出可操作的兼容性报告。",
        category = "诊断",
        isBuiltin = true,
        trigger = WorkflowTrigger.Proactive("BUILD_FAILED", suggestionLabel = "诊断构建失败"),
        nodes = listOf(
            WorkflowNode("start", WorkflowNodeType.TRIGGER, "开始"),
            WorkflowNode("doctor", WorkflowNodeType.TAIXU_BUILD, "环境诊断", config = mapOf("mode" to "doctor"), timeoutSeconds = 900),
            WorkflowNode("analyze", WorkflowNodeType.TAIXU_BUILD, "分析工程", config = mapOf("mode" to "analyze"), timeoutSeconds = 900),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "查看诊断结果"),
        ),
        edges = chain("start", "doctor", "analyze", "done"),
    )

    val installGeneratedApk = WorkflowDefinition(
        id = "install_generated_apk",
        name = "安装最新生成的 APK",
        description = "使用构建产物路径，在人工确认后交给宿主安装。",
        category = "Android",
        isBuiltin = true,
        trigger = WorkflowTrigger.Proactive("APK_GENERATED", suggestionLabel = "安装刚生成的 APK"),
        nodes = listOf(
            WorkflowNode("start", WorkflowNodeType.TRIGGER, "读取 APK", config = mapOf("requiredVariables" to "APK_PATH")),
            WorkflowNode("approve", WorkflowNodeType.HUMAN_APPROVAL, "确认安装", description = "确认将刚生成的 APK 安装到宿主设备。", canvasX = 260f),
            WorkflowNode("install", WorkflowNodeType.HOST_ACTION, "安装 APK", config = mapOf("action" to "install-apk"), timeoutSeconds = 600, canvasX = 520f),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "安装请求已提交", canvasX = 780f),
        ),
        edges = chain("start", "approve", "install", "done"),
    )

    val reverseAudit = WorkflowDefinition(
        id = "android_reverse_audit",
        name = "APK 逆向审计",
        description = "解包、反编译后由智能体只读审计，输出带证据的风险报告；已有输出目录不会被覆盖。",
        category = "逆向",
        isBuiltin = true,
        trigger = WorkflowTrigger.Manual("/wf android_reverse_audit"),
        nodes = listOf(
            WorkflowNode("start", WorkflowNodeType.TRIGGER, "选择 APK", config = mapOf("requiredVariables" to "APK_PATH")),
            WorkflowNode("apktool", WorkflowNodeType.BASH_COMMAND, "资源解包", config = mapOf("command" to "apktool d ${'$'}{APK_PATH} -o ./workflow-output/apktool"), timeoutSeconds = 1200),
            WorkflowNode("jadx", WorkflowNodeType.BASH_COMMAND, "反编译源码", config = mapOf("command" to "jadx -d ./workflow-output/jadx ${'$'}{APK_PATH}"), timeoutSeconds = 1800),
            WorkflowNode("audit", WorkflowNodeType.AGENT_INFERENCE, "只读审计与报告", config = mapOf("prompt" to "只读审计工作区 workflow-output/apktool 和 workflow-output/jadx。先索引并搜索清单、导出组件、网络配置及敏感数据处理，再阅读相关源码。不得修改文件或运行被审计程序。输出 Markdown 报告，列出文件与行号、证据、影响、建议及无法验证的事项；不要把推测写成已验证漏洞。"), timeoutSeconds = 1800),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "审计报告"),
        ),
        edges = chain("start", "apktool", "jadx", "audit", "done"),
    )

    val atomicCommit = WorkflowDefinition(
        id = "git_atomic_smart_commit",
        name = "审阅并提交变更",
        description = "展示变更，等待人工确认后使用传入的提交说明创建本地提交；默认不推送。",
        category = "Git",
        isBuiltin = true,
        trigger = WorkflowTrigger.Manual("/wf git_atomic_smart_commit"),
        defaultVariables = mapOf("COMMIT_MESSAGE" to "chore: update workspace"),
        nodes = listOf(
            WorkflowNode("start", WorkflowNodeType.TRIGGER, "开始"),
            WorkflowNode("diff", WorkflowNodeType.BASH_COMMAND, "审阅变更", config = mapOf("command" to "git status --short && git diff --stat && git diff && git diff --cached")),
            WorkflowNode("suggest", WorkflowNodeType.AGENT_INFERENCE, "生成提交建议", config = mapOf("prompt" to "只读审阅以下 Git 变更，输出一条 Conventional Commit 建议和简短风险摘要。不得修改、暂存、提交或推送任何文件。变更：\n${'$'}{diff.output}")),
            WorkflowNode("approve", WorkflowNodeType.HUMAN_APPROVAL, "确认本地提交", config = mapOf("requestedVariables" to "COMMIT_MESSAGE")),
            WorkflowNode("commit", WorkflowNodeType.BASH_COMMAND, "创建提交", config = mapOf("command" to "git add -A && git commit -m ${'$'}{COMMIT_MESSAGE}")),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "提交完成"),
        ),
        edges = chain("start", "diff", "suggest", "approve", "commit", "done"),
    )

    val buildRepair = WorkflowDefinition(
        id = "assisted_build_repair",
        name = "确认后修复构建",
        description = "诊断失败原因，确认后委派智能体最小修复，再展示 Diff 并确认重新构建；不会自动提交或推送。",
        category = "诊断",
        isBuiltin = true,
        trigger = WorkflowTrigger.Manual("/wf assisted_build_repair"),
        nodes = listOf(
            WorkflowNode("start", WorkflowNodeType.TRIGGER, "输入构建错误", config = mapOf("requiredVariables" to "BUILD_ERROR")),
            WorkflowNode("diagnose", WorkflowNodeType.AGENT_INFERENCE, "只读诊断", config = mapOf("prompt" to "只读检查当前工作区，根据以下错误说明根因和最小修复计划。不得修改文件。\n${'$'}{BUILD_ERROR}"), timeoutSeconds = 900),
            WorkflowNode("approve", WorkflowNodeType.HUMAN_APPROVAL, "允许修复文件", description = "确认允许智能体按诊断结果修改当前工作区。请先确保重要修改已备份。"),
            WorkflowNode("repair", WorkflowNodeType.SUBAGENT_DELEGATE, "实施最小修复", config = mapOf("writePaths" to ".", "prompt" to "按以下诊断实施最小构建修复，保留用户已有修改；禁止删除项目、重置 Git、提交或推送。不能确定时停止并说明。诊断：\n${'$'}{diagnose.output}\n原错误：\n${'$'}{BUILD_ERROR}"), timeoutSeconds = 1800),
            WorkflowNode("diff", WorkflowNodeType.BASH_COMMAND, "检查修复 Diff", config = mapOf("command" to "git status --short && git diff && git diff --cached")),
            WorkflowNode("rebuildApproval", WorkflowNodeType.HUMAN_APPROVAL, "确认重新构建", description = "审阅修复后启动构建验证。拒绝不会回滚已完成的修改。"),
            WorkflowNode("build", WorkflowNodeType.TAIXU_BUILD, "验证构建", config = mapOf("task" to "assembleDebug"), timeoutSeconds = 3600),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "修复验证结果"),
        ),
        edges = chain("start", "diagnose", "approve", "repair", "diff", "rebuildApproval", "build", "done"),
    )
}
