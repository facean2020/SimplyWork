# Changelog

## [1.3.0]

### Added / 新增
- **Streaming markdown rendering** — assistant replies now render progressively as the model generates, with a live tail cursor. / 助手回复支持流式 Markdown 渲染，生成过程中实时显示，尾部带闪烁光标。
- **Web tools** — `web_search` (Bing with DuckDuckGo fallback) and `web_fetch` for the agent to look up and read web pages. / 新增 `web_search`（Bing + DuckDuckGo 兜底）与 `web_fetch` 工具，Agent 可搜索并读取网页。
- **Attachment pipeline** — upload images and documents (txt / md) with the message; images are downsampled to a 1568-px model-ready blob, documents are extracted to text. Thumbnails render inline in the chat bubble. / 支持随消息上传图片与文档（txt / md）；图片自动降采样至 1568px 供模型读取，文档提取为文本；聊天气泡内嵌缩略图。
- **TTS tool** — `tts_tool` speaks a workspace text file into audio via the device TextToSpeech engine. / 新增 `tts_tool`，可将工作区文本文件用系统 TTS 引擎合成为音频。
- **Per-project Soul settings** — each project carries its own `soul.md` persona file, editable in settings. / 支持按项目配置独立的 `soul.md` 人格文件，可在设置中编辑。
- Model catalogue enrichment — context window, max output tokens, vision support and pricing are read from `/models` and normalised (`ModelIdNormalizer`, `ModelVisionTable`). / 模型列表自动读取上下文长度、输出上限、视觉能力与价格并做归一化处理。

### Changed / 变更
- The agent loop now consumes the SSE streaming endpoint end to end; partial text is throttled to the UI at 50 ms intervals. / Agent 循环端到端切换至 SSE 流式接口，增量文本以 50ms 节流推送至 UI。
- `ModelProvider.generateStream` gained a default implementation that wraps the non-streaming `generate`, so third-party providers need only implement one entry point. / `ModelProvider.generateStream` 提供默认实现（包装非流式 `generate`），第三方 Provider 只需实现一个入口。
- Image attachments no longer store base64 in the session JSONL — only a blob reference is persisted. / 图片附件不再在会话 JSONL 中保存 base64，仅持久化 blob 引用。
- Bump the Android app version to 1.3.0 (version code 7). / 应用版本更新至 1.3.0（版本号代码 7）。

### Fixed / 修复
- SSE chunks with explicit JSON nulls (`"usage":null`, `"delta":null`) no longer crash the turn with `Element class JsonNull is not a JsonObject`. / 修复 SSE 数据块中显式 JSON null（`"usage":null` / `"delta":null`）导致整轮对话崩溃的问题。
- Foreground service notification no longer hangs after an aborted run. / 修复中止运行后前台服务通知残留的问题。

## [1.2.3]

### Changed / 变更
- Require confirmation before saving or updating a provider API key. / 保存或更新服务商 API Key 前增加二次确认。
- Bump the Android app version to 1.2.3 (version code 6). / 应用版本更新至 1.2.3（版本号代码 6）。
