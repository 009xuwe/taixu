package top.wkbin.taixu.core.model.workflow

object BuiltinWorkflows {
    val all: List<WorkflowDefinition>
        get() = listOf(releaseApk, buildDoctor, reverseAudit, atomicCommit)

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

    val reverseAudit = WorkflowDefinition(
        id = "android_reverse_audit",
        name = "APK 逆向审计准备",
        description = "对指定 APK 安全解包并生成可供智能体继续审计的源码目录。",
        category = "逆向",
        isBuiltin = true,
        trigger = WorkflowTrigger.Manual("/wf android_reverse_audit"),
        nodes = listOf(
            WorkflowNode("start", WorkflowNodeType.TRIGGER, "选择 APK", config = mapOf("requiredVariables" to "APK_PATH")),
            WorkflowNode("apktool", WorkflowNodeType.BASH_COMMAND, "资源解包", config = mapOf("command" to "apktool d -f ${'$'}{APK_PATH} -o ./workflow-output/apktool"), timeoutSeconds = 1200),
            WorkflowNode("jadx", WorkflowNodeType.BASH_COMMAND, "反编译源码", config = mapOf("command" to "jadx -d ./workflow-output/jadx ${'$'}{APK_PATH}"), timeoutSeconds = 1800),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "准备完成"),
        ),
        edges = chain("start", "apktool", "jadx", "done"),
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
            WorkflowNode("diff", WorkflowNodeType.BASH_COMMAND, "审阅变更", config = mapOf("command" to "git status --short && git diff --stat")),
            WorkflowNode("approve", WorkflowNodeType.HUMAN_APPROVAL, "确认本地提交", config = mapOf("requestedVariables" to "COMMIT_MESSAGE")),
            WorkflowNode("commit", WorkflowNodeType.BASH_COMMAND, "创建提交", config = mapOf("command" to "git add -A && git commit -m ${'$'}{COMMIT_MESSAGE}")),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "提交完成"),
        ),
        edges = chain("start", "diff", "approve", "commit", "done"),
    )
}
