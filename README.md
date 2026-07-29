# 🐻 SunnyBear Assistant

> 基于 Spring Boot 的 AI 助手系统，支持多模型对话、工具调用、Android 远程控制、ComfyUI 文生图。

---

## 项目简介

SunnyBear Assistant 是一个**个人 AI 助手项目**，核心定位是**多模型、多工具、多端协同**的智能助理。它不仅能进行自然语言对话，还能操作文件系统、运行系统命令、控制浏览器、管理知识库与长期记忆、调度多步骤任务，通过 Android 无障碍服务远程操控手机，甚至桥接本地 ComfyUI 进行 AI 绘画。

> 🐻 助手人设通过 Prompt 完全可配置——名字、性格、说话风格、自称都可以自由定义，不绑定任何固定形象。

---

## 项目结构

```
sunnybear-assistant/
├── sunnybear-assistant-core/           # 后端核心（Spring Boot 3.4.1 + Maven）
│   ├── src/main/java/                  # Java 源码 (~320 个文件)
│   ├── src/main/resources/
│   │   ├── static/                     # 前端静态资源（Vue 3 单页应用）
│   │   ├── sql/                        # 数据库初始化脚本
│   │   └── application*.yml           # Spring Boot 配置
│   ├── settings/                       # 运行时热加载配置文件
│   ├── data/                           # 数据目录（SQLite DB + 用户文件）
│   ├── tool-extension/                 # 可扩展脚本工具目录
│   ├── deploy/                         # 部署配置（Nginx）
│   └── pom.xml
│
├── sunnybear-assistant-android-agent/  # Android 远程控制端
│   └── app/src/main/
│       ├── java/com/fishsunny/agent/   # 主活动 + 无障碍服务 + WebSocket 客户端
│       ├── res/                        # 布局 + 资源
│       └── AndroidManifest.xml
│
└── sunnybear-assistant-comfyui-agent/  # ComfyUI 本地代理（可选）
    ├── src/main/java/                  # WebSocket 客户端 + ComfyUI HTTP 调用
    ├── agent-config.json               # 代理配置文件
    └── pom.xml
```

---

## 技术栈

### 后端 (sunnybear-assistant-core)

| 技术 | 用途 |
|------|------|
| **Java 17** + **Spring Boot 3.4.1** | 核心框架 |
| **Spring WebSocket** | 实时通信 |
| **SQLite** (sqlite-jdbc 3.49.1.0) | 数据持久化 |
| **Playwright 1.49.0** | 无头浏览器（网页截图/内容读取） |
| **Apache Tika 2.9.2** | 正文抽取（Web Reader 快速模式） |
| **Jsoup 1.18.1** | HTML 解析 |
| **Apache POI 5.2.5** | Office 文档处理（Word/Excel/PPT） |
| **Apache PDFBox 3.0.2** | PDF 处理 |
| **Caffeine** | 本地缓存 |
| **Nashorn 15.4** | JavaScript 脚本引擎 |
| **Jackson** | JSON/YAML 序列化 |
| **Thumbnailator 0.4.14** | 图片缩放处理 |
| **Lombok** | 代码简化 |

### AI 模型协议适配

通过**适配器模式**统一接入多种 AI 模型，支持三种协议：

| 协议类型 | 适配器 | 适用厂商 |
|---------|--------|---------|
| **Anthropic 原生** | `AnthropicStreamAIAdapter` / `AnthropicAIAdapter` | Anthropic（Claude 系列） |
| **Standard（OpenAI 兼容）** | `StandardStreamAIAdapter` / `StandardAIAdapter` | Moonshot、OpenAI 等 |
| **Text** | `TextStreamAIAdapter` / `TextAIAdapter` | DeepSeek、SiliconFlow 等 |

所有协议均支持流式/非流式双模式、Thinking 推理过程展示、工具调用（Tool Use）。

### 前端 (内嵌于 Core)

前端采用 CDN 方式加载，无需构建工具链。clone 下来直接 `java -jar` 即可跑起全栈，零额外依赖。

| 技术 | 用途 |
|------|------|
| **Vue.js 3** (CDN) | 响应式 UI |
| **Element Plus** | UI 组件库 |
| **Marked** | Markdown 渲染 |
| **KaTeX** | 数学公式渲染 |
| **Mermaid** | 图表/流程图渲染 |
| **highlight.js** | 代码高亮 |
| **Lucide** | 图标库 |

### Android 端

| 技术 | 用途 |
|------|------|
| **Java** + Android SDK (min 24 / target 35) | 原生开发 |
| **OkHttp 4.12.0** | WebSocket 长连接 |
| **Gson 2.11.0** | JSON 解析 |
| **AccessibilityService** | 无障碍远程操控 |

### ComfyUI Agent

| 技术 | 用途 |
|------|------|
| **Java 17** + Maven | 独立可执行 JAR |
| **OkHttp 4.12.0** | WebSocket 客户端 + HTTP 调用 |
| **Jackson 2.15.3** | JSON 解析 |

---

## 核心功能

### 1. 多模型 AI 对话

通过**适配器模式**统一接入多种 AI 模型，流式/非流式双模式，支持 Thinking 推理过程展示、模型动态切换、会话标题自动生成。

预设两种对话模式：
- **chat**：标准对话（默认使用 DeepSeek V4 Flash）
- **chat_pro**：专业模式（默认使用 DeepSeek V4 Pro），支持自动切换

具体使用哪些模型由 `settings/ai_settings.json` 配置决定，支持为不同场景（对话、摘要、标题、OCR、任务）指定不同的模型。

### 2. 工具系统（15 大类，60+ 工具）

| 工具集 | 包含工具 | 说明 |
|--------|---------|------|
| **文件** | list / read / write / edit / delete / download / search | 完整文件操作 + 文本搜索 |
| **系统** | command / extension-script | 执行命令和自定义脚本 |
| **浏览器** | navigate / click / type / scroll / drag / screenshot / read-content / wait | Playwright 无头浏览器 |
| **网络** | web-search / web-reader / explore | 搜索 + 正文提取 + 子链接探索 |
| **图片** | caption / screen-capture | 图片描述 + 屏幕截图分析 |
| **计算** | calculate | 数学表达式求值 |
| **键鼠** | mouse-move / mouse-click / mouse-scroll / keyboard-input | 本地桌面操控 |
| **记忆** | post-memory / delete-memory | 长期记忆管理 |
| **知识库** | post-knowledge / delete-knowledge / read / list | Wiki 词条管理 + Embedding 检索 |
| **任务** | create / read / list / delete / run / step-update | 多步骤异步任务调度 |
| **流程** | flow-test / wait-flow | 流程测试 + 延时 |
| **会话** | session-file / switch-model | 文件管理 + 模型切换 |
| **Android** | click / swipe / type / screenshot / get-ui-tree / launch-app / press-key / wait | 远程手机操控 |
| **ComfyUI** | generate / resources / view / workflow-list / workflow-detail | AI 绘画生成（可远程子 Agent 执行） |
| **角色工具** | 骰子 / 词条查询 / SQL 状态 / 战斗引擎 | 角色扮演辅助 |

### 3. Android 远程控制

通过 **无障碍服务 (AccessibilityService)** + **WebSocket 长连接**实现手机远程操控：

```
Core Server ←→ WebSocket ←→ Android App (Foreground Service)
                                    ↓
                          AccessibilityService
                                    ↓
          点击 / 滑动 / 输入 / 截图 / UI 树分析 / 启动App / 系统按键 / 等待文本
```

关键特性：
- 支持坐标点击 + 文本匹配点击双模式
- 支持长按手势（可自定义时长）
- 支持文本输入（优先焦点节点，支持 targetHint 查找输入框）
- UI 树遍历（可配置深度，含坐标 + 属性标注）
- 系统按键模拟（back / home / recents / notification / screenshot 等）
- 滚动操作 + 等待文本出现（轮询模式）
- Basic Auth 安全认证
- 断线自动重连
- 前台通知栏保活
- 截图需要 Android 14+

### 4. ComfyUI 文生图集成

通过独立的 **ComfyUI Agent JAR** 将本地 ComfyUI 实例桥接到远程 Server：

```
Core Server ←→ WebSocket ←→ ComfyUI Agent JAR ←→ HTTP ←→ 本地 ComfyUI
                                                          ↓
                                              workflow 目录下的工作流文件
```

支持的操作：
- **generate**：执行工作流生图（可传入 workflow JSON 替换节点参数）
- **resources**：查看 GPU/显存/磁盘资源使用情况
- **view**：查看生成图片的 Base64 结果
- **workflow-list**：列出可用工作流
- **workflow-detail**：查看工作流详情

命令行参数覆盖配置文件，支持 Basic Auth，生成超时可自定义（默认 30 分钟）。

> 也支持在同一台机器上以命令行方式直接调用 ComfyUI（无需 Agent JAR），通过 `comfyui-subagent` 工具自动编排生图流程。

### 5. 角色系统（Character）

支持创建多个 AI 角色，每个角色拥有独立设定：

- 🎭 独立 Prompt 和 AI 参数设置
- 📚 专属词条库 (Glossary) — AI 可查询/自动生成
- 🎲 骰子系统（D20 / NDM 自定义骰子）
- 🗄️ 独立 SQLite 数据库 — 运行时可执行 SQL 查询/写入
- ⚔️ 回合制战斗系统 — 状态机驱动的战斗引擎，支持 Buff / Skill / 行动声明
- 🎨 自定义头像 + 背景图 + 主题色
- 🔒 Nginx Basic Auth 隔离访问（每个角色独立端口/路径）
- 🔗 会话绑定机制（角色 ↔ 对话独立绑定）

### 6. 知识库 & 长期记忆

- **知识库**：Wiki 式词条管理，通过 Embedding 做语义检索（BGE-M3 模型），自动匹配相关知识注入对话上下文
- **长期记忆**：对话过程中自动积累核心信息，注入系统提示词
- 会话级知识注入追踪，保证连续性
- 相似度阈值可配置（默认 0.7）

### 7. 任务调度

支持多步骤异步任务，AI 自动拆解 → 创建 → 执行 → 返回结果：

```
用户需求 → AI 拆解为步骤 → TaskCreate → TaskRun → 逐步执行 → 结果汇总
```

- 每个步骤独立追踪状态
- 支持子 Agent 模式（任务执行时使用独立 AI 调用，与主对话隔离）
- 任务提示词模板可自定义

### 8. Agent 日志面板

前端提供 **Agent 运行日志侧边栏**，实时展示 AI 的工具调用过程：
- 调用链树形展示（step → tool call → sub-step）
- 每个节点展示耗时、状态（running / success / error）
- 支持同时查看多个并行 agent 的执行情况
- 折叠/展开细节控制

### 9. QQ Bot 集成

支持 OneBot 协议，通过 HTTP API 对接 QQ 机器人：
- 群聊/私聊消息收发
- 与 Web Chat 共享同一后端
- 支持多机器人实例（不同 reply-id）

### 10. 扩展脚本系统

`tool-extension/` 目录下放置 `.yaml` 脚本文件即可注册新工具，无需重启：

```yaml
name: system-info
description: 获取系统基本信息
type: powershell
parameters:
  - name: detail
    type: string
    description: 详细程度（summary/full）
    required: false
script: |
  chcp 65001 > $null
  $detail = "{{detail}}"
  systeminfo | Select-String "OS Name|Memory|Processor"
```

支持类型：`cmd` / `powershell` / `python` / `bash`，支持子目录递归扫描，参数使用 `{{placeholder}}` 占位符。

### 11. 前端特性

- **会话管理**：对话列表、会话重命名、删除、模型一键切换（普通 ↔ Pro）
- **消息操作**：消息分支切换（前进/后退，在 AI 回答的不同版本间浏览）、用户消息删除、AI 回复编辑
- **文件上传**：支持图片、文档等多格式文件上传
- **工具确认**：敏感操作（文件删除/下载等）弹窗确认
- **命令建议**：输入 `/` 触发工具命令自动补全
- **设置面板**：
  - AI 参数调整（model / temperature / thinking / prompt）
  - 工具行为开关与参数配置
  - 知识库 Embedding API 配置
  - 用户资料/头像/背景自定义
- **角色设置页面**：独立的前端页面管理角色的词条、战斗等

---

## 数据库设计

共 16 张表，使用 SQLite：

| 表名 | 说明 |
|------|------|
| `chat_session` | 对话会话 |
| `chat_message` | 对话消息（含推理内容、工具调用） |
| `chat_models` | 模型配置 |
| `knowledge_entry` | 知识库词条（含 Embedding 向量） |
| `session_knowledge` | 会话-知识映射 |
| `chat_memory` | 长期记忆 |
| `ai_greeting` | AI 问候语 |
| `task` | 异步任务 |
| `task_step` | 任务步骤 |
| `character_info` | 角色基础信息 |
| `character_session_mapping` | 角色-会话映射 |
| `character_glossary` | 角色词条 |
| `character_db_*` | 角色独立数据库（每角色一张，支持 SQL 操作） |
| `battle_state` | 战斗状态 |
| `battle_buff` | 战斗 Buff |
| `battle_skill` | 战斗技能 |

---

## 快速开始

### 环境要求

- **JDK 17+**
- **Maven 3.8+**
- **Playwright 浏览器**（如使用浏览器工具，需 `playwright install chromium`）
- **Android Studio**（如编译 Android 端）
- **ComfyUI**（如使用文生图功能，可选）
- **Nginx**（生产部署，可选）

### 1. 克隆项目

```bash
git clone <your-repo-url>
cd sunnybear-assistant
```

### 2. 配置 API Key

根据 `application.yml` 中注册的 AI 模型适配器，设置对应的环境变量：

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxx
export MOONSHOT_API_KEY=sk-xxxxxxxx
export SILICONFLOW_API_KEY=sk-xxxxxxxx
```

### 3. 启动后端

```bash
cd sunnybear-assistant-core
mvn spring-boot:run
```

默认端口 **11451**，启动后访问：`http://localhost:11451`

### 4. 启动 Android 端（可选）

1. 用 Android Studio 打开 `sunnybear-assistant-android-agent/`
2. 编译安装到手机
3. 在手机「设置 → 无障碍 → SunnyBear Agent」中开启服务
4. 在 App 中输入服务器地址并连接

### 5. 启动 ComfyUI Agent（可选）

```bash
cd sunnybear-assistant-comfyui-agent
mvn package -DskipTests
java -jar target/comfyui-agent-1.0.jar \
  --server ws://localhost:11451/comfyui-bridge \
  --comfyui http://127.0.0.1:8188 \
  --name "我的机器"
```

或直接编辑 `agent-config.json` 后运行。

## 配置说明

### 核心配置 (`application.yml`)

```yaml
server:
  port: 11451                          # 服务端口

spring:
  datasource:
    url: jdbc:sqlite:${assistant.file.base-path}assistant.db

assistant:
  file:
    base-path: data/                   # 数据文件根目录
  cache:
    max-size: 1000                     # 缓存最大条目
    expire-minutes: 10                 # 缓存过期时间
  chat:
    async:                             # 异步聊天线程池配置

# 插件配置
plug:
  qq:
    bot:                               # QQ Bot (OneBot)
  android:
    tool:
      enable: true                     # Android 远程控制开关
  comfyui:
    bridge:
      command-timeout: 1800            # ComfyUI 生图超时（秒）
    tool:
      enable: true                     # ComfyUI 工具开关

engine:
  tool:
    extension:
      dir: tool-extension              # 扩展脚本目录
    # 各工具集开关（true/false）
    os.enable: true
    net.enable: true
    file.enable: true
    browser.enable: true
    # ...
  adapter-register:
    register:
      # 在此注册 AI 模型适配器（apiName / baseUrl / apiKey / stream）
```

### 热加载配置 (`settings/` 目录)

`settings/` 目录下的 JSON 文件支持运行时热加载，无需重启服务即可调整 AI 参数、工具行为、用户偏好等。

| 文件 | 说明 |
|------|------|
| `ai_settings.json` | AI 场景配置（chat / chat_pro / summary / title / ocr / mission / task） |
| `assistant_settings.json` | 助手名称 + 头像 |
| `user_settings.json` | 用户信息 + 背景 + 主题色 + 自动切换模型开关 |
| `tool_settings.json` | 工具行为参数（命令超时、输出限制、搜索 API Key 等） |
| `knowledge_settings.json` | 知识库 Embedding API 配置（模型/URL/相似度阈值） |

### 角色配置 (`application-character.yml`)

角色系统有独立的工具开关配置，默认关闭危险工具（文件/网络/浏览器等），仅开放计算、角色专属工具（骰子/词条/SQL/战斗）。

---

## 添加新 AI 模型

在 `application.yml` 的 `engine.adapter-register.register` 列表中添加新条目即可：

```yaml
- apiName: my-provider
  baseUrl: https://api.example.com/v1/chat/completions
  apiKey: ${MY_API_KEY}
  stream: true
  adapterCls: com.fishsunny.assistant.engine.adapter.standard.StandardStreamAIAdapter
  masterReqCls: com.fishsunny.assistant.engine.protocol.project.ChatRequest
  targetReqCls: com.fishsunny.assistant.engine.protocol.standard.chat.StandardAIRequest
  masterRespCls: com.fishsunny.assistant.engine.protocol.project.ChatResponse
  targetRespCls: com.fishsunny.assistant.engine.protocol.standard.chat.StandardStreamAIResponse
```

- `apiName`：模型别名，用于 `ai_settings.json` 中的 `adapterName` 引用
- `apiKey`：支持 `${ENV_VAR}` 环境变量占位符
- `adapterCls`：根据 API 协议选择对应的适配器

---

## 开发说明

### 包结构概览

```
com.fishsunny.assistant
├── App.java                    # Spring Boot 入口
├── config/                     # 配置类（Cache / WebSocket / 异步）
├── dto/                        # 数据传输对象
├── engine/
│   ├── adapter/                # AI 模型适配器（Anthropic/Standard/Text 协议）
│   ├── protocol/               # 请求/响应协议定义（含 Anthropic/Standard/Text 三种协议）
│   └── tool/                   # 工具系统（框架 + 实例 + 执行器 + 扩展）
├── mvc/
│   ├── controller/             # REST 控制器
│   ├── dao/                    # 数据访问层
│   └── service/                # 业务逻辑层
├── plug/
│   ├── android/                # Android 桥接（WebSocket 服务 + 工具注册）
│   ├── character/              # 角色系统（Controller + Service + 工具注册）
│   ├── comfyui/                # ComfyUI 桥接（WebSocket 服务 + 工具注册）
│   └── qq/                     # QQ Bot (OneBot)
├── settings/                   # 配置热加载
├── utils/                      # 工具类（图片缩放、相似度计算等）
├── variable/                   # 常量定义
└── websocket/                  # WebSocket 聊天处理器
```

### 添加新工具

1. 在 `engine/tool/instance/` 下创建工具类，继承 `ToolHandler`
2. 在对应的 `*ToolKit.java` 中注册
3. 如需条件启用，在 `application.yml` 中添加开关
4. 工具会自动注入到 AI 系统提示词中

### 添加新插件模块

参考 `plug/` 下的现有模块结构：
1. 创建 `plug/xxx/` 包
2. 创建 Service（处理核心逻辑）
3. 创建 ToolKit + Tool（注册为 AI 工具）
4. 如需网络通信，创建 WebSocket Handler

---

## 项目特点

- **多协议适配**：支持 Anthropic 原生协议、OpenAI 兼容协议、Text 协议三种，新增模型只需配置
- **多工具协同**：AI 可操作文件系统、浏览器、命令行、Android 设备、ComfyUI 等多种工具
- **适配器模式**：新增 AI 模型只需在配置文件中注册，无需修改代码
- **扩展脚本**：通过 YAML 脚本灵活添加工具能力，支持 PowerShell/Python/Bash/cmd
- **多角色支持**：同一套后端可运行多个独立 AI 角色，每个角色拥有独立的数据库和工具集
- **Android 远程操控**：通过无障碍服务实现手机远程控制
- **ComfyUI 桥接**：支持远程/本地 ComfyUI 文生图，Agent JAR 可部署在 GPU 机器上
- **消息分支**：支持在 AI 回答的不同版本间切换
- **安全机制**：文件操作/下载有审查机制（auto / always-asked 模式），命令执行有输出限制

---

## License

[MIT License](LICENSE) © 2026 FlyingFish1119

---

> 🐻 *"随时为您效劳。"*
