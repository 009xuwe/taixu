# PR 说明：修复沙箱 / 凭据 / FTP 链路 8 处安全缺陷

> **基线**：`wkbin/taixu` @ `f82a44f7`（Release v0.16.0）
> **改动**：6 个文件，+85 / −9 行
> **核验方式**：每一条都用 `git show upstream/main:<file>` 逐行确认上游当前代码仍存在该问题，无推测。

---

## 为什么提这个 PR

这 8 处问题是在审计本仓库安全边界时发现的。逐条比对上游 v0.16.0 当前代码，**确认全部仍未修复**。

其中 S1（命令注入）、S2（沙箱 fd 泄漏）、S3（FTP 鉴权绕过）、S4/S5（凭据损毁）的后果不可逆或涉及隔离边界，建议优先处理。

---

## S1 · 命令注入（CWE-78）

**文件**：`feature/git/GitManager.kt`

**上游当前实现**：
```kotlin
private fun shellEscape(value: String): String {
    val trimmed = value.trim()
    return if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) "\"$trimmed\"" else trimmed
}
```

只看空格，用双引号包裹。双引号**不阻止** `$`、`` ` ``、`"`、`\` 的展开 —— 项目路径含这些字符即可向沙箱 shell 注入命令。

**修复**：改用 POSIX 单引号转义（内部单引号拆成 `'\''`），任意路径都安全。

---

## S2 · 沙箱能读到宿主私有数据（fd 泄漏）

**文件**：`app/src/main/cpp/pty_native.c`

**问题**：`fork()` 会复制宿主 App 进程的**全部文件描述符**。exec 之后，这些 fd 仍留在 shell 里 —— 包括 SQLite 数据库、DataStore（含 API key 密文）、下载临时文件、网络套接字。PRoot 沙箱内以 root 运行的进程可以直接通过 `/proc/self/fd/N` 读写它们。**沙箱隔离在这条路径上形同虚设**，同时泄漏的 fd 也会逐渐耗尽进程配额。

**上游确认**：全文件 grep 无 `close_range` / `closefrom` / `/proc/self/fd`。

**修复**：exec 前调用 `close_from(3)`：
- 优先 `close_range`（Linux 5.9+，原子收口）
- 老内核回退逐个 `close`，上限取 `sysconf(_SC_OPEN_MAX)` 并夹逼到 1M 防异常值

---

## S3 · FTP 鉴权绕过

**文件**：`runtime/ftp/AndroidFtpServer.kt`

**上游当前实现**：
```kotlin
val expectedUser = config.username.ifBlank { "root" }
val passwordMatches = config.password.isNullOrBlank() || pass == config.password
val userMatches = user.equals(expectedUser, ignoreCase = true) || user.equals("root", ignoreCase = true)
```

两个问题：
1. **密码为空时放行一切密码** —— 只要用户没设密码（漏配），局域网内任何人可登录并读写 rootfs。免密应该是显式选择，不是漏配的默认结果。
2. `user.equals("root")` 后备匹配**绕过自定义用户名** —— 即便设了 `username = "alice"`，用 `root` 仍能登入。

**修复**：密码为空时拒绝密码登录（免密须显式启用 `anonymousEnabled`）；移除 root 兜底。

---

## S4 · Keystore 别名竞态导致数据永久不可解密

**文件**：`core/security/SecretManager.kt`

**问题**：`key()` 是 check-then-generate：
```kotlin
if (!store.containsAlias(alias)) { /* generateKey() */ }
return (store.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
```
并发首次调用时，两个线程可能**同时看到 alias 不存在**，各自 `generateKey()`。AndroidKeyStore 对同名 alias 是**覆盖写** —— 后写者覆盖前写者，**前者刚加密的数据将永久无法解密**。

**修复**：`key()` 加 `@Synchronized`。

---

## S5 · Keystore 短暂故障导致凭据整表销毁

**文件**：`core/datastore/SettingsDataStore.kt`

**问题**：`setWorkshopKeystores` 覆盖写之前**不校验存量密文能否解密**。若 Keystore 短暂故障（设备刚解锁、厂商 ROM 抽风）导致解密返回 null，调用方拿到的是「空列表」，再写回就把**全部签名密文永久销毁**。

**修复**：写入前先尝试解密存量；解不开就**拒绝写入**（`return@edit`），宁可这次设置失败，不可销毁数据。

---

## S6 · 路径前缀逃逸

**文件**：`runtime/ftp/AndroidFtpServer.kt`

**上游当前实现**：
```kotlin
val isContained = allowedRoots.any { root ->
    canonicalTarget.absolutePath.startsWith(root.absolutePath)
}
```

纯前缀匹配缺分隔符边界：允许根目录是 `/data/rootfs` 时，同级的 `/data/rootfs.staging`、`/data/rootfs.previous` 也会被判定为「在范围内」而放行。

**修复**：补边界 —— 路径相等，或前缀**紧跟 `File.separator`**。

---

## S7 · FTP bounce

**文件**：`runtime/ftp/AndroidFtpServer.kt`

**问题**：PASV 模式下数据端口在 30 秒窗口内处于监听状态，`accept()` **不校验对端 IP** —— 任意主机只要抢在真客户端之前连上，就能劫持数据连接（经典的 FTP bounce 场景）。

**修复**：`openDataSocket` 校验对端 IP 必须与控制连接来源（`clientIp`）一致，否则拒绝并关闭。

---

## S8 · URL / JSON 注入

**文件**：`core/network/CcSwitchClient.kt`

**上游当前实现**：
```kotlin
val payload = """{"providerId":"$providerId"}"""
.url("http://127.0.0.1:$port/api/agents/$agentId/switch")
```

`agentId` 直拼 URL path —— 含 `/` 或 `?` 时可越权访问其他端点；`providerId` 手拼 JSON —— 含引号可破坏结构。

**修复**：
- URL 用 `HttpUrl.Builder().addPathSegment(...)` 自动编码
- JSON 用 `JSONObject` 结构化序列化

**同时修复**：`installOrUpdateAgent` 存在同类问题（`agentId` 直拼 path、`version` 手拼 JSON），一并处理。

---

## 验证

**未在本地编译**（开发机为 Android 设备，编译开销过大），全部经**云端 CI** 验证。

- **编译（Kotlin + C）**：✅ 通过
- **单元测试**：⚠️ 见下节——**基线自带失败，非本 PR 引入**

### 关于测试结果（请作者留意）

CI 跑出 5 个失败，经**零改动基线对照实验**确认与本次改动无关：

| 测试 | 本 PR 分支 | 纯净上游基线（零源码改动） |
|---|---|---|
| `ToolExecutorTest`（4 例：base tool …） | 4 例失败 | **4 例失败（完全相同）** |
| `AndroidHttpServerTest > server can be restarted after stop` | 1 例失败（`BindException`） | 未复现 |

- **`ToolExecutorTest` 的 4 例**：对照实验证明**上游基线自身即失败**（76 tests completed, 4 failed）。复现方式：检出 `f82a44f7`，跑 `./gradlew testDebugUnitTest`。本 PR 未触碰 `ToolExecutor` 及其相关代码。
- **`AndroidHttpServerTest` 的 `BindException`**：端口占用导致，属 CI 机器抖动的常见表现；该测试文件不涉及 FTP（grep 0 命中），与本 PR 修改的 `AndroidFtpServer.kt` 无关联。

若作者希望，可另行排查上游基线这 4 个失败的原因（本次未纳入范围）。

---

## 兼容性

- 无 API 签名变更，无新增依赖（`HttpUrl`、`JSONObject` 均为项目已用库）
- S3 属**行为变更**：此前「未设密码即可登录」，现在会拒绝。若有用户依赖免密登录，需改用 `anonymousEnabled`
- 其余均为内部实现收紧，对外行为不变
