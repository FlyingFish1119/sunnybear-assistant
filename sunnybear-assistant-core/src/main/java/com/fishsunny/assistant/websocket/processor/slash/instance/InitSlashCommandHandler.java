package com.fishsunny.assistant.websocket.processor.slash.instance;

/*
 * @Usage /init <path> —— 探索本机目录，生成结构化项目文档并写入核心文件（PROJECT_<目录名>.md）。
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
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.instance.file.FileListTool;
import com.fishsunny.assistant.engine.tool.instance.file.FileReadTool;
import com.fishsunny.assistant.engine.tool.instance.file.FileSearchTool;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.utils.CoreFileManager;
import com.fishsunny.assistant.utils.ObjectUtils;
import com.fishsunny.assistant.utils.SessionFileManager;
import com.fishsunny.assistant.websocket.ChatProvider;
import com.fishsunny.assistant.websocket.processor.StandardChatTranslateFactory;
import com.fishsunny.assistant.websocket.processor.slash.framework.SlashCommandComponent;
import com.fishsunny.assistant.websocket.processor.slash.framework.SlashCommandHandler;
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

@Slf4j
@SlashCommandComponent("/init")
public class InitSlashCommandHandler extends SlashCommandHandler {

    /** 子 Agent 可用的只读文件工具 */
    private static final Set<String> SUB_AGENT_TOOLS = Set.of(
            FileListTool.NAME,
            FileSearchTool.NAME,
            FileReadTool.NAME
    );

    private final ChatMessageService chatMessageService;

    private final ObjectMapper objectMapper;

    private final AssistantSettings assistantSettings;

    private final CoreFileManager coreFileManager;

    private final ChatHttpHandler chatHttpHandler;

    private final StandardChatTranslateFactory standardChatTranslateFactory;

    private final ToolExecutor toolExecutor;

    private final AISettings chatAISettings;

    private final AISettings chatProAISettings;

    /** 本次探索的项目根目录（供落盘钩子生成文件名） */
    private Path projectDir;

    public InitSlashCommandHandler(ChatMessageService chatMessageService,
                                   ObjectMapper objectMapper,
                                   AssistantSettings assistantSettings,
                                   CoreFileManager coreFileManager,
                                   ChatHttpHandler chatHttpHandler,
                                   StandardChatTranslateFactory standardChatTranslateFactory,
                                   @Lazy ToolExecutor toolExecutor,
                                   @Qualifier(AISettings.CHAT) AISettings chatAISettings,
                                   @Qualifier(AISettings.CHAT_PRO) AISettings chatProAISettings) {
        this.chatMessageService = chatMessageService;
        this.objectMapper = objectMapper;
        this.assistantSettings = assistantSettings;
        this.coreFileManager = coreFileManager;
        this.chatHttpHandler = chatHttpHandler;
        this.standardChatTranslateFactory = standardChatTranslateFactory;
        this.toolExecutor = toolExecutor;
        this.chatAISettings = chatAISettings;
        this.chatProAISettings = chatProAISettings;
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
                    探索完成后会生成结构化项目文档并保存到核心文件。""");
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
     * 末轮助手消息（无工具调用）落库前：报告写入核心文件，并在正文追加保存提示。
     * 带工具调用的中间轮不处理。
     * <p>项目目录只加在写入的文件开头，消息正文不重复。
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
            String projectName = resolveProjectName(projectDir);
            String fileName = SessionFileManager.sanitizeFileName("PROJECT_" + projectName + ".md");
            Path target = SessionFileManager.uniquePath(coreFileManager.buildCoreDirPath(), fileName);
            String savedName = target.getFileName().toString();
            // 文件开头补上项目目录（/init 的参数）
            coreFileManager.writeCoreText(savedName, "> 项目目录：`" + projectDir + "`\n\n" + report.strip());
            message.bodyText(report.strip() + "\n\n---\n\n> 📁 已保存到核心文件 `core/" + savedName + "`");
        } catch (Exception e) {
            log.error("/init 报告落盘失败: {}", e.getMessage(), e);
            message.bodyText(report.strip() + "\n\n---\n\n> ⚠️ 报告保存到核心文件失败：" + e.getMessage());
        }
        return message;
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
