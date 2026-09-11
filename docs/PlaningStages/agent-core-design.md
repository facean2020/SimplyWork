# Agent Core 设计（会话状态树 + 记忆分层 + Agent Loop）

> **状态**：设计基线 v1.0（2026-08-31，已实现）；UI 阶段增量 v1.1（2026-09-10，已实现）
> **上游文档**：`architecture.md`（Core 核心控制层）/ `android-agent.md`（§5.4 Steer-capable Loop）/ `appfunction-design.md` v1.1（systemops 工具来源）
> **本文档关系**：细化 `architecture.md` 中「Core 核心控制层」的 agent-core + session-core 两块，作为本轮实现的唯一设计依据。与 `architecture.md` 冲突之处**以本文为准**（差异见 §12）。
> **实现状态**：**已实现**（包 `core.agent` / `core.session` / `core.AgentRuntime`；JVM 单测 49 项通过，全工程 84 项）。UI 阶段增量：包 `ui.agent` / `ui.settings` / `AppContainer`（Q1 HITL 弹窗、Q2 循环外层重启、多会话已落地；全工程 JVM 102 项）。

---

## 1. 定位与范围

Agent Core 是 Core 核心控制层的驱动引擎：**把一次用户意图变成一条「模型推理 ↔ 工具执行」的循环，并把全程落成可回溯的 DAG 会话树**。

| 层 | 本轮交付 | 说明 |
|---|---|---|
| **session-core** | 会话状态树持久化 | SQLite 存 DAG 结构，JSONL 存 payload 内容；`SessionRepository` 唯一门面 |
| **agent-core** | Agent Loop 驱动引擎 | 消息队列、Prompt 组装、上下文压缩、前后审计门禁、完成守卫、最小工具注册表 |
| tool-core | ❌ 不在本轮 | 以 **Registry-lite**（agent-core 内最小注册表）过渡，接口形状对齐未来 tool-core |
| skill-engine | ❌ 不在本轮 | 留 `SkillInjector` 注入缝（默认空实现） |
| UI 层对接 | ❌ 不在本轮 | ~~`AgentRuntime` 装配工厂即为 UI 对接点~~ → **UI 阶段已交付**（包 `ui.agent`：AgentViewModel MVI / 对话屏 / 会话抽屉 / 设置屏 / Q1 审批弹窗） |

**依赖方向**（自上而下，反向禁止）：

```
agent-core ──▶ session-core ──▶ infrastructure(error/util)
    │
    ├──▶ infrastructure.model      (ModelProvider / Message / ToolDefinition)
    ├──▶ infrastructure.systemops  (Router / ToolFactory / DangerLevel)
    └──▶ infrastructure.sandbox    (预留，本轮不接)
```

---

## 2. 会话存储（session-core）

### 2.1 双介质分工

> **SQLite 存 DAG 结构信息，JSONL 存 payload 内容**（用户裁决）。

| 介质 | 存什么 | 为什么 |
|---|---|---|
| **SQLite**（单库 `filesDir/agent/sessions/agent.db`） | 节点元数据 + 树边 + 状态 | 结构化查询（路径回溯、状态筛选、token 汇总）、事务性、状态更新不用重写大字段 |
| **JSONL**（每会话一文件 `filesDir/agent/payloads/<sessionId>.jsonl`） | 节点 payload（消息正文/工具调用与结果） | append-only 顺序写、人可读可导出、大文本不膨胀数据库 |

SQLite 三张表：

```sql
CREATE TABLE sessions (
  id             TEXT PRIMARY KEY,          -- UUID
  title          TEXT NOT NULL,             -- 首条 UserPrompt 前 48 字符
  created_at     INTEGER NOT NULL,          -- epoch ms
  active_leaf_id TEXT                       -- 活跃叶游标（Active Node Pointer）
);

CREATE TABLE nodes (
  id             TEXT PRIMARY KEY,          -- UUID
  session_id     TEXT NOT NULL REFERENCES sessions(id),
  parent_id      TEXT,                      -- 树边；root 为 NULL
  node_type      TEXT NOT NULL,             -- UserPrompt|SystemPrompt|ToolCall|ToolResult|Steer|Summary
  token_count    INTEGER NOT NULL,          -- 本节点内容 token（估算或 usage 实测）
  status         TEXT NOT NULL,             -- pending|success|failed|abandoned
  created_at     INTEGER NOT NULL,
  payload_offset INTEGER NOT NULL,          -- JSONL 文件内字节偏移
  payload_length INTEGER NOT NULL           -- JSONL 行字节数（不含换行）
);
CREATE INDEX idx_nodes_session ON nodes(session_id, created_at);

CREATE TABLE session_state (               -- State Memory（非 DAG，单行可变状态）
  session_id      TEXT PRIMARY KEY REFERENCES sessions(id),
  todo_json       TEXT NOT NULL,            -- List<TodoItem> JSON
  preferences_json TEXT NOT NULL,           -- List<String> JSON
  updated_at      INTEGER NOT NULL
);
```

JSONL 每会话一文件，**一行一条记录、追加写**（kotlinx.serialization 保证 JSON 内换行被转义，行不会撕裂）：

```json
{"id":"<nodeId>","nodeType":"ToolCall","payload":{ ... }}
```

payload 按 nodeType 分型（复用既有 `Message` 序列化）：

| nodeType | payload 形状 |
|---|---|
| `UserPrompt` | `Message.User` JSON |
| `SystemPrompt` | `{"kind":"base"\|"follow_up", "text":"..."}` |
| `ToolCall` | `Message.Assistant` JSON（content + toolCalls；**无工具调用的纯文本回答也落此类型**，见 §3.1） |
| `ToolResult` | `Message.Tool` JSON |
| `Steer` | `{"text":"...", "atLoop":N}`（运行中用户转向指令） |
| `Summary` | `{"text":"...", "coveredNodeIds":[...], "coveredTokenCount":N, "strategy":"model"\|"truncate"}` |

### 2.2 写入顺序与崩溃一致性

1. 追加 JSONL 行（`FileDescriptor.sync()`）→ 2. 插入 SQLite 行（含 offset/length）。

- **崩溃在 ①② 之间** → 产生「孤儿 JSONL 行」：加载时按 SQLite 为准，未引用行忽略（无害）。
- **DB 有行、payload 缺失**（理论上不会出现）→ 读 payload 报 `Session.PayloadMissing`，组装器按显式失败处理（P2）。

### 2.3 恢复语义（进程被杀后 resume）

- `ToolCall` 节点若停留 `pending` → 说明工具执行中断：resume 时统一改 `abandoned`，组装器为其**合成**一条 `ToolResult(isError=true, "interrupted by restart")`，保证 OpenAI 兼容接口的 tool_calls→tool 消息配对完整。
- 活跃叶游标存于 `sessions.active_leaf_id`，resume 从叶回溯即可重建上下文。

### 2.4 存储接口（可测性）

不引 Room、不引 Robolectric（沿用 systemops 的测试惯例）：

```kotlin
interface SessionStore   { /* sessions/nodes 表的结构化读写 */ }
interface PayloadStore   { /* append(id, nodeType, payloadJson) / read(id) */ }
```

- `SqliteSessionStore` / `JsonlPayloadStore`：Android 薄层（Context 注入路径），**instrumented 验收延后**（与 systemops 同一欠账池）。
- `InMemorySessionStore` / `InMemoryPayloadStore`：主源码内实现，JVM 单测与潜在轻量运行模式共用。
- `SessionRepository`（接口 + Impl）：对 agent-core 唯一暴露面——建会话、追节点、改状态、回溯活跃路径、StateMemory 读写。并发用 `Mutex` 串行化（单会话单运行假设，防御性保护）。

---

## 3. 会话节点模型

### 3.1 节点字段（用户规格逐字落地）

```kotlin
data class SessionNode(
  val id: String,            // UUID
  val parentId: String?,     // 树边
  val nodeType: SessionNodeType,
  val payloadRef: String,    // JSONL 记录定位（读经由 repository）
  val tokenCount: Int,
  val status: SessionNodeStatus,
  val createdAt: Long
)

enum class SessionNodeType { UserPrompt, SystemPrompt, ToolCall, ToolResult, Steer, Summary }
enum class SessionNodeStatus { pending, success, failed, abandoned }
```

**类型语义裁定**：

- **`ToolCall` 承载完整 assistant 回合**——含工具调用时必然是本类型；模型**纯文本最终回答**（无工具调用）也落 `ToolCall` 节点（nodeType 枚举保持用户规格的六值不变，payload 内 `toolCalls: []` 表达差异）。它在该轮的 DAG 语义就是「模型输出节点」。
- **`Steer` 是用户输入**：组装时转 `Message.User`（模型把 user 角色视为最高优先级插入意图，符合转向语义）。
- **`SystemPrompt(kind=follow_up)` 是系统自愈追问**（CompletionGuard / 审计拒绝后的引导），组装时转 `Message.User` + `[system follow-up]` 前缀——以用户口吻注入保证所有 OpenAI 兼容端点的接受度。

### 3.2 状态机

| 节点类型 | 初始 | 终态 | failed 含义 |
|---|---|---|---|
| UserPrompt / Steer | success 直落 | — | — |
| SystemPrompt | success 直落 | — | — |
| ToolCall | `pending`（模型产出即落库） | `success`（已产生配对 ToolResult，含业务失败） / `failed`（审计拒绝或执行崩溃，无正常结果） | 该调用未被正常执行 |
| ToolResult | success 直落（含 isError=true 的业务失败——结果是合法记录） | — | — |
| Summary | success 直落 | — | — |
| 任意 | — | `abandoned`（恢复清扫 / 未来 /tree 回退剪枝） | — |

**失败显式**（P2 映射）：审计拒绝不是静默丢弃——落 `ToolCall(failed)` + `ToolResult(isError=true, "Rejected by audit: …")` 双节点，模型下一轮看到拒绝原因后自愈改道。

---

## 4. DAG 与分支语义

- 树边 = `parent_id`；**活跃路径** = `active_leaf_id` 沿 parent 回溯至 root。v1 会话为**追加链**（用户 steer、follow-up 都是追加节点，不分叉）；`/tree` 分支回退是后续功能，本设计只需保证：**换叶即换分支**（改 `active_leaf_id` 一处指针），历史节点永不删除。
- **压缩重挂**：压缩把旧前缀换成 Summary 节点时，将剩余首节点的 `parent_id` 重写到 Summary（一条 UPDATE），原链路仍完整保留在库内（可审计、可回溯）。
- Summary 节点自身在活跃路径上，可被后续更粗粒度的压缩再次收编（Summary 链式套叠）。

---

## 5. 会话三层记忆

> 每轮组装 `ModelRequest` 时的注入顺序：**State Memory（置顶）→ System Prompt → Episode Summary → Working Memory**。

### 5.1 WORKING MEMORY — 活跃分支最近 N 轮未压缩交互

- 「轮（round）」= 一条 `UserPrompt`/`Steer` 节点起，到下一条用户节点之前的全部节点（含 assistant 回合与工具链）。
- 从活跃叶回溯按轮收集，受双重预算：**最多 `maxWorkingRounds`（默认 12）轮** 且 **token 总量 ≤ `workingTokenBudget`（默认 12_000）**；预算内取**最近的完整轮**，放不下整轮则整轮丢弃（不截半轮，保证配对完整）。
- 不缓存、每轮循环实时从 repository 计算（节点元数据很轻）。

### 5.2 EPISODE SUMMARY — 历史长链的摘要节点

- Working Memory 覆盖不到的更早历史，由 `ContextCompactor` 压成 `Summary` 节点留在活跃路径上（§7）。
- 摘要节点按 §2.1 payload 形状落库，组装时转 `Message.System("Summary of earlier conversation: …")`。

### 5.3 STATE MEMORY — To-Do 与用户偏好（每轮强制置顶注入）

- **非 DAG 数据**：单行可变状态，存 `session_state` 表，不进节点流。
- 组装时拼进首条 System 消息的固定区块：

```
[STATE MEMORY]
Todos:
- [ ] (in_progress) 订阅下周例会
- [x] (done) 查询天气
Preferences:
- Always reply in Chinese
```

- 状态变更唯一入口 = `todo_write` 内置工具（§8.3）；`CompletionGuard` 读同一数据源判定闭环。
- TodoItem：`{id, title, status: pending|in_progress|done|cancelled, createdAt, updatedAt}`；偏好项 v1 为 `List<String>`（采集工具延后）。

### 5.4 Token 记账

- assistant 回合：优先取 `ModelResponse.usage.completionTokens` 实测值；其余节点用启发式估算器（ASCII ≈ chars/4，CJK ≈ chars/1，取和），`TokenEstimator` 接口可替换。
- 压缩节省量 = 被收编节点的 tokenCount 之和 − 摘要自身 tokenCount，写入 Summary payload 的 `coveredTokenCount`。

---

## 6. Agent Loop（驱动时序）

与用户给定的流程图逐节点对应：

```kotlin
suspend fun run(sessionId, initialPrompt): Flow<AgentStepEvent>   // 冷流，取消即安全终止
```

```
① UserPrompt 入队 ────────────────▶ 入队即落库 UserPrompt 节点
② B Check Message Queue ──────────▶ drainAll()：Steer/FollowUp/UserPrompt 全部出队并逐条落库
③ C Inject System Prompt ───────── 会话首轮持久化一条 SystemPrompt(kind=base) 节点（仅一次）
④ D Inject Skill and Tool ──────── registry.definitions()（sys_* 13 + todo_write）+ SkillInjector 缝（默认空）
⑤ E Compact Engine ─────────────── 水位检查，超限则压缩（§7）
⑥ F Convert format ─────────────── AgentContextAssembler：节点流 → ModelRequest（§8.1）
⑦ H Send to LLM ────────────────── withTimeout(llmTimeoutMs=120s) + provider.generate()
⑧ I 落库 ToolCall 节点（pending→success）
⑨ J 有 ToolCalling？
   ├─ Yes → ⑩ O Pre-Tool Audit Gate
   │        ├─ Allow → ⑪ P 执行 → ⑫ Q Post-Tool Gate → 落库 ToolResult → 事件 → 回 ②(T)
   │        └─ Deny  → ToolCall 改 failed + 落库 ToolResult(isError, 拒绝原因) → 回 ②(U)
   └─ No  → ⑫ K 队列空？
            ├─ No → 回 ②(L)
            └─ Yes → ⑬ N Completion Guard
                     ├─ Completed → emit Completed → 终止 (M)
                     └─ Not → 入队 FollowUp（下轮 ② 落 SystemPrompt 节点）→ 回 ②(W)
```

**终止与安全阀**（防失控三件套）：

| 阀 | 默认 | 触发后果 |
|---|---|---|
| `maxLoopIterations` | 200 | 发 `Failed("loop budget exceeded")` 终止 |
| `maxGuardInterventions` | 3 | CompletionGuard 连续追问 N 次仍未闭环 → 强制 `Completed(reason=guard_exhausted)` |
| `llmTimeoutMs` | 120_000 | 单轮 LLM 超时 → `Failed`（无自动重试，重试策略留给调用方重启；见 §12 开放问题 Q2） |

**取消语义**：运行即冷 Flow；协程取消传播到 `generate()` 挂起点，已落库节点不受影响（pending 清扫交给 §2.3 恢复逻辑）。

**AgentStepEvent**（对 UI 的唯一事件出口）：
`LoopStarted / InboundPersisted(count) / ModelTurn(text, toolCallCount, usage) / ToolStarted(name) / ToolCompleted(name, ok) / ToolRejected(name, reason) / ContextCompacted(summaryNodeId, savedTokens) / FollowUpInjected(text) / Completed(finalText) / Failed(cause)`

> UI 阶段增量：新增 `InboundPersisted` —— 入队消息（用户 prompt / 转向）落库即通知，UI 无需等下一轮 ModelTurn 就能显示（§6.1 事件面最小扩展，2026-09-10）。

**v1 明确不用流式**：循环内走 `generate()` 单轮非流式（时序图即单点 Response）；UI 流式渲染将来以 `generateStream` 换入，接口缝已在 ModelProvider，本轮不做。

---

## 7. ContextCompactor（上下文压缩自愈引擎）

### 7.1 触发与范围

- 触发条件（每轮 ⑤ 检查）：估算下一轮上下文 token（StateMemory + Summary + Working 全量）> `compactionTriggerTokens`（默认 24_000）。
- 压缩对象：活跃路径上**最旧的连续节点前缀**，满足「不进入 Working Memory 保留窗（`compactionRetainRounds`=4 轮）」且不含 SystemPrompt(base)。
- 一次压缩至少收编一整轮；不足一轮（会话刚开始）→ 跳过本轮压缩。

### 7.2 压缩流程

1. 选定收编 span（最旧 → 保留窗之前）。
2. `Summarizer.summarize(nodes)` 产出摘要文本：
   - `ModelSummarizer`（默认）：用当前 ModelProvider + 压缩专用 prompt（结构化模板：用户目标 / 已完成步骤 / 关键工具结果 / 未决事项）。
   - `TruncateSummarizer`（兜底）：确定性拼接截断，无 LLM 依赖（测试与离线降级）。
3. 创建 `Summary` 节点：`parent = span 前一个节点`（span 起点为 root 时 parent = root，即 parentId=null 的兄弟），重挂 span 后首节点 `parent_id → Summary.id`（§4）。
4. **Summary 插入不推进叶指针**（`appendNode(advanceLeaf=false)`）：叶仍停留在工作窗上，摘要在重挂后才成为祖先节点；被收编节点**不删除不改状态**（历史保真，路径不可达即不可见）。

### 7.3 压缩失败的显式处理

Summarizer 异常 → 落 `CompactionSkipped(reason)` 事件（非终态）、跳过压缩继续循环（上下文超限时由模型端报 `ContextWindowExceeded` 兜底，不做静默截断）。

---

## 8. 工具面与审计门禁

### 8.1 Registry-lite（tool-core 落地前的过渡形态）

```kotlin
data class RegisteredTool(
  val definition: ToolDefinition,          // 复用 infrastructure.model
  val dangerLevel: DangerLevel,            // 复用 systemops（P3 贯通）
  val executor: suspend (argumentsJson: JsonObject) -> SystemToolOutput
)
class AgentToolRegistry { fun register(t) / get(name) / definitions() }
```

- 对齐 `appfunction-design.md` §6.1：`SystemToolEntry` 携带 dangerLevel 的形状即 Registry-lite 条目形状；tool-core 落地后整体迁出。
- **v1.3 工具族账目**：`sys_*` 13 个（§5 全走 Router）+ `todo_write`（State Memory 唯一写入口）+ **v1.3 自操作 10 个**（`self_config_read` / `self_config_update` / `self_session_read` / `self_project_create` / `fs_list` / `fs_read` / `fs_write` / `fs_mkdir` / `fs_move` / `fs_delete`，经 `AgentRuntime.createWithStores(selfOps = …)` 注入注册，缺省不注册保测试零改动）= 注册表 24 个工具。

### 8.2 Pre-Tool Audit Gate（决策表）

| # | 检查 | 结果 |
|---|---|---|
| A1 | 工具未注册 | **Deny**("Unknown tool") |
| A2 | 参数 JSON 解析失败（`SystemOperationToolFactory.parseArguments` 等） | **Deny**("Invalid arguments: …") |
| A3 | dangerLevel ∈ {SAFE, USER_VISIBLE} | **Allow** |
| A4 | dangerLevel == USER_CONFIRMING | 询问 `AuditApprovalHandler`：approve → Allow；decline → **Deny**("User declined") |
| A5 | CalendarInsertSpec DIRECT_WRITE | dangerLevel 解析为 USER_CONFIRMING（实例级，覆盖 entry 默认值），走 A4 |
| A6 | v1.2 读工具执行时发现缺 Android 运行时权限 | **不在审计门禁层**：A3/A4 通过后进入 Router，执行器经 `SystemPermissionRequester` 邀请 UI 弹系统授权框 → **复查权限** → 已授予则同一次调用继续；仍缺则回 `PermissionRequired` 结果（Deny 不抛异常，P2） |

- `AuditApprovalHandler` 是 HITL 缝：v1 默认 `AutoApproveHandler`（审计日志放行），UI 弹窗版后续替换（诚实欠账，见 §12）。
- **Deny 不抛异常**：生成 `ToolResult(isError=true)` 落库回流模型（P2）。
- **A4 与 A6 是两条独立的 HITL 通道，不要合并**：A4 问"要不要让助手做这件事"（我们自己的对话框，可配置降级），A6 是 Android 系统权限弹窗（由系统渲染、每权限至多问两次、拒绝后进入永久拒绝态）。A6 挂在执行器一侧，正是因为 P4 规定基础设施只报告、申请归 UI。
- A6 的挂起同样受 Router 10 s 超时约束：用户不回系统弹窗时退化为 `Timeout`，不会吊死 Agent Loop；`PermissionCoordinator` 在取消时清空挂起态，不留幽灵弹窗。

### 8.3 工具清单（v1.2：14 个）

| 工具 | 来源 | dangerLevel |
|---|---|---|
| `sys_deeplink_open` / `sys_app_open` | systemops Router | USER_CONFIRMING |
| `sys_clipboard_read` | systemops Router | SAFE |
| `sys_clipboard_write` / `sys_share_text` / `sys_notify_post` | systemops Router | USER_VISIBLE |
| `sys_calendar_insert` | systemops Router | USER_VISIBLE（DIRECT_WRITE 时实例级升 USER_CONFIRMING） |
| `sys_time_read` / `sys_time_calc` / `sys_device_read` / `sys_app_list` | systemops Router（v1.2 读侧） | SAFE |
| `sys_calendar_query` / `sys_location_read` | systemops Router（v1.2 读侧） | USER_CONFIRMING |
| `todo_write`（builtin） | agent-core，写 StateMemoryStore | SAFE |

**为什么读侧工具必须占两个 dangerLevel 档**：分档依据是**数据归属**而非操作类型。时钟、电量、已安装包名属于设备状态 → `SAFE`；日程内容与实时坐标属于用户私有数据 → `USER_CONFIRMING`，每次调用都过 HITL。副作用是 `sys_calendar_query` 比 `sys_calendar_insert` 的默认模式更严——这不是笔误：EDITOR_INTENT 写入由日历 App 自行确认、从不把既有日程暴露给我们，而查询会把整本日程读进模型上下文。

**v1.2 读侧解决的硬缺口**：模型没有任何时间感（`DEFAULT_SYSTEM_PROMPT` 与 `AgentContextAssembler` 注入链都不含当前时间），而 `sys_calendar_insert` 的入参是 epoch 毫秒——没有 `sys_time_read`，排期只能靠猜，日历写入实际不可用。同理 `sys_app_open` 要求精确包名，没有 `sys_app_list` 就稳定吃到 `target_not_found`。

缺权限时的自愈路径见 §8.2 A6，设计依据在 `appfunction-design.md` v1.2 修订段。

`todo_write` schema：`{"items":[{"title":string,"status":"pending|in_progress|done|cancelled"}]}`（replace-all 语义，id 服务端生成）；无 todo 工具时 CompletionGuard 无从判定闭环，故为必要最小内置件。

### 8.4 Post-Tool Result Gate

1. **归一化**：`SystemOperationResult` → 单行 JSON 文本（Success 用其 `outputJson`；其余 sealed 分支序列化，模型可机读 status/reason 自愈）。
2. **Sanitizer 缝**：`ToolResultSanitizer` 接口（PII 脱敏等），v1 默认恒等实现。
3. **截断**：超 `maxToolResultChars`（默认 8_000）→ 保留头部 + `[truncated N chars]` 后缀（显式标记，防模型误判完整）。
4. 落库 `ToolResult` 节点（tokenCount 估算）。

---

## 9. 错误体系衔接

`InfrastructureExceptions` 新增 `Session` sealed 分支（与 Model/Sandbox/SystemOperation 平级）：

```
Session.StoreIo(message, cause)      — SQLite/JSONL 读写失败
Session.PayloadMissing(nodeId)       — DB 有行而 JSONL 无内容（P2 显式失败）
Session.SerializeFailed(message)     — payload 编解码失败
```

循环内 LLM 失败（`InfrastructureException.Model.*`）→ 不落 assistant 节点、发 `Failed` 事件、运行终止（队列保留，用户可重发）。

---

## 10. 可测性策略（JVM 单测矩阵）

| 被测件 | 手段 |
|---|---|
| SessionRepository（InMemory store 对） | 节点追加/状态迁移/路径回溯/压缩重挂/resume 清扫 |
| JsonlPayloadStore | 临时目录 + 真实文件 IO（JVM 可测，纯 java.io） |
| AgentContextAssembler | 节点流 → ModelRequest：置顶注入顺序、Summary→System、Steer→User、配对修复合成 ToolResult |
| MessageQueueManager | 出队顺序（FIFO 跨类型）、isEmpty |
| AuditGatePipeline | 决策表全分支（A1–A5）、拒绝回流、Post-Tool 截断 |
| CompletionGuard | 无 todo/有开放 todo/空文本/连续追问上限 |
| ContextCompactor | 水位内 no-op / 触发压缩 span 选择 / 链式二次压缩 / Summarizer 失败跳过 |
| AgentToolRegistry + TodoTool | 注册检索、todo_write 写入 StateMemory |
| AgentLoopCoordinator（集成） | FakeProvider：单轮直答 / 工具调用 / 审计拒绝 / steer 中断 / 完成守卫追问闭环 |
| SqliteSessionStore | ❌ JVM 不可测（android.database.sqlite），薄层实现 + instrumented 验收（欠账池） |

---

## 11. 与既有裁定的衔接

- **P2 失败显式**：审计拒绝、后台剪贴板受限、PermissionRequired 一律作为带原因的结果/消息回流模型，无静默 null。
- **P3 危险等级贯通**：`DangerLevel` 从 systemops 沿 Registry-lite 直达审计门禁。
- **P4 权限归 UI**：基础设施只产 `PermissionRequired` 结果；`AuditApprovalHandler` 同理只定义缝，申请 UX 归 UI 层。
- **P5 超时语义**：LLM 调用 `withTimeout`；工具执行超时已由 SystemOperationRouter 统一（10s）。

---

## 12. 与 architecture.md 的差异及开放问题

**已裁决差异**（本文为准）：

1. session-core 持久化 **Room → 原生 SQLite + JSONL 双介质**（用户裁决，零新增依赖）。
2. `architecture.md` 样板中的 `dynamicInjector` 拆为「SystemPromptProvider + AgentContextAssembler + StateMemory 注入」三件，职责更单一。
3. `architecture.md` 中 app-function 命名已被 v1.1 裁决更替为 systemops，本文沿用。

**开放问题**：

| # | 问题 | 处理 |
|---|---|---|
| Q1 | `AuditApprovalHandler` 默认自动放行 USER_CONFIRMING 工具（DeepLink/AppOpen）——HITL 弹窗属 UI 层 | ✅ **已解决（UI 阶段）**：`ui.agent.ApprovalCoordinator` 实现 request 挂起 / respond 回灌，`AgentScreen` 弹审批对话框（关闭弹窗=拒绝，安全默认）；`AgentRuntime.create` 增加 `approvalHandler` 参数接入 |
| Q2 | LLM 瞬时故障（限流/网络）不自动重试 | ✅ **已裁决（UI 阶段）**：循环外层重启 —— `run` 的 `initialPrompt` 改为可空（null = 不入队直接续跑），UI 失败横幅提供"重试"；循环内保持零自动重试（P2 失败显式） |
| Q3 | SQLite 多进程访问未设防（单进程假设） | 单 Activity 应用成立；如未来多进程需换 WAL + 单写者 |
| Q4 | 偏好（Preferences）采集机制无工具化入口 | 待 skill-engine / 内置偏好工具排期 |
