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
| `chat_project_id` | string | 项目会话绑定的项目 ID | `ChatProjectBindings.EXTENSION_KEY` | 前端随新建请求下发；`ChatProjectService.attachSession()` 合并写入 |

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
- 项目：`mvc/service/ChatProjectBindings.java`（key = `chat_project_id`）。
  与上面两个的关键差别是**不另开 type** —— 项目会话仍然是普通 `chat` 会话，只多挂一个项目指针，
  否则它就从普通对话列表里消失了。值必须是字符串：按 extension 反查走 `json_extract`，
  存成数字取出来是整数，跟字符串参数比较会静默不相等；解绑写空串（`ChatProjectBindings.UNBOUND`）。
- 以上三个键都由前端在新建会话请求里通过 `request.extension` 整体下发，
  后端 `CreateChatMessageRequestHandler.createChatSession()` 写入。
  项目另有两条后置写入路径：Jev 判中后 `attachJudgedProject()`、会话列表右键「归入项目」，
  都走 `ChatProjectService.attachSession()` → `mergeExtension()` 合并写入。
- `chat_project_id` 有一个**只在请求里存在**的哨兵值 `-1`（`ChatProjectBindings.FORCE_UNBOUND`）：
  语义是「这条会话强制不绑定项目（散会话）」。`createChatSession()` 落库前会把这个键摘掉，
  `judgeProjectTask()` 见到它直接跳过 Jev 归属判定，因此它**永远不会写进 `chat_session.extension`**。
  别把它和 `UNBOUND`（解绑后写进库的空串，键在值为空）混为一谈。

## 注意事项

- 不要与 `ChatMessage.extension` 混淆。`chatSelect`、`ttsAudio`、`status`、`audios`、`chat_usage`
  等属于**消息**的 extension，不是会话的。
- 新增 key 时请在此表登记，并优先复用 `mergeExtension` 合并写入，避免整体覆盖抹掉其它消费方的字段。
- 请求侧有两个同族字段，别混：`ChatMessageRequest.extension` 是本表的口袋（新会话初始值，只有 create 读）；
  `ChatMessageRequest.messageExtension` 是**消息**的口袋（本条用户消息的 extension，
  create / append / edit 读，见 `CHAT_MESSAGE_EXTENSION.md`）。
