package com.fishsunny.assistant.engine.tool.instance.browser;

/*
 * @Usage 浏览器 JS 执行工具 - 在当前无头浏览器页面中执行一段 JavaScript 并返回结果。
 *        适合读取页面变量/DOM 数据、触发页面自带函数等。每次需用户确认。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/9
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.BrowserToolKit;
import com.fishsunny.assistant.engine.tool.service.browser.PlaywrightBrowserService;
import com.fishsunny.assistant.engine.tool.service.security.SecurityService;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

@ToolKitComponent(BrowserToolKit.class)
@ConditionalOnExpression("${engine.tool.browser.enable:true} && ${engine.tool.browser.eval.enable:true}")
public class BrowserEvalTool implements ToolHandler {

    public static final String NAME = "browser_eval_tool";

    /** 确认框中展示脚本时的截断长度，避免超长脚本撑爆弹窗 */
    private static final int SCRIPT_PREVIEW_MAX = 2000;

    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final PlaywrightBrowserService browserService;
    private final SecurityService securityService;

    public BrowserEvalTool(ObjectMapper objectMapper,
                           PlaywrightBrowserService browserService,
                           SecurityService securityService) {
        this.objectMapper = objectMapper;
        this.browserService = browserService;
        this.securityService = securityService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("在当前浏览器页面中执行一段 JavaScript，并返回其执行结果。"
                        + "适合读取页面变量/DOM 数据、提取结构化信息、触发页面自带函数等。"
                        + "返回值会尽量以 JSON 呈现。执行前需用户确认。（每次需用户确认）")
                .setRequired(List.of("script"));

        ToolRegister.Parameters scriptParam = new ToolRegister.Parameters()
                .setParameterName("script")
                .setType("string")
                .setDescription("要执行的 JavaScript 代码。可返回一个值作为执行结果："
                        + "对象/数组会被序列化为 JSON，字符串/数字/布尔原样返回，不返回则为 null。"
                        + "例如 \"document.title\"、\"[...document.querySelectorAll('a')].map(a => a.href)\"");

        register.setParameters(List.of(scriptParam));
    }

    @Override
    @ToolIncludeContext(key = {"session", "chatSession"}, type = {WebSocketSession.class, ChatSession.class})
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);

            if (!StringUtils.hasText(arguments.getScript())) {
                throw new ToolExecutor.ToolExecuteException("参数 script 不能为空");
            }

            // 始终需要用户确认（无审查/定时任务由 SecurityService 统一裁决）
            ask(context, arguments.getScript());

            String sessionId = ((ChatSession) context.get("chatSession")).getId();
            String result = browserService.evaluate(sessionId, arguments.getScript());
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "已执行 JavaScript，返回值：\n" + result);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("执行 JavaScript 失败: " + e.getMessage());
        }
    }

    /**
     * 向用户发送 JS 执行确认请求，等待用户确认。
     */
    private void ask(Map<String, Object> context, String script) throws Exception {
        String preview = script.length() > SCRIPT_PREVIEW_MAX
                ? script.substring(0, SCRIPT_PREVIEW_MAX) + "\n…（已截断）"
                : script;
        String message = "### 浏览器 JS 执行请求\n\n"
                + "AI 请求在当前浏览器页面中执行以下 JavaScript：\n\n"
                + "```js\n" + preview + "\n```\n\n"
                + "> ⚠️ 脚本可读取页面数据、触发页面行为或发起网络请求，请确认安全后再允许执行。";
        securityService.ask(NAME, message, null, context);
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
        private String script;
    }
}
