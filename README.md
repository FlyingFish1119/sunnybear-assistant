# 🐻 SunnyBear Assistant

> 基于 Spring Boot 的 AI 助手系统，支持多模型对话、工具调用、Android 远程控制。

---

## 项目简介

SunnyBear Assistant 是一个**个人 AI 助手项目**，核心定位是**多模型、多工具、多端协同**的智能助理。它不仅能进行自然语言对话，还能操作文件系统、运行系统命令、控制浏览器、管理知识库与长期记忆、调度多步骤任务，甚至通过 Android 无障碍服务远程操控手机。

> 🐻 助手人设通过 Prompt 完全可配置——名字、性格、说话风格、自称都可以自由定义，不绑定任何固定形象。

---

## 项目结构

```
sunnybear-assistant/
├── sunnybear-assistant-core/       # 后端核心（Spring Boot 3.4.1 + Maven）
│   ├── src/main/java/              # Java 源码 (~335 个文件)
│   ├── src/main/resources/         # 配置 + 前端静态资源
│   ├── settings/                   # 运行时热加载配置文件
│   ├── data/                       # 数据目录（SQLite DB + 用户文件）
│   ├── tool-extension/             # 可扩展脚本工具目录
│   ├── deploy/                     # 部署配置（nginx）
│   └── pom.xml
│
└── sunnybear-assistant-android/    # Android 远程控制端
    └── app/src/main/
        ├── java/com/fishsunny/agent/   # 主活动 + 无障碍服务 + WebSocket 客户端
        ├── res/                        # 布局 + 资源
        └── AndroidManifest.xml
```

---

## 技术栈

### 后端 (sunnybear-assistant-core)

| 技术 | 用途 |
|------|------|
| **Java 17** + **Spring Boot 3.4.1** | 核心框架 |
| **SQLite** (sqlite-jdbc 3.49.1.0) | 数据持久化 |
| **Playwright 1.49.0** | 无头浏览器（网页截图/内容读取） |
| **Apache Tika 2.9.2** | 正文抽取（Web Reader 快速模式） |
| **Jsoup 1.18.1** | HTML 解析 |
| **Apache POI 5.2.5** | Office 文档处理（Word/Excel/PPT） |
| **Apache PDFBox 3.0.2** | PDF 处理 |
| **Caffeine** | 本地缓存 |
| **Nashorn 15.4** | JavaScript 脚本引擎 |
| **Jackson** | JSON/YAML 序列化 |
| **Lombok** | 代码简化 |

### 前端 (内嵌于 Core)

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

---

## 核心功能

### 1. 多模型 AI 对话

通过**适配器模式**统一接入多种 AI 模型，流式/非流式双模式，支持 Thinking 推理过程展示、模型动态切换、会话标题自动生成。具体使用哪些模型由 `settings/ai_settings.json` 配置决定。

### 2. 工具系统（12 大类，50+ 工具）

| 工具集 | 包含工具 | 说明 |
|--------|---------|------|
| **文件** | list / read / write / edit / delete / download | 完整文件操作 |
| **系统** | command / extension-script | 执行命令和自定义脚本 |
| **浏览器** | navigate / click / type / scroll / drag / screenshot / read-content / wait | Playwright 无头浏览器 |
| **图片** | caption / screen-capture | 图片描述 + 屏幕截图分析 |
| **网络** | web-search / web-reader | 搜索 + 正文提取 |
| **计算** | calculate | 数学表达式求值 |
| **键鼠** | mouse-move / mouse-click / mouse-scroll / keyboard-input | 本地桌面操控 |
| **记忆** | post-memory / delete-memory | 长期记忆管理 |
| **知识库** | post-knowledge / delete-knowledge | Wiki 词条管理 + Embedding 检索 |
| **任务** | create / read / list / delete / run / step-update | 多步骤异步任务调度 |
| **流程** | flow-test / wait-flow | 流程测试 + 延时 |
| **会话** | session-file / switch-model | 文件管理 + 模型切换 |
| **Android** | click / swipe / type / screenshot / get-ui-tree / launch-app / press-key / wait | 远程手机操控 |

### 3. Android 远程控制

通过 **无障碍服务 (AccessibilityService)** + **WebSocket 长连接**实现手机远程操控：

```
Core Server ←→ WebSocket ←→ Android App (Foreground Service)
                                    ↓
                          AccessibilityService
                                    ↓
                    点击 / 滑动 / 输入 / 截图 / 启动App / 系统按键
```

- 支持 Basic Auth 安全认证
- 断线自动重连（5 秒间隔）
- 前台通知栏保活
- 需要 Android 14+ 以支持截图

### 4. 角色系统（Character）

支持创建多个 AI 角色，每个角色拥有独立设定：

- 🎭 独立 Prompt 和 AI 参数
- 📚 专属词条库 (Glossary)
- 🎲 骰子工具
- ⚔️ 回合制战斗系统
- 🎨 自定义头像 + 主题色
- 🔒 Nginx Basic Auth 隔离访问

### 5. 知识库 & 长期记忆

- **知识库**：Wiki 式词条管理，通过 Embedding 做语义检索，自动匹配相关知识注入对话上下文
- **长期记忆**：对话过程中自动积累核心信息，注入系统提示词
- 会话级知识注入追踪，保证连续性

### 6. 任务调度

支持多步骤异步任务，AI 自动拆解 → 创建 → 执行 → 返回结果：

```
用户需求 → AI 拆解为步骤 → TaskCreate → TaskRun → 逐步执行 → 结果汇总
```

### 7. QQ Bot 集成

支持 OneBot 协议，通过 HTTP API 对接 QQ 机器人：
- 群聊/私聊消息收发
- 与 Web Chat 共享同一后端

### 8. 扩展脚本系统

`tool-extension/` 目录下放置 `.yaml` 脚本文件即可注册新工具，无需重启：

```yaml
name: system-info
description: 获取系统基本信息
type: powershell
script: |
  systeminfo | Select-String "OS Name|Memory|Processor"
```

支持类型：`cmd` / `powershell` / `python` / `bash`

---

## 数据库设计

共 11 张表，使用 SQLite：

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
| `character_info` | 角色信息 |
| `character_session_mapping` | 角色-会话映射 |
| `character_glossary` | 角色词条 |

---

## 快速开始

### 环境要求

- **JDK 17+**
- **Maven 3.8+**
- **Playwright 浏览器**（如使用浏览器工具）
- **Android Studio**（如编译 Android 端）
- **Nginx**（生产部署，可选）

### 1. 克隆项目

```bash
git clone <your-repo-url>
cd sunnybear-assistant/sunnybear-assistant-core
```

### 2. 配置环境变量

根据 `application.yml` 中注册的 AI 模型适配器，设置对应的 API Key 环境变量。例如：

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxx
# 其他模型按需配置...
```

### 3. 启动后端

```bash
cd sunnybear-assistant-core
mvn spring-boot:run
```

默认端口 **11451**，启动后访问：`http://localhost:11451`

### 4. 启动 Android 端

1. 用 Android Studio 打开 `sunnybear-assistant-android/`
2. 编译安装到手机
3. 在手机「设置 → 无障碍 → SunnyBear Agent」中开启服务
4. 在 App 中输入服务器地址并连接

### 5. 生产部署

参考 `deploy/nginx.conf`，使用 Nginx 反向代理，支持：
- 多角色路径隔离（`/bro/`、`/sis/`）
- Basic Auth 访问控制
- WebSocket 长连接代理
- 自定义域名绑定

---

## 配置说明

### 核心配置 (`application.yml`)

```yaml
server:
  port: 11451                          # 服务端口

assistant:
  file:
    base-path: data/                   # 数据文件根目录
  cache:
    max-size: 1000                     # 缓存最大条目
    expire-minutes: 10                 # 缓存过期时间

engine:
  tool:
    # 各工具集开关（true/false）
    os.enable: true
    net.enable: true
    file.enable: true
    browser.enable: true
    # ...
  adapter-register:
    # 在此注册 AI 模型适配器
```

### 热加载配置 (`settings/` 目录)

`settings/` 目录下的 JSON 文件支持运行时热加载，无需重启服务即可调整 AI 参数、工具行为、用户偏好等。

---

## 项目特点

- **多工具协同**：AI 可操作文件系统、浏览器、命令行、Android 设备等多种工具
- **适配器模式**：新增 AI 模型只需在配置文件中注册，无需修改代码
- **扩展脚本**：通过 YAML 脚本灵活添加工具能力
- **多角色支持**：同一套后端可运行多个独立 AI 角色
- **Android 远程操控**：通过无障碍服务实现手机远程控制
- **安全机制**：文件操作/下载有审查机制，命令执行有输出限制

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
│   ├── protocol/               # 请求/响应协议定义
│   └── tool/                   # 工具系统（框架 + 实例 + 执行器）
├── mvc/
│   ├── controller/             # REST 控制器
│   ├── dao/                    # 数据访问层
│   └── service/                # 业务逻辑层
├── plug/
│   ├── android/                # Android 桥接（WebSocket 服务 + 工具）
│   ├── character/              # 角色系统
│   └── qq/                     # QQ Bot (OneBot)
├── settings/                   # 配置热加载
├── utils/                      # 工具类（图片缩放、相似度计算等）
├── variable/                   # 常量定义
└── websocket/                  # WebSocket 聊天处理器
```

### 添加新 AI 模型

在 `application.yml` 的 `engine.adapter-register.register` 列表中添加新条目即可：

```yaml
- apiName: my-provider
  baseUrl: https://api.example.com/v1/chat/completions
  apyKey: ${MY_API_KEY}
  stream: true
  adapterCls: com.fishsunny.assistant.engine.adapter.text.TextStreamAIAdapter
  masterReqCls: com.fishsunny.assistant.engine.protocol.project.ChatRequest
  targetReqCls: com.fishsunny.assistant.engine.protocol.text.TextAIRequest
  masterRespCls: com.fishsunny.assistant.engine.protocol.project.ChatResponse
  targetRespCls: com.fishsunny.assistant.engine.protocol.text.TextStreamAIResponse
```

---

## License

个人项目，仅供学习交流。

---

> 🐻 *"随时为您效劳。"*
