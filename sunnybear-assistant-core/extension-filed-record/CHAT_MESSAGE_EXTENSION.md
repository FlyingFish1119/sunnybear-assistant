# ChatMessage.extension 字段使用说明

`chat_message.extension` 是一个 JSON 对象列（实体：`ChatMessage.extension`，`Map<String, Object>`，
默认空 `HashMap`，永不为 `null`）。语义由各消费方自行约定，核心层不解析、不解释。

与 `ChatSession.extension` 不同，消息的 extension **没有统一的合并入口**：各写入方直接操作
`message.getExtension().put(...)`，落库时由 `ChatMessageRepositoryImplement` 与 TEXT 列互转。
编辑 assistant 消息只重建 `contents`，extension 原样保留。

## 当前使用的 key

| key | 类型 | 含义 | 定义 | 写 | 读 |
|---|---|---|---|---|---|
| `chat_usage` | object | 本轮各项 token 用量 | `ChatUsage.MESSAGE_KEY` | `StandardChatTranslateFactory` | `ContextCompressor`、前端 |
| `reasoningSignature` | string | 推理签名（Anthropic extended thinking / Gemini thought signature） | `ChatMessage.EXTENSION_REASONING_SIGNATURE` | `StandardChatTranslateFactory` | `ChatMessage.resolveReasoningSignature()` |
| `ttsAudio` | object | 整轮完整 TTS 音频，供 🔊 重播 | — | `StandardChatTranslateFactory` | 前端 |
| `chatSelect` | object | 角色快捷选项 | — | `CharacterChatSelectService` | 前端 |
| `status` | string | 工具执行中占位状态（值 `executing`） | — | `ToolExecuteNotifier` | 前端 |
| `audios` | array | 流式 TTS 逐句音频帧 | — | 前端（实时） | 前端 |

## 分类说明

### 1. `chat_usage` —— 本轮 token 用量

- 写入：`StandardChatTranslateFactory.java`，取 `TokenUsage.toMap()`（snake_case，跳过 null）：
  `prompt_tokens` / `completion_tokens` / `total_tokens` / `cached_tokens` / `reasoning_tokens`。
- 后端读取：`ContextCompressor.latestPromptTokens()`，用于上下文压缩阈值判断。
- 前端读取：`message-area-tokens.js`（每轮消耗徽章）、`message-topbar.js` / `ctx-gauge.js`
  （最近一次真实输入 token）。
- 同一轮的会话累计值另写入 `ChatSession.extension` 的 `chat_*` 键，勿混淆。

### 2. `reasoningSignature` —— 推理签名

- 定义：`ChatMessage.java:51`（`EXTENSION_REASONING_SIGNATURE`）。
  因 `chat_message` 表无对应列，签名随 extension 落库，会话重载后仍能回传模型。
- 写入：`StandardChatTranslateFactory.java`，赶在 `appendAssistantMessage` 落库前写入。
- 读取：`ChatMessage.resolveReasoningSignature()` 字段优先，回退到 extension。

### 3. `ttsAudio` —— 整轮完整音频

- 结构：`{"audio": <base64>, "format": <格式>}`。
- 写入：`StandardChatTranslateFactory.java`（逐句帧只是实时通道，不持久化）。
- 前端读取：`message-area-assistant.js`（是否显示 🔊）、`send-area.js`（历史/刷新后重播）。

### 4. `chatSelect` —— 角色快捷选项

- 结构：`{"title": <可选>, "options": [<字符串>, ...]}`。
- 写入：`CharacterChatSelectService.applyChatSelect()`，不进正文，编辑不受影响。
- 前端读取：`character_index.html` 回填 `msg.chatSelect`，由 `character-chat-select` 组件展示。

### 5. `status` —— 工具执行中占位

- 值：`executing`。`ToolExecuteNotifier` 推送的 **实时占位消息** 携带（不落库），
  前端 `session-store.js` 也据此插入占位；工具结果返回后按 `toolCallId` 替换。
- 前端读取：`message-area-tool.js`（渲染"执行中…"黄点），`session-store.js`
  用 `extension.status === 'executing'` 判断占位。

### 6. `audios` —— 流式 TTS 逐句帧

- 前端 `session-store.js` 处理 `TTS_AUDIO` 帧时注入当前流式 assistant 消息的 extension，
  保序入队实时播放；**仅前端实时用，不落库**（落库的是整轮 `ttsAudio`）。

## 注意事项

- 不要与会话的 extension 混淆。`worldId`、`characterId`、`chat_mark`、`chat_total_tokens`
  等属于 **`ChatSession.extension`**（见 `CHAT_SESSION_EXTENSION.md`）。
- 新增 key 时请在此表登记；写入方在落库前如需加工，可用
  `ChatProvider.getBeforeSaveAssistantProvider()` 钩子，但注意钩子可能替换消息对象。
