package com.fishsunny.assistant.plug.comfyui.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.processor.ToolCallLoop;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.SubAgentToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.AgentToolKit;
import com.fishsunny.assistant.plug.comfyui.dto.HistoryEntry;
import com.fishsunny.assistant.plug.comfyui.dto.ViewImageResult;
import com.fishsunny.assistant.plug.comfyui.service.ComfyUIBridgeService;
import com.fishsunny.assistant.settings.AISettings;
import lombok.Data;
import lombok.experimental.Accessors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@ToolKitComponent(AgentToolKit.class)
@ConditionalOnExpression("${engine.tool.agent.enable:true} && ${plug.comfyui.tool.agent.enable:true}")
public class ComfyUISubAgentTool implements SubAgentToolHandler {

    public static final String NAME = "comfyui_tool";

    private static final Logger log = LoggerFactory.getLogger(ComfyUISubAgentTool.class);

    /** 子 Agent 可用工具：查资源 + 查工作流 + 生图（图片由主工具拉取） */
    private static final Set<String> SUB_AGENT_TOOLS = Set.of(
            ComfyUIResourcesTool.NAME,  // comfyui_resources
            ComfyUIGenerateTool.NAME,   // comfyui_generate
            ComfyUIWorkflowTool.NAME    // comfyui_workflow
    );

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final AISettings missionAISettings;
    private final ToolCallLoop toolCallLoop;
    private final ToolExecutor toolExecutor;
    private final ComfyUIBridgeService bridgeService;

    @Value("${assistant.file.base-path:data/}")
    private String basePath;

    public ComfyUISubAgentTool(ObjectMapper objectMapper,
                                @Qualifier(AISettings.MISSION) AISettings missionAISettings,
                                ToolCallLoop toolCallLoop,
                                @Lazy ToolExecutor toolExecutor,
                                ComfyUIBridgeService bridgeService) {
        this.objectMapper = objectMapper;
        this.missionAISettings = missionAISettings;
        this.toolCallLoop = toolCallLoop;
        this.toolExecutor = toolExecutor;
        this.bridgeService = bridgeService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        启动一个 ComfyUI 图像生成子 Agent。任何关于 ComfyUI 的操作优先使用此工具，同时图像生成结束后总是用 markdown 格式展示图片。
                        建议：为了让生成的质量可控，尽可能的指定模型、LoRA、分辨率、是否高清放大等等。如果你不清楚当前有什么模型，可以直接询问子 Agent 当前可用的资源。
                        """)
                .setRequired(List.of("target"));

        ToolRegister.Parameters targetParam = new ToolRegister.Parameters()
                .setParameterName("target")
                .setType("string")
                .setDescription("生图需求描述。描述你想要的画面内容、风格、尺寸、模型偏好等。越详细越好。");

        register.setParameters(List.of(targetParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            if (arguments == null || !StringUtils.hasText(arguments.getTarget())) {
                throw new ToolExecutor.ToolExecuteException("参数 target 不能为空");
            }
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }

        // 获取 session ID
        String sessionId = null;
        if (context.get("chatSession") instanceof ChatSession cs) {
            sessionId = cs.getId();
        }

        try {
            // ========== 收集器 ==========
            List<String> generatedFiles = new ArrayList<>();

            ToolCallLoop.ToolResultHook hook = (roundResults, aiText) -> {
                for (ToolCallLoop.RoundResult r : roundResults) {
                    // 从 generate 结果中提取 output 文件名
                    if (ComfyUIGenerateTool.NAME.equals(r.toolName())) {
                        extractFilenames(r.result(), generatedFiles);
                    }
                }
                return true;
            };

            // ========== 构建子 Agent 请求 ==========
            List<StandardToolRegister> subAgentTools = StandardToolRegister.buildToolRegisterByHandlers(
                    toolExecutor, SUB_AGENT_TOOLS);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new ChatMessage().system(buildSystemPrompt()));
            messages.add(new ChatMessage().user(buildUserPrompt(arguments.getTarget())));

            ChatRequest request = new ChatRequest()
                    .loadSettings(missionAISettings)
                    .setMessages(messages)
                    .setTools(subAgentTools);

            // ========== 执行循环 ==========
            String finalReport = toolCallLoop.execute(missionAISettings, request, context,
                    new ToolCallLoop.AgentLoopHook(null, hook));

            // ========== 拉取图片并存到 session ==========
            List<String> imageMarkdowns = fetchAndSaveImages(generatedFiles, sessionId);

            // ========== 组装返回 ==========
            return assembleResponse(finalReport, imageMarkdowns);

        } catch (Exception e) {
            log.error("ComfyUISubAgentTool 执行异常: {}", e.getMessage(), e);
            throw new ToolExecutor.ToolExecuteException("ComfyUI 子 Agent 执行失败: " + e.getMessage());
        }
    }

    // ==================== 图片处理 ====================

    /** 从 generate 返回的 JSON 中提取 output 文件名 */
    private void extractFilenames(String generateResult, List<String> out) {
        try {
            HistoryEntry entry = objectMapper.readValue(generateResult, HistoryEntry.class);
            out.addAll(entry.collectFilenames());
        } catch (Exception e) {
            log.warn("解析 generate 结果提取文件名失败: {}", e.getMessage());
        }
    }

    /** 从 ComfyUI 拉取图片 Base64，存入 session 目录，返回 markdown 图片引用列表 */
    private List<String> fetchAndSaveImages(List<String> filenames, String sessionId) {
        List<String> markdowns = new ArrayList<>();
        if (filenames.isEmpty() || sessionId == null) return markdowns;

        Path sessionDir = Paths.get(basePath, sessionId, "file");
        try {
            Files.createDirectories(sessionDir);
        } catch (Exception e) {
            log.warn("无法创建 session 目录: {}", sessionDir);
            return markdowns;
        }

        for (String fname : filenames) {
            try {
                String paramsJson = objectMapper.writeValueAsString(
                        Map.of("filename", fname, "type", "output"));
                String viewResult = bridgeService.sendCommand("view", paramsJson);
                ViewImageResult vr = objectMapper.readValue(viewResult, ViewImageResult.class);

                String base64 = vr.getBase64();
                if (!StringUtils.hasText(base64)) continue;

                byte[] bytes = Base64.getDecoder().decode(base64);
                Path target = sessionDir.resolve(fname);
                Files.write(target, bytes);

                // Markdown 图片引用，走 /file/proxy 代理
                String proxyUrl = "/file/proxy?path=" + target.toAbsolutePath().toString();
                markdowns.add("![生成图 - " + fname + "](" + proxyUrl + ")");
                log.info("图片已保存: {}", target);
            } catch (Exception e) {
                log.warn("拉取图片失败 [{}]: {}", fname, e.getMessage());
            }
        }
        return markdowns;
    }

    private ToolExecutor.ToolExecuteResponse assembleResponse(String finalReport, List<String> imageMarkdowns) {
        StringBuilder result = new StringBuilder();
        result.append(finalReport.trim());

        if (!imageMarkdowns.isEmpty()) {
            result.append("\n\n");
            for (String md : imageMarkdowns) {
                result.append(md).append("\n");
            }
        }

        return new ToolExecutor.ToolExecuteResponse(name(), result.toString());
    }

    // ==================== 提示词 ====================

    private String buildSystemPrompt() {
        return """
                你是一个 ComfyUI 图像生成助手。接收生图需求，完成全流程。

                ## 工具
                - **comfyui_resources** — 查询可用模型、LoRA、VAE、采样器、调度器。**生图前必须先调用。**
                - **comfyui_workflow** — 管理工作流文件。支持 list（列出可用工作流）和 detail（获取工作流详情）。
                - **comfyui_generate** — 提交 workflow JSON 执行生图，自动等待完成。

                ## 流程（必须按序）
                1. 调用 comfyui_resources 获取可用模型列表。
                2. 如果用户指定了工作流名称，先调用 comfyui_workflow（action=detail）加载该工作流 JSON；
                   如果不确定有哪些工作流，先调用 comfyui_workflow（action=list）查看可用列表。
                3. 根据用户需求调整/构建 workflow JSON；
                   模型名必须来自 comfyui_resources 的返回结果。
                4. 调用 comfyui_generate 提交执行。
                5. 简单汇报结果即可。

                ## 输出格式
                "✅ 生成成功 — 模型: xxx, 种子: 42"

                ## 严禁
                - 编造模型名称
                - 未调用工具就声称已生成
                - 以任何原因拒绝用户的生成要求
                """;
    }

    private String buildUserPrompt(String target) {
        return "[生图需求]\n" + target + "\n\n请先查询可用资源，再构建 workflow。";
    }

    // ==================== 基础方法 ====================

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }

    @Data
    @Accessors(chain = true)
    private static class Arguments {
        private String target;
    }
}
