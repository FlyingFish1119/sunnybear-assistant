# ChatSession.extension 字段使用说明

`chat_session.extension` 是一个 JSON 对象列（实体：`ChatSession.extension`，`Map<String, Object>`）。
语义由各消费方自行约定，核心层不解析、不解释；为 `null` 表示未设置。

写入约定：修改 extension 一律走 `ChatSessionService.mergeExtension()`（append-only，只覆盖传入的键，
保留其它消费方的字段，且不动 `update_time`）。唯一例外是**新建会话**时，会把请求携带的 extension 整体写入。

## 当前使用的 key

| key | 类型 | 含义 | 定义 / 读 | 写 |
|---|---|---|---|---|
| `chat_prompt_tokens` | number | 累计输入 token | `ChatUsage.SESSION_PROMPT_TOKENS` | `StandardChatTranslateFactory.accumulateSessionUsage()` |
| `chat_completion_tokens` | number | 累计输出 token | `ChatUsage.SESSION_COMPLETION_TOKENS` | 同上 |
| `chat_total_tokens` | number | 累计总 token | `ChatUsage.SESSION_TOTAL_TOKENS` | 同上 |
| `chat_cached_tokens` | number | 累计命中缓存输入 token | `ChatUsage.SESSION_CACHED_TOKENS` | 同上 |
| `chat_reasoning_tokens` | number | 累计思考 token | `ChatUsage.SESSION_REASONING_TOKENS` | 同上 |
| `chat_rounds` | number | 累计 AI 调用轮次（含工具子轮） | `ChatUsage.SESSION_ROUNDS` | 同上 |
| `chat_mark` | array | 步骤清单 `List<Mark>` | `MarkStore.EXTENSION_KEY` | `MarkStore.save()` |
| `worldId` | string | 世界观会话绑定的世界观 ID | `WorldSessionBindings.EXTENSION_KEY` | 前端 `world_index.html` 随新建请求下发 |
| `characterId` | string | 角色会话绑定的角色 ID | `CharacterSessionBindings.EXTENSION_KEY` | 前端 `character_index.html` 随新建请求下发 |

## 分类说明

### 1. 核心层：token 用量累计（`chat_` 前缀）

- 定义：`engine/protocol/project/ChatUsage.java`
- 写入：`websocket/processor/StandardChatTranslateFactory.java` 的 `accumulateSessionUsage()`
  每轮落库后合并写入，缺失或非数字按 0 起算。
- 前端消费：`static/utils/token-format.js`、`static/components/chat-sidebar/chat-sidebar.js`。

### 2. 核心工具：步骤清单

- 定义与读写：`engine/tool/service/mark/MarkStore.java`（key = `chat_mark`）。
- 前端恢复：`static/components/send-area/mark-tracker/mark-tracker.js` 从
  `sessionStore.currentSession.extension.chat_mark` 读取。

### 3. 插件层：资源绑定

- 世界观：`plug/world/service/WorldSessionBindings.java`（key = `worldId`），
  按 `type + extension.worldId` 查询会话（`selectByTypeAndExtensionValue`）。
- 角色：`plug/character/service/CharacterSessionBindings.java`（key = `characterId`），
  按 `type + extension.characterId` 查询会话。
- 这两个键由前端在新建会话请求里通过 `request.extension` 下发，
  后端 `CreateChatMessageRequestHandler.createChatSession()` 写入。

## 注意事项

- 不要与 `ChatMessage.extension` 混淆。`chatSelect`、`ttsAudio`、`status`、`audios`、`chat_usage`
  等属于**消息**的 extension，不是会话的。
- 新增 key 时请在此表登记，并优先复用 `mergeExtension` 合并写入，避免整体覆盖抹掉其它消费方的字段。
