# 附件上传设计（图片 / 文档，v1.8；图片 blob 引用式存储，v1.9）

> **状态**：已实现（v1.8 + v1.9）。代码 `core/attachment/` + `infrastructure/media/AndroidUploadLoader.kt` + `Message.Attachment`；JVM 单测（`DocumentExtractorFactoryTest` / `UploadSizingTest` / `ImageBlobStoreTest`）通过。
> **上游文档**：`architecture.md`（Core / Infra 分层）/ `agent-core-design.md`（§3.1 节点模型、§5.4 Token 记账、§6 Agent Loop）。
> **本文档关系**：细化 `architecture.md`「Core 核心控制层 → 附件处理」与「基础提供层 → media」两块，是附件链路的唯一设计依据。与上游冲突之处**以本文为准**。

---

## 1. 定位与范围

**一句话**：把用户挑选的一张图片或一份文档，变成模型能直接消费的 payload，并且**不改变 Agent Loop 的任何时序**。

- **不改**：`SessionNodeType` 枚举（仍是 `UserPrompt/SystemPrompt/ToolCall/ToolResult/Steer/Summary` 六值）、DAG 分支语义、压缩触发逻辑、循环安全阀。
- **改**：`Message.User` 多一个 `attachments: List<Attachment>` 字段；`UserPrompt` 节点的 payload 内携带附件；`ModelRequest` 的 `content` 从字符串变成 parts 数组。
- **新增**：`core.attachment` 包（纯 JVM 逻辑，可单测）与 `infrastructure.media.AndroidUploadLoader`（`content://` URI 的 Android 边界实现）。

**设计原则**（与既有裁定一致）：

| 原则 | 落地 |
|---|---|
| **P2 失败显式** | 不支持的格式 / 解码失败 / 文件过大，在**选完文件那一刻**以 `Result.failure(UploadException)` 报给用户，不静默吞掉 |
| **纯逻辑与 Android 解耦** | 尺寸计算（`ImageSizing`）、格式分派（`UploadKinds`）、文档提取（`DocumentTextExtractor`）全部只吃 `ByteArray`/`String`，不出现 `android.*` 类型 |
| **格式扩展零侵入** | 新格式 = 新增一个 `DocumentTextExtractor` 实现 + `defaults()` 加一行；UI / ViewModel / Loop / 请求序列化全都不动 |
| **一次转换，全程复用** | 转换发生在**挑选时**（不是发送时），错误在用户还看着 picker 时就能报出来 |

---

## 2. 端到端链路

```
用户点 + │ 选图 / 选文档
   │
   ├─ Photo Picker（PickVisualMedia）/ OpenDocument（无 MIME 过滤）
   ▼
UploadOwner.load(uri, name)                       ← ViewModel 只认识这个缝
   │  AndroidUploadLoader（Dispatchers.IO）
   │
   ├─ UploadKinds.of(name, mimeType)  →  Image | Document | null
   │
   ├─[Image]─────────────────────────────────────────────────────┐
   │   openStream → readBytes（≤32 MB）                           │
   │   inJustDecodeBounds 只取宽高（防 12MP 先 OOM）               │
   │   ImageSizing.sampleSize → inSampleSize 降采样解码            │
   │   ImageSizing.fitted → createScaledBitmap（长边 ≤1568）       │
   │   WebP_LOSSY q85（保 alpha，比 PNG 小很多）                   │
   │   ImageBlobStore.save(id, bytes) → Attachment.Image(id)      │
   │                                                              ▼
   └─[Document]──────────────────────────────────────────────────┐
       openStream → readBytes → UploadDocument(name,mime,bytes)   │
       DocumentExtractorFactory.extract()                         │
         → firstOrNull { it.supports(doc) }（先注册者优先）        │
         → PlainTextDocumentExtractor（txt/md）                   │
              UTF-8 解码 → 去 BOM → NUL 字节判二进制 → 超 40k 截断 │
         → Success / Unsupported / Failed                        │
       → PendingUpload(text=…, truncated=…)                      ▼
                                                       PendingUpload（UI chip）
                                                                 │
                                            用户点发送 ──────────┤
                                                                 ▼
                                          PromptUploads.attachments(uploads)
                                                                 │
                                                       List<Attachment>
                                                                 ▼
                              AgentViewModel.startRun(prompt, attachments)
                                                                 ▼
                              AgentRunSupervisor.start(…, attachments)
                                                                 ▼
                    AgentLoopCoordinator.run(sessionId, prompt, attachments)
                                                                 ▼
                        queue.enqueue(IncomingMessage.UserPrompt(text, attachments))
                                                                 ▼
                            SessionRepository.appendUserPrompt(…, attachments)
                                    → Message.User(content, attachments) 落 JSONL
                                      （图片只落 {id,mimeType,name} 引用）
                                                                 ▼
                            AgentContextAssembler.nodeToMessage(UserPrompt)
                                    → Message.User(content, attachments = user.attachments)
                                                                 ▼
                            OpenAiCompatibleClient.formatMessageToJson()
                                    → ImageBlobStore.read(id) 取字节 → Base64
                                    → content: [ {type:"text"}, {type:"image_url"} , {type:"text"} ]
```

**唯一的格式分叉点**是 `UploadKinds.of`；`AndroidUploadLoader` 之后的整条链路只处理 `List<Attachment>`，不再区分来源。

---

## 3. 包与组件

| 位置 | 组件 | 职责 |
|---|---|---|
| `core/attachment/` | `UploadOwner` | **上传缝**（interface）。`suspend fun load(uri, name): Result<PendingUpload>` + `release(uploadId)`（丢弃未发送的 blob）。ViewModel 只依赖它，因此上传逻辑可在 JVM 单测里用假实现替换 |
| | `UploadKinds` | **纯分派**：`of(name, mimeType): UploadKind?`。MIME 优先、扩展名兜底；`null` = 明确不接受 |
| | `ImageSizing` | **纯尺寸计算**：`sampleSize()` 降采样因子、`fitted()` 目标宽高。`MAX_EDGE = 1568` |
| | `ImageBlobStore` | **图片二进制库**（v1.9）：`save/read/delete`，`FileImageBlobStore` 落 `filesDir/agent/images/<id>.webp`。JSONL 只存引用 |
| | `PendingUpload` | **待发送态**：一条 chip 的数据（`id/name/kind/sizeBytes/image/text/truncated`） |
| | `PromptUploads` | **待发送 → 附件映射** + token 估算（`IMAGE_TOKEN_ESTIMATE = 1200`） |
| | `DocumentExtraction` | `UploadDocument`（name/mime/bytes）、`DocumentExtraction`（Success/Unsupported/Failed）、`DocumentTextExtractor`（provider 接口） |
| | `DocumentExtractorFactory` | **工厂**：`extractorFor()` / `extract()` / `defaults()` |
| | `PlainTextDocumentExtractor` | **唯一 provider（txt/md）** |
| `infrastructure/media/` | `AndroidUploadLoader` | `UploadOwner` 的真实实现：`content://` → 解码 / 提取 / 写 blob |
| `infrastructure/model/model/` | `Attachment` | **落库/传输模型**：`Image(id, mimeType, name)` / `Document(name, text, truncated)` |

`UploadDocument` 刻意不出现 Android 类型——provider 只吃 `ByteArray`，所以将来的 pdf/word 解析器（哪怕是 JVM 库）也能在同一层单测。

---

## 4. 图片路径

`AndroidUploadLoader.prepareImage()`，四道闸门按顺序：

1. **大小闸门**：`readBytes()` 后 `> 32 MB` 直接 `UploadException`。存在的意义是**明确失败**，而不是解码进 OOM。
2. **只取边界**：`inJustDecodeBounds = true` 的 `BitmapFactory` 只填 `outWidth/outHeight`、**故意不返回 bitmap**——12MP 原图直接解码可能在缩放前就爆内存。宽高 `<= 0` 视为解码失败。
3. **两级降采样**：`ImageSizing.sampleSize()` 选 2 的幂做 `inSampleSize`（解码时就丢像素，省内存），再用 `ImageSizing.fitted()` 精确缩到长边 1568。
4. **重编码**：`WEBP_LOSSY` q85。选 WebP 而非 PNG/JPEG 的理由：**保 alpha**（截图不会变黑底）+ 体积远小于 PNG；所有 vision provider 都认。写完直接 `ImageBlobStore.save(id, bytes)`，payload 只带 `{id, mimeType, name}`。

`MAX_EDGE = 1568` 不是审美选择，而是**成本约束**：WebP blob 会在请求格式化时 base64 进 `data:` URL，并且**每一轮都要重新发一遍**。12MP 照片（3–6 MB → ~8 MB base64）会让每一次请求都付这笔钱。1568 也正好是 vision 模型切 tile 的常用长边。

**清理**：`decoded.recycle()` 在 `finally`，缩放产物 `bitmap !== decoded` 时单独 recycle。

---

## 5. 文档路径（工厂模式）

用户要求「**必须用工厂分派**，因为以后要加 pdf/word」。落地方式：

```kotlin
class DocumentExtractorFactory(private val extractors: List<DocumentTextExtractor> = defaults()) {
    fun extractorFor(document: UploadDocument) = extractors.firstOrNull { it.supports(document) }
    fun extract(document: UploadDocument): DocumentExtraction =
        extractorFor(document)?.extract(document) ?: DocumentExtraction.Unsupported(document.name)

    companion object { fun defaults(): List<DocumentTextExtractor> = listOf(PlainTextDocumentExtractor()) }
}
```

**三个要点**：

1. **先注册者优先** = 优先级。`supports()` 只看文件名与声明 MIME，**从不读字节**，所以分派是纯函数、零 I/O。
2. **三种结局，不是两种**：
   - `Success(name, text, truncated)` — 可用；
   - `Unsupported(name)` — 「这个格式还没有 provider」（pdf/word 落地前）；
   - `Failed(name, reason)` — 「provider 认领了但内容读不出来」。
   `Unsupported` 与 `Failed` 分开是刻意的：**只有后者该怪文件**，前者是「我们还不支持」。
3. **picker 故意不加 MIME 过滤**：接受格式只在一处维护（工厂），没人认领的文件回来变成**明确的**「暂不支持」——而不是被静默附加成一条空消息。

`PlainTextDocumentExtractor` 的具体规则：

- UTF-8 解码 + 去 BOM（`\uFEFF`，某些编辑器留下，对模型是纯噪音）。
- **NUL 字节 = 二进制标记** → `Failed("内容疑似二进制文件，无法作为文本读取")`。把二进制硬解成文本会得到满屏乱码，模型还得为它推理一遍。
- 超 `DEFAULT_MAX_CHARS = 40_000` 字符（约 1 万汉字 token）截断**只留开头**，且截断**不切断 surrogate pair**（半个字符会渲染成替换符）。
- `truncated` 标记同时传达给**模型**（`[附件：name（内容过长，仅保留开头部分）]`）与**UI chip**——截断必须被声明，不能静默。

为什么截断：提取出的文本进一个持久化 payload、每轮重发、还要占上下文预算。2 MB 的日志文件会吃光整个会话窗口。

---

## 6. 消息层与线上格式

```kotlin
sealed interface Attachment {
    data class Image(val id: String, val mimeType: String, val name: String? = null) : Attachment
    data class Document(val name: String, val text: String, val truncated: Boolean = false) : Attachment
}
```

`OpenAiCompatibleClient.formatMessageToJson()`：

- `attachments.isEmpty()` → `content` 仍是**字符串**（保持旧行为，不惊动任何端点）。
- 有附件 → `content` 变 **parts 数组**：
  - 正文非空才加 `{"type":"text","text":…}`（**纯附件轮没有正文**；空 text part 会被严格端点拒绝）。
  - `Attachment.Image` → `ImageBlobStore.read(id)` 取字节 → `{"type":"image_url","image_url":{"url":"data:<mime>;base64,<data>"}}`。blob 缺失时改发 `{"type":"text","text":"[图片已丢失：name]"}`，不拖垮整个请求。
  - `Attachment.Document` → `{"type":"text","text":"[附件：<name>]\n<text>"}`。**标签是必需的**：两个上传文件之间的文本没有天然边界，模型只能靠标签区分；`truncated` 时标签内再补一句说明。

**文档走文本而非原生 file part**，这正是格式扩展便宜的根因：pdf/word 将来只需产出 `text`，请求格式**永远不用变**。

---

## 7. 持久化与一个被修掉的 bug

- 落库：`SessionRepository.appendUserPrompt()` 把 `Message.User(content, attachments)` 整体序列化进 `UserPrompt` 节点的 JSONL payload。**没有新增节点类型**——附件是 payload 内的字段，所以分支、编辑重发、compact、token 汇总全都自动兼容。
- **图片不进 JSONL**（v1.9）：`Attachment.Image` 只带 `{id, mimeType, name}` 引用，WebP 字节在 `ImageBlobStore`（`filesDir/agent/images/<id>.webp`）。会话删除时按 payload 里的引用把 blob 一并删掉；未发送就丢掉的 chip 走 `UploadOwner.release()`。
- **被修掉的 bug**：`AgentContextAssembler.nodeToMessage(UserPrompt)` 原先只取 `user.text`、把附件无声丢弃。后果是**哪怕模型层早就支持 vision，图片也永远发不出去**；纯附件轮还会变成空 prompt。现在显式 `attachments = user.attachments`。
- 编辑重发：`AgentViewModel.editAndResend()` 用 `attachmentsOf(node)` 从原 payload 读回附件并带进新分支——否则「改个错字」就会把图丢了。引用式存储下这直接复用同一个 blob id，不复制文件。

---

## 8. Token 记账与压缩

- `PromptUploads.estimatedTokens()`：文档文本走 `TokenEstimator`（ASCII ≈ chars/4，CJK ≈ chars/1）；**图片按常量 1200/张**。
  为什么图片也要估：payload 里没有尺寸信息，而完全忽略图片会让「图片很多」的会话悄悄越过压缩触发线。
- `AgentLoopCoordinator` 落 `UserPrompt` 节点时 `tokenCount = estimate(text) + PromptUploads.estimatedTokens(attachments)`。
- **压缩边界（已知限制）**：`NodePayloads.flatText(UserPrompt)` 只取 `content`，**不含附件**。也就是说被压缩收编的附件轮，摘要里只留下用户文字，文档正文与图片不会进入 summarizer 输入。这符合「图片本来就无法被摘要」的现实，但**长文档被压缩后其正文会从活跃路径消失**——需要长文档长期在场的场景，应改为「先落地到 workspace 文件、再用工具读」，而不是靠附件常驻。

---

## 9. UI 层

| 位置 | 变化 |
|---|---|
| `AgentScreen.kt` | `PickVisualMedia()`（系统 Photo Picker，minSdk 34，**不需要任何运行时权限**）+ `OpenDocument()`（无 MIME 过滤，理由见 §5） |
| `InputBar.kt` | `+` 按钮 → `AttachSheet`（图片 / 文档两个**带图标的描边卡片**，标题只是标题、不再像按钮）；上方待发送区：图片是 **56dp 缩略图 + 角标删除**，文档是可删除 chip；`uploadBusy` 转圈；`uploadError` 就近显示 |
| `ImageThumbs.kt` | 缩略图唯一入口：按 blob id 从 `ImageBlobStore` 读字节，**降采样到 256px** 再解码（`rememberImageThumb`）；blob 缺失时占位「图」，不塌版式 |
| `AgentUiModels.kt` | `pendingUploads: List<PendingUpload>` / `uploadBusy` / `uploadError`；`UiAttachment(name, image, truncated, imageId)` 是**显示投影**——字节与文档正文不进 Compose state，只有 blob 引用随行，位图只在行上屏时解码 |
| `ConversationView.kt` / `ConversationRenderer.kt` | 用户气泡内：图片渲染为 **88dp 缩略图**（GPT / Gemini 式），文档仍是单行 chip——**绝不渲染原始 payload**（提取出的文档是 prompt 量级的文本，塞进气泡就是一面文字墙） |
| `AgentViewModel.kt` | `onPick…` → `UploadOwner.load()`；发送时 `PromptUploads.attachments(pendingUploads)` 并清空 chips（同一个 `update`）；`loadImageBytes(id)` 供缩略图按需读 blob |
| `res/drawable/ic_attach_*.xml` | 附件入口的 SVG 图标（图片 / 文档），与文字入口拉开辨识度 |

`UiAttachment` 只多带一个 `imageId`，是这套设计能撑住「图片进会话历史 + 缩略图」的关键：会话再长，Compose 里也只有字符串 + 少量按需解码的小图。

---

## 10. 扩展一个新文档格式（pdf / word）

1. 实现 `DocumentTextExtractor`：`id`、`supports()`（看扩展名/MIME）、`extract()`（失败返回 `Failed`，不抛异常）。
2. `DocumentExtractorFactory.defaults()` 里加一行（顺序即优先级）。
3. 完。`UploadKinds` 需要把新扩展名归到 `Document` 分支；`AndroidUploadLoader`、`AgentViewModel`、`InputBar`、`AgentLoopCoordinator`、`OpenAiCompatibleClient` **一律不改**。

新 provider 的 `extract()` 应自己限制输出长度（参照 `PlainTextDocumentExtractor.DEFAULT_MAX_CHARS`），因为落库成本与每轮重发成本都由它决定。

---

## 11. 测试

| 测试 | 覆盖 |
|---|---|
| `DocumentExtractorFactoryTest` | 工厂分派顺序、`Unsupported` vs `Failed` 的区分、txt/md 认领规则 |
| `UploadSizingTest` | `sampleSize` / `fitted` 纯函数；`PromptUploads.attachments()` 映射与 `truncated` 传递；token 估算（文档走 estimator、图片按常量） |
| `AgentViewModelTest` | 上传缝替身：pending 状态、发送清空、失败展示、编辑重发带附件 |

真实 provider 参与测试，**不打 mock**——工厂的全部价值就在分派结果，mock 掉就什么都没测。

---

## 12. 已知限制 / 未决

| # | 事项 | 现状 |
|---|---|---|
| A1 | 仅支持 `txt` / `md`（+ `text/*`） | pdf/word 走 §10 扩展点，未排期 |
| A2 | 图片以 WebP blob 常驻本地、每轮重发 base64 | v1.9 改为引用式存储（JSONL 只留 id），请求时才读 blob 编码；每轮重发是 OpenAI 兼容接口的固有成本，已用 1568 长边 + WebP 压到可接受 |
| A3 | 压缩收编后附件正文不进入摘要（§8） | 长文档长期在场的场景应改走 workspace 文件 |
| A4 | 单张图片按固定 1200 token 估算 | payload 无尺寸信息；接入 provider 的 image token 计量后可替换 |
| A5 | 无 OCR / 无图片描述回填 | 纯图片轮依赖 vision 模型自身能力 |
