package com.fishsunny.assistant.websocket.slash.instance;

/*
 * @Usage /init <path> —— 探索本机目录，生成结构化项目文档，落进「项目」：
 *        项目名取目录名，已有同名项目直接复用（没有才新建），报告写进该项目的 PROJECT.md，
 *        再让 CUB 按这份文档刷一条简短描述写进项目的 description，
 *        并把本次探索的会话一并归入该项目。不往核心文件里塞副本。
 *        直接复用标准对话流程（StandardChatTranslateFactory + ChatHttpHandler.translate），
 *        探索过程以与主对话一致的流式/状态机/工具气泡实时呈现在消息区。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/24
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.cancel.ChatCancelContext;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatProject;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.instance.file.FileListTool;
import com.fishsunny.assistant.engine.tool.instance.file.FileReadTool;
import com.fishsunny.assistant.engine.tool.instance.file.FileSearchTool;
import com.fishsunny.assistant.engine.tool.instance.view.ViewCaptionTool;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.mvc.service.ChatProjectBindings;
import com.fishsunny.assistant.mvc.service.ChatProjectService;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.utils.ObjectUtils;
import com.fishsunny.assistant.utils.ProjectFileManager;
import com.fishsunny.assistant.websocket.provider.ChatProvider;
import com.fishsunny.assistant.websocket.processor.StandardChatTranslateFactory;
import com.fishsunny.assistant.websocket.slash.framework.SlashCommandComponent;
import com.fishsunny.assistant.websocket.slash.framework.SlashCommandHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@SlashCommandComponent("/init")
public class InitSlashCommandHandler extends SlashCommandHandler {

    /** 子 Agent 可用的只读文件工具 */
    private static final Set<String> SUB_AGENT_TOOLS = Set.of(
            FileListTool.NAME,
            FileSearchTool.NAME,
            FileReadTool.NAME,
            ViewCaptionTool.NAME
    );

    private final ChatMessageService chatMessageService;

    private final ObjectMapper objectMapper;

    private final AssistantSettings assistantSettings;

    private final ChatProjectService chatProjectService;

    private final ProjectFileManager projectFileManager;

    private final ChatHttpHandler chatHttpHandler;

    private final StandardChatTranslateFactory standardChatTranslateFactory;

    private final ToolExecutor toolExecutor;

    private final AISettings chatAISettings;

    private final AISettings chatProAISettings;

    private final AISettings cubAISettings;

    /** 本次探索的项目根目录（供落盘钩子取项目名与文档头） */
    private Path projectDir;

    public InitSlashCommandHandler(ChatMessageService chatMessageService,
                                   ObjectMapper objectMapper,
                                   AssistantSettings assistantSettings,
                                   ChatProjectService chatProjectService,
                                   ProjectFileManager projectFileManager,
                                   ChatHttpHandler chatHttpHandler,
                                   StandardChatTranslateFactory standardChatTranslateFactory,
                                   @Lazy ToolExecutor toolExecutor,
                                   @Qualifier(AISettings.CHAT) AISettings chatAISettings,
                                   @Qualifier(AISettings.CHAT_PRO) AISettings chatProAISettings,
                                   @Qualifier(AISettings.CUB) AISettings cubAISettings) {
        this.chatMessageService = chatMessageService;
        this.objectMapper = objectMapper;
        this.assistantSettings = assistantSettings;
        this.chatProjectService = chatProjectService;
        this.projectFileManager = projectFileManager;
        this.chatHttpHandler = chatHttpHandler;
        this.standardChatTranslateFactory = standardChatTranslateFactory;
        this.toolExecutor = toolExecutor;
        this.chatAISettings = chatAISettings;
        this.chatProAISettings = chatProAISettings;
        this.cubAISettings = cubAISettings;
    }

    @Override
    protected List<String> resolveArgs(String originArgs) {
        // 整串作为路径：Windows 路径可能含空格，不能按空白切分
        String path = originArgs == null ? "" : originArgs.trim();
        return List.of(path);
    }

    @Override
    protected void handle(List<String> args) throws Exception {
        String path = CollectionUtils.isEmpty(args) ? "" : args.getFirst();

        if (!StringUtils.hasText(path)) {
            handleMessage("""
                    **用法**：`/init <path>`
                    
                    传入要探索的本机目录路径，例如 `/init E:\\local\\my-project`，\
                    探索完成后会生成结构化项目文档：按目录名建好项目（已有同名项目则复用），\
                    文档写进该项目的 PROJECT.md，并把本次会话归入该项目。""");
            return;
        }

        Path dir;
        try {
            dir = Path.of(path).toAbsolutePath().normalize();
        } catch (Exception e) {
            handleMessage("**路径无效**：`" + path + "` 不是合法的目录路径。");
            return;
        }
        if (!Files.isDirectory(dir)) {
            handleMessage("**目录不存在**：`" + path + "` 不是一个可访问的目录。");
            return;
        }
        this.projectDir = dir;

        // 探索用与主对话一致的标准流程：仅挂只读文件工具
        List<StandardToolRegister> fileTools =
                StandardToolRegister.buildToolRegisterByHandlers(toolExecutor, SUB_AGENT_TOOLS);

        // 以持久化的用户消息为父节点，保证助手/工具消息挂进同一条消息链
        ChatMessage originUser = ObjectUtils.getLast(messages);
        ChatMessage instruction = new ChatMessage().user(buildUserPrompt(dir));
        if (originUser != null) {
            instruction.setId(originUser.getId());
        }

        List<ChatMessage> requestMessages = new ArrayList<>();
        requestMessages.add(new ChatMessage().system(buildSystemPrompt()));
        requestMessages.add(instruction);

        AISettings settings = Boolean.TRUE.equals(chatSession.getEnablePro()) ? chatProAISettings : chatAISettings;
        ChatRequest request = new ChatRequest()
                .loadSettings(settings)
                .setMessages(requestMessages)
                .setTools(fileTools);

        // 落盘钩子：末轮（无工具调用）的助手消息即最终报告，写入核心文件并在正文补保存提示
        ChatProvider chatProvider = new ChatProvider()
                .setBeforeSaveAssistantProvider(this::persistReport);

        StandardChatTranslateFactory.ReActDependence dependence =
                new StandardChatTranslateFactory.ReActDependence(assistantSettings, session);
        StandardChatTranslateFactory.ReActOption option =
                new StandardChatTranslateFactory.ReActOption(chatProvider);
        List<ChatMessage> collector = new ArrayList<>();
        StandardChatTranslateFactory.ReActData data =
                new StandardChatTranslateFactory.ReActData(collector, chatSession, request);

        while (!ChatCancelContext.isCancelled()) {
            ChatHttpHandler.InTranslateCallback inTranslate = standardChatTranslateFactory.createInTranslateCallback(dependence, data);
            ChatHttpHandler.CompleteCallback complete = standardChatTranslateFactory.createOnCompleteCallback(dependence, option, data);

            ChatHttpHandler.TranslateData translateData = new ChatHttpHandler.TranslateData(chatSession.getId(), settings.getAdapterName(), request);
            ChatHttpHandler.TranslateOption translateOption = new ChatHttpHandler.TranslateOption()
                    .setStream(settings.getStream())
                    .setEnableTTS(false);
            ChatHttpHandler.TranslateHandler translateHandler = new ChatHttpHandler.TranslateHandler(inTranslate, complete);

            // 返回是否有工具调用：无工具调用即本轮探索结束
            if (!chatHttpHandler.translate(translateData, translateHandler, translateOption)) {
                break;
            }
        }

        // 标准流程已自行落库/推送，这里回填返回值供上游使用（如新会话标题生成）
        if (collector.isEmpty()) {
            handleMessage("**探索已中止**：`/init " + path + "` 未生成报告。");
        } else {
            resultMessage.addAll(collector);
        }
    }

    /**
     * 末轮助手消息（无工具调用）落库前：报告写进项目、会话归入项目，并在正文追加保存提示。
     * 带工具调用的中间轮不处理。
     * <p>项目名取目录名：已有同名项目直接复用（文档视为刷新），没有才新建。
     * 项目目录只加在写入的文件开头，消息正文不重复。
     */
    private ChatMessage persistReport(ChatMessage message) {
        if (!CollectionUtils.isEmpty(message.getToolCalls())) {
            return message;
        }
        String report = message.resolveBodyText();
        if (!StringUtils.hasText(report)) {
            return message;
        }
        try {
            ChatProject project = resolveOrCreateProject(resolveProjectName(projectDir));
            // 同名覆盖：同一个目录再 init 一次，就是刷新这个项目的 PROJECT.md
            projectFileManager.writeProjectText(project.getId(), ChatProjectBindings.PROJECT_FILE_NAME,
                    "> 项目目录：`" + projectDir + "`\n\n" + report.strip());
            // 项目文档进「自动追加」：以后这个项目里新建的会话，一开口就带着它（幂等，反复 init 不会重复）
            chatProjectService.updateAutoAppendFile(project.getId(), ChatProjectBindings.PROJECT_FILE_NAME, true);
            // 顺手让 CUB 按文档写一句简短描述落到项目上（也当 Jev 判归属时的线索）
            refreshProjectDescription(project, report);
            // 会话归入项目：mergeExtension 只覆盖这一个键，反复 init 也幂等
            if (chatSession != null && StringUtils.hasText(chatSession.getId())) {
                chatProjectService.attachSession(chatSession.getId(), project.getId());
            }
            message.bodyText(report.strip() + "\n\n---\n\n> 📁 已保存到项目 **" + project.getName()
                    + "** 的 `" + ChatProjectBindings.PROJECT_FILE_NAME + "`");
        } catch (Exception e) {
            log.error("/init 报告写入项目失败: {}", e.getMessage(), e);
            message.bodyText(report.strip() + "\n\n---\n\n> ⚠️ 报告写入项目失败：" + e.getMessage());
        }
        return message;
    }

    /**
     * 让 CUB 按项目文档写一句简短描述，落到项目的 description 上。
     * <p>描述不只是给人看的：Jev 判断「这条新消息属不属于这个项目」吃的就是项目名 + 描述，
     * 所以刷新文档时顺手把它也刷了。生成失败就保留原描述，不拦主流程。
     */
    private void refreshProjectDescription(ChatProject project, String report) {
        String description = generateDescription(report);
        if (!StringUtils.hasText(description)) {
            return;
        }
        try {
            chatProjectService.update(project.getId(), project.getName(), description, null);
            project.setDescription(description);
            log.info("项目「{}」描述已更新: {}", project.getName(), description);
        } catch (Exception e) {
            log.warn("写入项目描述失败 projectId={}: {}", project.getId(), e.getMessage());
        }
    }

    /**
     * 调 CUB 生成一句话的项目描述；失败或返回空时返回 null（调用方保留原描述）。
     * <p>与知识库生成简介是同一套写法（见 KnowledgeServiceImplement#generateIntro）。
     */
    private String generateDescription(String report) {
        String prompt = """
                你是一名项目档案编辑。请根据下面的项目文档，为这个项目写一段简短的【项目描述】。

                要求：
                1. 控制在 50 字以内，一句话说清这个项目是做什么的。
                2. 点出项目名称与核心主题 —— 这段描述后续要用来判断「用户的新问题是否属于这个项目」。
                3. 直接输出描述文本，不要加引号、不要加前缀或解释，也不要换行。
                """;
        try {
            ChatRequest request = new ChatRequest().quickBuild(prompt, report, cubAISettings);
            AtomicReference<String> description = new AtomicReference<>();
            chatHttpHandler.translate(
                    new ChatHttpHandler.TranslateData(UUID.randomUUID().toString(), cubAISettings.getAdapterName(), request),
                    new ChatHttpHandler.TranslateHandler(null, (result, lastRes) -> {
                        if (result != null && StringUtils.hasText(result.content())) {
                            description.set(result.content().trim());
                        }
                    }),
                    new ChatHttpHandler.TranslateOption().setStream(cubAISettings.getStream()));
            return description.get();
        } catch (Exception e) {
            log.warn("生成项目描述失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 按名字取项目：已有同名项目直接复用，没有才新建。
     * <p>重名判断走 listAll()：项目是个人量级（几个到几十个），不值得为它单开一个按名查的接口。
     * 颜色传 null，交给 service 从色板自动分配。
     */
    private ChatProject resolveOrCreateProject(String name) {
        for (ChatProject project : chatProjectService.listAll()) {
            if (name.equalsIgnoreCase(project.getName())) {
                return project;
            }
        }
        return chatProjectService.create(name, "由 /init 探索目录 " + projectDir + " 生成", null);
    }

    private String resolveProjectName(Path dir) {
        if (dir == null) {
            return "project";
        }
        Path fileName = dir.getFileName();
        if (fileName != null && StringUtils.hasText(fileName.toString())) {
            return fileName.toString();
        }
        return "project";
    }

    private void handleMessage(String content) {
        ChatMessage msg = new ChatMessage()
                .assistant(content, "", List.of())
                .makeInsertable(chatSession.getId(), ChatMessage.getParentId(messages), assistantSettings.getAssistantName());
        super.insertMessage(msg, chatMessageService);
        super.sendMessage(msg, objectMapper);
        super.resultMessage.add(msg);
    }

    // ==================== 提示词 ====================

    private String buildSystemPrompt() {
        return """
                你是一个项目文档生成助手。你的职责是围绕给定的项目根目录，调用工具了解其结构、阅读关键文件，\
                最终输出一份可直接保存为 PROJECT.md 的结构化项目文档。

                ## 可用工具
                - **file_list_tool** — 浏览目录，列出文件和子目录（递归、glob 过滤、分页）
                - **file_search_tool** — 在目录内按内容搜索文件（支持关键词/正则、glob 过滤、上下文行）
                - **file_read_tool** — 读取文件内容（支持多文件与行范围）

                ## 每轮工作规则（必须严格遵守）
                1. 在每一轮中，先**简短评估当前进展**（不超过 20 字），然后调用一个或多个工具。
                2. 说明后立即调用工具，不要在中间输出长篇分析。
                3. 如果上一轮结果不理想，**换策略**：换搜索关键词、换子目录、换过滤条件。
                4. 前面各轮的工具结果都在对话上下文中，下一轮可直接看到；最终文档由你自行汇总，**不会自动附加工具调用明细附录**。

                ## 探索策略
                - **只围绕目标项目根目录活动**，不要漫游到无关的系统目录
                - 先用 file_list_tool 从 depth=1 开始逐层了解目录结构（不要一次深度拉满大目录）
                - 优先精读：README / 构建清单（pom.xml、package.json、build.gradle、go.mod、Cargo.toml 等）/ 入口文件 / 配置样例
                - 需要在大量文件里定位内容时，用 file_search_tool 搜关键词/正则
                - 大文件用 file_read_tool 指定行范围，避免整段贴出
                - 目录或文件不存在/不可读时，如实说明并停止该分支，**禁止臆造内容或路径**
                - 多轮仍无新发现即可停止，不要无限加深度

                ## 最终文档格式
                当信息收集充分时，**停止调用工具**，输出最终文档（Markdown，可直接作为 PROJECT.md 保存）：

                # {项目名}

                ## 项目概述
                一段话说明这个项目是做什么的、解决什么问题。

                ## 技术栈
                语言、框架、关键依赖、构建工具（以实际读到的清单文件为准）。

                ## 目录结构
                以缩进树展示主要目录及一句话说明各目录职责（只列关键层级，不逐文件罗列）。

                ## 核心模块
                ### 1. {模块名}
                职责、关键文件、模块间关系（引用具体文件路径）。
                ### 2. {模块名}
                ...

                ## 构建与运行
                从 README / 脚本 / 配置中读到的安装、构建、启动方式；读不到就写「未在仓库中找到」。

                ## 关键入口
                - {文件路径} — 一句话说明（可含行号）

                ## 引用文件
                - {文件路径} — 一句话说明

                ## 探索范围与限制
                - 覆盖了哪些子目录 / 哪些未深入
                - 不确定或未验证的内容（若有）

                ## 严禁行为
                - 禁止编造未读到的文件内容
                - 禁止在非最终轮输出完整文档
                - 禁止读取目标目录之外、与项目无关的敏感系统文件
                - 文档中的每一条信息都必须来自工具返回的实际内容""";
    }

    private String buildUserPrompt(Path dir) {
        return """
                [项目根目录]
                %s

                开始探索。先了解整体结构，再精读清单/README/入口等关键文件，信息充足后输出最终的 PROJECT.md 文档。"""
                .formatted(dir.toString());
    }
}
