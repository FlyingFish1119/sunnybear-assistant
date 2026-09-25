package com.fishsunny.assistant.plug.comfyui.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ContentType;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.utils.EasyReActProcessor;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.MultimodalResultAble;
import com.fishsunny.assistant.engine.tool.framework.SubAgentToolHandler;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.AgentToolKit;
import com.fishsunny.assistant.plug.comfyui.dto.HistoryEntry;
import com.fishsunny.assistant.plug.comfyui.dto.ViewImageResult;
import com.fishsunny.assistant.plug.comfyui.service.ComfyUIBridgeService;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.utils.SessionFileManager;
import lombok.Data;
import lombok.experimental.Accessors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@ToolKitComponent(AgentToolKit.class)
@ConditionalOnExpression("${engine.tool.agent.enable:true} && ${plug.comfyui.tool.agent.enable:true}")
public class ComfyUISubAgentTool implements SubAgentToolHandler, MultimodalResultAble {

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
    private final EasyReActProcessor easyReActProcessor;
    private final ToolExecutor toolExecutor;
    private final ComfyUIBridgeService bridgeService;
    private final SessionFileManager sessionFileManager;

    public ComfyUISubAgentTool(ObjectMapper objectMapper,
                                @Qualifier(AISettings.MISSION) AISettings missionAISettings,
                                EasyReActProcessor easyReActProcessor,
                                @Lazy ToolExecutor toolExecutor,
                                ComfyUIBridgeService bridgeService,
                                SessionFileManager sessionFileManager) {
        this.objectMapper = objectMapper;
        this.missionAISettings = missionAISettings;
        this.easyReActProcessor = easyReActProcessor;
        this.toolExecutor = toolExecutor;
        this.bridgeService = bridgeService;
        this.sessionFileManager = sessionFileManager;

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

            EasyReActProcessor.ToolResultHook hook = (roundResults, aiText) -> {
                for (EasyReActProcessor.RoundResult r : roundResults) {
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
            String finalReport = easyReActProcessor.execute(missionAISettings, request, context,
                    new EasyReActProcessor.AgentLoopHook(null, hook));

            // ========== 拉取图片并存到 session ==========
            boolean raw = resolveRaw(arguments.getExtension());
            List<GeneratedImage> images = fetchImages(generatedFiles, sessionId, raw);

            // ========== 组装返回 ==========
            return assembleResponse(finalReport, images, raw);

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

    /** 从 extension 解析 raw 开关，缺省 false */
    private boolean resolveRaw(Map<String, Object> extension) {
        if (extension == null) {
            return false;
        }
        Object value = extension.get("raw");
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && "true".equalsIgnoreCase(value.toString().trim());
    }

    /**
     * 生成图：raw=false 时只留会话引用（组装 markdown 用）；
     * raw=true 时保留 base64，落盘与引用回写交给 {@link MultimodalResultAble} 统一处理。
     */
    private record GeneratedImage(String fileName, String base64, String ref) {
        String markdown() {
            String proxyUrl = "/file/proxy?path=" + URLEncoder.encode(ref, StandardCharsets.UTF_8);
            return "![生成图 - " + fileName + "](" + proxyUrl + ")";
        }
    }

    /** 从 ComfyUI 拉取生成图。raw=false 立即落盘记录引用；raw=true 保留 base64 供多模态返回 */
    private List<GeneratedImage> fetchImages(List<String> filenames, String sessionId, boolean raw) {
        List<GeneratedImage> images = new ArrayList<>();
        if (filenames.isEmpty() || sessionId == null) return images;

        for (String fname : filenames) {
            try {
                String paramsJson = objectMapper.writeValueAsString(
                        Map.of("filename", fname, "type", "output"));
                String viewResult = bridgeService.sendCommand("view", paramsJson);
                ViewImageResult vr = objectMapper.readValue(viewResult, ViewImageResult.class);

                String base64 = vr.getBase64();
                if (!StringUtils.hasText(base64)) continue;

                if (raw) {
                    // 只给出会话内文件名，落盘由 MultimodalResultAble 统一处理
                    images.add(new GeneratedImage(fname, base64, null));
                } else {
                    byte[] bytes = Base64.getDecoder().decode(base64);
                    String ref = sessionFileManager.writeSessionFile(sessionId, fname, bytes);
                    images.add(new GeneratedImage(fname, null, ref));
                    log.info("图片已保存: {}", ref);
                }
            } catch (Exception e) {
                log.warn("拉取图片失败 [{}]: {}", fname, e.getMessage());
            }
        }
        return images;
    }

    private ToolExecutor.ToolExecuteResponse assembleResponse(String finalReport,
                                                              List<GeneratedImage> images,
                                                              boolean raw) {
        StringBuilder result = new StringBuilder(finalReport.trim());

        if (!images.isEmpty()) {
            result.append("\n\n");
            for (GeneratedImage image : images) {
                result.append(raw
                        ? "生成图：" + image.fileName() + "\n"
                        : image.markdown() + "\n");
            }
        }

        ToolExecutor.ToolExecuteResponse response =
                new ToolExecutor.ToolExecuteResponse(name(), result.toString());
        if (raw) {
            // raw：图片作为多模态内容直接返回给外层模型查看，正文不含 markdown 图片链接。
            // 落盘与引用回写由 MultimodalResultAble 统一处理（经 agent_tool 路由时由 AgentTool 转发）
            for (GeneratedImage image : images) {
                response.modalContent(image.fileName(), ContentType.IMAGE, image.base64());
            }
        }
        return response;
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

    @Override
    public List<ToolRegister.Parameters> extensionProperties() {
        return List.of(new ToolRegister.Parameters()
                .setParameterName("raw")
                .setType("boolean")
                .setDescription("适用于 " + NAME + "；是否以原始图片方式返回生成图，默认 false。" +
                        "false：图片以 markdown 链接展示；" +
                        "true：图片作为多模态内容直接返回给模型查看，正文不再输出 markdown 链接。"));
    }

    @Data
    @Accessors(chain = true)
    private static class Arguments {
        private String target;
        /** 扩展选项，由 agent_tool 透传；raw 控制生成图的返回形式 */
        private Map<String, Object> extension;
    }
}
