package com.fishsunny.assistant.engine.tool.instance.browser;

/*
 * @Usage 浏览器导航工具 - 在无头浏览器中打开指定 URL
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/22 10:00
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.ToolAsk;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.service.browser.PlaywrightBrowserService;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.BrowserToolKit;
import com.fishsunny.assistant.mvc.controller.ChatController;
import com.fishsunny.assistant.variable.ControlSign;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@ToolKitComponent(BrowserToolKit.class)
@ConditionalOnExpression("${engine.tool.browser.enable:true} && ${engine.tool.browser.navigate.enable:true}")
public class BrowserNavigateTool implements ToolHandler {

    public static final String NAME = "browser_navigate_tool";

    private static final int DEFAULT_TIMEOUT_MS = 30000;

    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final PlaywrightBrowserService browserService;

    public BrowserNavigateTool(ObjectMapper objectMapper,
                               PlaywrightBrowserService browserService) {
        this.objectMapper = objectMapper;
        this.browserService = browserService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("打开指定 URL，会话状态（cookie、登录态等）跨操作保持。（每次需用户确认）")
                .setRequired(List.of("url"));

        ToolRegister.Parameters urlParam = new ToolRegister.Parameters()
                .setParameterName("url")
                .setType("string")
                .setDescription("目标网页的 URL 地址，须包含协议头（http:// 或 https://）");

        ToolRegister.Parameters timeoutParam = new ToolRegister.Parameters()
                .setParameterName("timeout_ms")
                .setType("integer")
                .setDescription("（可选）页面加载超时毫秒数，默认 " + DEFAULT_TIMEOUT_MS);

        register.setParameters(List.of(urlParam, timeoutParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        String uuid = UUID.randomUUID().toString();
        try {
            if (!(context.get("session") instanceof WebSocketSession session)) {
                throw new ToolExecutor.ToolExecuteException("工具内部错误导致此工具不可使用，原因: session 依赖缺失");
            }

            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);

            if (!StringUtils.hasText(arguments.getUrl())) {
                throw new ToolExecutor.ToolExecuteException("参数 url 不能为空");
            }

            // 始终需要用户确认
            ask(uuid, session, arguments);

            if (!session.isOpen()) {
                throw new ToolExecutor.ToolExecuteException("session 已关闭，无法获取用户回应，工具不可用");
            }

            int timeout = DEFAULT_TIMEOUT_MS;
            if (StringUtils.hasText(arguments.getTimeoutMs())) {
                timeout = Integer.parseInt(arguments.getTimeoutMs());
            }

            String sessionId = ((ChatSession) context.get("chatSession")).getId();
            String title = browserService.navigate(sessionId, arguments.getUrl(), timeout);
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "已导航到: " + arguments.getUrl() + "\n页面标题: " + title);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("浏览器导航失败: " + e.getMessage());
        } finally {
            ChatController.cleanupConfirm(uuid);
        }
    }

    /**
     * 向用户发送导航确认请求，等待用户确认
     */
    private void ask(String uuid, WebSocketSession session, Arguments arguments) throws Exception {
        String message = "### 浏览器导航请求\n\n"
                + "AI 请求在浏览器中打开以下 URL：\n\n"
                + "**目标地址：** `" + arguments.getUrl() + "`\n\n"
                + "> ⚠️ 请确认此导航操作安全后再允许执行。";

        ToolAsk confirmation = new ToolAsk()
                .setId(uuid)
                .setToolName(NAME)
                .setMessage(message);

        session.sendMessage(new TextMessage(ControlSign.SIGN_TOOL_ASK + objectMapper.writeValueAsString(confirmation)));
        // 本工具确认无超时（ToolAsk 不携带 timeout），一直等待用户确认，与前端"等待确认中"一致
        Boolean result = ChatController.awaitConfirm(uuid, null);
        if (result == null) {
            throw new ToolExecutor.ToolExecuteException("用户未确认浏览器导航操作，工具已取消。请停止重复调用此工具，改为询问用户原因或是否需要调整 URL。");
        }
        if (!result) {
            throw new ToolExecutor.ToolExecuteException("用户拒绝了浏览器导航操作，工具已取消。请停止重复调用此工具，改为询问用户原因或是否需要调整 URL。");
        }
    }

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
        private String url;
        private String timeoutMs;
    }
}
