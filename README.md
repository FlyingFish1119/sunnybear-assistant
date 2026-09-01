# 🐻 SunnyBear Assistant

> 给自己用的 AI 助手。多模型对话 + 工具系统,一个 Spring Boot jar 跑全栈。

## 这是什么

SunnyBear Assistant 是我自己写的个人 AI 助手。一开始只是想有个能聊天的窗口,后来陆续加了工具,就成了现在的样子。

核心想法很简单:让 AI 不只能聊天,还能动手干活。它可以读写文件、执行命令、开浏览器查资料、维护知识库、记长期记忆、把复杂需求拆成步骤一步步做。一切都在本地运行,数据落在 SQLite 和文件里。

助手的人设完全可配置:名字、性格、说话方式都在 `settings/` 里改,不绑定固定形象。

它是个人项目:代码按我顺手的方式组织,文档只覆盖实际用到的部分。你可以看看、改改,但别指望它像商业产品那样完善。

## 功能

- **多模型对话**。适配器模式接入三种协议:Anthropic 原生、OpenAI 兼容、各家 Text 协议,流式输出、思考过程展示、工具调用都支持。不同场景可以配不同模型——普通对话、专业模式、OCR、网页摘要、标题生成各用各的,轻活用不上重模型。默认配置里普通对话用 DeepSeek V4 Flash,专业模式用 V4 Pro。
- **工具系统**。几十个工具按类目组织:文件读写搜索、命令行、Playwright 浏览器(导航/点击/截图/读正文)、网页搜索与正文提取、图片描述、桌面键鼠、定时任务、知识库、记忆、多步任务、MCP 客户端,以及计算、流程、会话等小工具。每个工具集都有独立开关、超时和安全输出限制。
- **知识库与长期记忆**。知识库是 Wiki 式词条,对话时自动筛出相关内容注入上下文(会话级去重),词条检索用 Embedding(BGE-M3);长期记忆会沉淀对话中的关键信息,注入系统提示词。
- **任务调度**。AI 把需求拆成多步任务,逐步执行、逐步汇报;支持子 Agent 模式,子任务用独立的 AI 调用,和主对话互不干扰。
- **扩展脚本**。往 `tool-extension/` 丢一个 YAML 就注册一个新工具,支持 cmd / powershell / python / bash,自动扫描,不用重启。
- **前端**。Vue 3 单页应用,静态文件内嵌在 `static/`,没有构建步骤。会话管理、模型一键切换、消息分支(在 AI 的不同版本回复之间切换)、文件上传、敏感操作弹窗确认、`/` 命令补全、Agent 运行日志面板(实时展示工具调用链)。

## 快速开始

需要 **JDK 21** 和 **Maven**。

```bash
# 1. 配置模型 API Key(application.yml 中引用的环境变量)
export DEEPSEEK_API_KEY=sk-xxx
export MOONSHOT_API_KEY=sk-xxx
export SILICONFLOW_API_KEY=sk-xxx

# 2. 启动
cd sunnybear-assistant-core
mvn spring-boot:run
```

启动后访问 <http://localhost:11451>。数据(数据库、上传文件)落在 `data/`,配置落在 `settings/`,两者都支持运行时热加载。

打包运行:`mvn package` 后 `java -jar target/sunnybear-assistant-core-1.0.jar`。

浏览器工具需要先安装 Chromium:`playwright install chromium`。

## 配置

`settings/` 下的 JSON 文件运行时热加载,改完即生效:

| 文件 | 作用 |
|------|------|
| `ai_settings.json` | 各场景(对话/专业/OCR/任务/标题等)的模型与参数、助手 prompt |
| `assistant_settings.json` | 助手名称、头像 |
| `user_settings.json` | 用户信息、背景、主题色、自动切换模型开关 |
| `tool_settings.json` | 工具开关、命令超时、输出大小限制、搜索 API Key |
| `knowledge_settings.json` | 知识库 Embedding 配置(模型、URL、相似度阈值) |

新模型不用写代码:在 `application.yml` 的 `engine.adapter-register.register` 加一条(协议、baseUrl、apiKey、是否流式)即可,运行时会热加载。

## 目录结构

```
sunnybear-assistant-core/
├── src/main/java/com/fishsunny/assistant/
│   ├── engine/          # AI 适配器、协议、工具系统
│   ├── websocket/       # 聊天 WebSocket 处理
│   ├── mvc/             # REST 控制器、DAO、Service
│   └── ...
├── src/main/resources/
│   ├── static/          # 前端(Vue 3 单页,无构建步骤)
│   └── sql/             # SQLite 建表脚本,启动时自动执行
├── settings/            # 热加载配置
├── data/                # 运行时数据(SQLite + 用户文件)
└── tool-extension/      # 扩展脚本工具
```

## 技术栈

Java 21 · Spring Boot 3.4 · SQLite · Playwright · Apache Tika / POI / PDFBox · Caffeine · Vue 3(Element Plus,本地引入)

## License

[MIT](LICENSE) © 2026 FlyingFish1119
