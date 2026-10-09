package com.fishsunny.assistant.engine.tool.instance.browser;

/*
 * @Usage 浏览器操作链工具。AI 不再一次调用一个动作，而是提交一串操作原语组成的链，
 *        由工具在同一个浏览器会话里按序执行。支持点击、输入、悬停、下拉选择、拖拽、滚动、
 *        等待元素、固定等待、中途截图，链级延迟与失败整链中断、链尾默认截图。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/9
 */

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ContentType;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.MultimodalResultAble;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.instance.BrowserToolKit;
import com.fishsunny.assistant.engine.tool.service.browser.PlaywrightBrowserService;
import com.fishsunny.assistant.engine.tool.service.security.SecurityService;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 浏览器操作链工具。
 * <p>
 * 对外只暴露本工具：模型提交一组操作原语组成的链，工具在指定的浏览器会话里一次性按序执行，
 * 中间无需再与模型往返。原语集合与参数见 {@link BrowserChainOperation}。
 * <p>
 * 与 bot_chain_tool 的差异：浏览器操作以 CSS 选择器定位，而非坐标；导航（打开 URL）因需
 * 用户确认，仍由独立的 browser_navigate_tool 承担，不纳入本链。
 */
@ToolKitComponent(BrowserToolKit.class)
@ConditionalOnExpression("${engine.tool.browser.enable:true} && ${engine.tool.browser.chain.enable:true}")
public class BrowserChainTool implements ToolHandler, MultimodalResultAble {

    public static final String NAME = "browser_chain_tool";

    // ---- 原语类型 ----
    private static final String OP_CLICK = "click";
    private static final String OP_TYPE = "type";
    private static final String OP_HOVER = "hover";
    private static final String OP_SELECT = "select";
    private static final String OP_DRAG = "drag";
    private static final String OP_SCROLL = "scroll";
    private static final String OP_WAIT_FOR = "wait_for";
    private static final String OP_WAIT = "wait";
    private static final String OP_SCREENSHOT = "screenshot";
    private static final String OP_EVAL = "eval";

    /** scroll 默认垂直滚动像素 */
    private static final int DEFAULT_DELTA_Y = 300;
    /** wait_for 默认超时毫秒 */
    private static final int DEFAULT_TIMEOUT_MS = 10000;
    /** 确认框中展示脚本时的截断长度 */
    private static final int SCRIPT_PREVIEW_MAX = 2000;

    private final ObjectMapper objectMapper;
    private final ToolRegister register;
    private final PlaywrightBrowserService browserService;
    private final SecurityService securityService;

    public BrowserChainTool(ObjectMapper objectMapper,
                            PlaywrightBrowserService browserService,
                            SecurityService securityService) {
        this.objectMapper = objectMapper;
        this.browserService = browserService;
        this.securityService = securityService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        浏览器操作链。AI 不一次调用一个动作，而是提交一串操作按序执行，整链在同一个浏览器会话里一次性完成。
                        操作分三类：【页面操作】click/type/hover/select/drag/scroll/wait_for，【节奏与观测】wait（靠 delay 计时）、screenshot（截当前页面并把图片随结果返回），【脚本】eval（在页面中执行 JavaScript，返回值写入链日志）。
                        每个节点可带 delay（毫秒，<0 不延迟）控制节奏；链级可用 default_delay 设默认延迟、head_delay/tail_delay 设链头链尾延迟。
                        任何一步失败则整链中断，返回已执行到的步骤序号与失败原因。
                        注意：链中含 eval 时会在执行前请用户确认一次；导航（打开 URL）请使用 browser_navigate_tool；读取页面结构请使用 browser_read_content_tool。""")
                .setRequired(List.of("operations"));

        ToolRegister.Parameters headDelayParam = new ToolRegister.Parameters()
                .setParameterName("head_delay")
                .setType("integer")
                .setDescription("（可选）链开始前的等待毫秒数，默认 0，<0 表示不延迟");

        ToolRegister.Parameters tailDelayParam = new ToolRegister.Parameters()
                .setParameterName("tail_delay")
                .setType("integer")
                .setDescription("（可选）链结束后的等待毫秒数，默认 0，<0 表示不延迟");

        ToolRegister.Parameters defaultDelayParam = new ToolRegister.Parameters()
                .setParameterName("default_delay")
                .setType("integer")
                .setDescription("（可选）每个操作节点的默认延迟毫秒数（节点未指定 delay 时使用），默认 0，<0 表示不延迟");

        ToolRegister.Parameters screenshotParam = new ToolRegister.Parameters()
                .setParameterName("screenshot")
                .setType("boolean")
                .setDescription("（可选）链执行完成后是否截取当前页面并随结果返回，默认 true。截图用于操作后确认效果");

        ToolRegister.Parameters operationsParam = new ToolRegister.Parameters()
                .setParameterName("operations")
                .setType("array")
                .setDescription("操作列表，按顺序执行。每个元素是一个操作节点，字段 type 声明操作类型，其余字段按类型取用；"
                        + "每个节点可带 delay（毫秒，<0 不延迟）。操作类型："
                        + "click（selector）、"
                        + "type（selector、text：清空后填入）、"
                        + "hover（selector）、"
                        + "select（selector、value 或 label：下拉选项的 value / 可见文本）、"
                        + "drag（source、target：CSS 选择器）、"
                        + "scroll（selector 可选，缺省滚整页；delta_y 像素，正下负上，默认 " + DEFAULT_DELTA_Y + "）、"
                        + "wait_for（selector、timeout_ms 可选，默认 " + DEFAULT_TIMEOUT_MS + "：等待元素出现）、"
                        + "wait（无参数，等待时长由 delay 给出）、"
                        + "screenshot（无参数：截取当前页面并随结果返回图片）、"
                        + "eval（script：在页面中执行 JavaScript，返回值写入链日志；执行前需用户确认）。"
                        + "示例：填写并提交 = [type(#kw, text=...), click(#su)]；等结果再截图 = [click(.tab), wait_for(#list), screenshot]")
                .setItems(ToolRegister.Parameters.object("操作节点", null, null));

        register.setParameters(List.of(headDelayParam, tailDelayParam, defaultDelayParam, screenshotParam, operationsParam));
    }

    @Override
    @ToolIncludeContext(key = {"chatSession", "session"}, type = {ChatSession.class, WebSocketSession.class})
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("操作链参数解析失败：" + e.getMessage());
        }

        List<BrowserChainOperation> operations = arguments.getOperations();
        if (CollectionUtils.isEmpty(operations)) {
            throw new ToolExecutor.ToolExecuteException("operations 不能为空，至少需要一个操作节点");
        }

        Object sessionObj = context.get("chatSession");
        if (!(sessionObj instanceof ChatSession chatSession) || !StringUtils.hasText(chatSession.getId())) {
            throw new ToolExecutor.ToolExecuteException("无法确定浏览器会话，操作链未执行");
        }
        String sessionId = chatSession.getId();

        // 链中含 eval 时，执行前请用户确认一次（脚本可读写页面数据/发起请求）
        if (containsEval(operations)) {
            try {
                confirmEval(context, operations);
            } catch (ToolExecutor.ToolExecuteException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolExecutor.ToolExecuteException("JS 执行确认失败: " + e.getMessage());
            }
        }

        ToolExecutor.ToolExecuteResponse response = new ToolExecutor.ToolExecuteResponse(NAME, null);
        StringBuilder log = new StringBuilder();

        // 链头延迟
        sleepQuietly(arguments.getHeadDelay());

        // 逐步执行
        int index = 0;
        for (BrowserChainOperation op : operations) {
            index++;
            try {
                executeOperation(sessionId, op, response, log);
            } catch (ToolExecutor.ToolExecuteException e) {
                throw new ToolExecutor.ToolExecuteException(
                        "操作链在第 " + index + " 步中断（type=" + op.getType() + "）：" + e.getMessage());
            }
            // 节点延迟：节点未指定则回落链级默认；<0 表示不延迟
            Integer nodeDelay = op.getDelay() != null ? op.getDelay() : arguments.getDefaultDelay();
            sleepQuietly(nodeDelay);
        }

        // 链尾延迟
        sleepQuietly(arguments.getTailDelay());

        // 可选：链尾截图（默认开启，仅显式设为 false 时跳过）
        if (!Boolean.FALSE.equals(arguments.getScreenshot())) {
            screenshot(sessionId, response, log);
        }

        String result = "浏览器操作链执行完成，共 " + index + " 步。" + log;
        response.setResult(result);
        return response;
    }

    /**
     * 执行单个操作节点。未识别的 type 直接抛异常触发整链中断。
     */
    private void executeOperation(String sessionId, BrowserChainOperation op,
                                  ToolExecutor.ToolExecuteResponse response, StringBuilder log)
            throws ToolExecutor.ToolExecuteException {
        String type = op.getType();
        if (!StringUtils.hasText(type)) {
            throw new ToolExecutor.ToolExecuteException("操作节点缺少 type 字段");
        }

        switch (type.trim().toLowerCase()) {
            case OP_CLICK -> {
                String selector = requireSelector(op, OP_CLICK);
                browserService.click(sessionId, selector);
                log.append("\n- 已点击: ").append(selector);
            }
            case OP_TYPE -> {
                String selector = requireSelector(op, OP_TYPE);
                if (!StringUtils.hasText(op.getText())) {
                    throw new ToolExecutor.ToolExecuteException("type 需要 text 字段");
                }
                browserService.type(sessionId, selector, op.getText());
                log.append("\n- 已输入: ").append(selector).append(" ← ").append(op.getText());
            }
            case OP_HOVER -> {
                String selector = requireSelector(op, OP_HOVER);
                browserService.hover(sessionId, selector);
                log.append("\n- 已悬停: ").append(selector);
            }
            case OP_SELECT -> {
                String selector = requireSelector(op, OP_SELECT);
                if (!StringUtils.hasText(op.getValue()) && !StringUtils.hasText(op.getLabel())) {
                    throw new ToolExecutor.ToolExecuteException("select 需要 value 或 label 字段");
                }
                browserService.select(sessionId, selector, op.getValue(), op.getLabel());
                log.append("\n- 已选择: ").append(selector)
                        .append(" → ").append(StringUtils.hasText(op.getLabel()) ? op.getLabel() : op.getValue());
            }
            case OP_DRAG -> {
                if (!StringUtils.hasText(op.getSource())) {
                    throw new ToolExecutor.ToolExecuteException("drag 需要 source 字段");
                }
                if (!StringUtils.hasText(op.getTarget())) {
                    throw new ToolExecutor.ToolExecuteException("drag 需要 target 字段");
                }
                browserService.drag(sessionId, op.getSource(), op.getTarget());
                log.append("\n- 已拖拽: ").append(op.getSource()).append(" → ").append(op.getTarget());
            }
            case OP_SCROLL -> {
                int deltaY = op.getDeltaY() != null ? op.getDeltaY() : DEFAULT_DELTA_Y;
                if (StringUtils.hasText(op.getSelector())) {
                    browserService.scroll(sessionId, op.getSelector(), deltaY);
                    log.append("\n- 已滚动: ").append(op.getSelector()).append(" ").append(deltaY).append("px");
                } else {
                    browserService.scroll(sessionId, deltaY);
                    log.append("\n- 已滚动页面 ").append(deltaY).append("px");
                }
            }
            case OP_WAIT_FOR -> {
                String selector = requireSelector(op, OP_WAIT_FOR);
                int timeoutMs = op.getTimeoutMs() != null ? op.getTimeoutMs() : DEFAULT_TIMEOUT_MS;
                browserService.waitForSelector(sessionId, selector, timeoutMs);
                log.append("\n- 元素已出现: ").append(selector);
            }
            case OP_WAIT -> {
                // wait 节点本身不做动作，等待时长由 delay 表达
            }
            case OP_SCREENSHOT -> screenshot(sessionId, response, log);
            case OP_EVAL -> {
                if (!StringUtils.hasText(op.getScript())) {
                    throw new ToolExecutor.ToolExecuteException("eval 需要 script 字段");
                }
                String result = browserService.evaluate(sessionId, op.getScript());
                log.append("\n- 已执行 JS，返回值: ").append(result);
            }
            default -> throw new ToolExecutor.ToolExecuteException("不支持的操作类型 [" + type + "]");
        }
    }

    /**
     * 截取当前页面并作为多模态内容挂到结果上（落盘与引用回写由 {@link MultimodalResultAble} 处理）。
     */
    private void screenshot(String sessionId, ToolExecutor.ToolExecuteResponse response, StringBuilder log)
            throws ToolExecutor.ToolExecuteException {
        String base64 = browserService.screenshot(sessionId);
        String fileName = UUID.randomUUID() + ".png";
        response.modalContent(fileName, ContentType.IMAGE, base64);
        log.append("\n- 已截图: ").append(fileName);
    }

    private String requireSelector(BrowserChainOperation op, String opName) throws ToolExecutor.ToolExecuteException {
        if (!StringUtils.hasText(op.getSelector())) {
            throw new ToolExecutor.ToolExecuteException(opName + " 需要 selector 字段");
        }
        return op.getSelector();
    }

    /** 链中是否含有 eval 原语 */
    private boolean containsEval(List<BrowserChainOperation> operations) {
        for (BrowserChainOperation op : operations) {
            if (OP_EVAL.equals(normalizeType(op.getType()))) {
                return true;
            }
        }
        return false;
    }

    /** 把链中所有 eval 脚本汇总后向用户确认一次；用户拒绝时由 SecurityService 抛异常中断整链 */
    private void confirmEval(Map<String, Object> context, List<BrowserChainOperation> operations) throws Exception {
        StringBuilder scripts = new StringBuilder();
        for (BrowserChainOperation op : operations) {
            if (!OP_EVAL.equals(normalizeType(op.getType()))) {
                continue;
            }
            String script = op.getScript() == null ? "" : op.getScript();
            if (script.length() > SCRIPT_PREVIEW_MAX) {
                script = script.substring(0, SCRIPT_PREVIEW_MAX) + "\n…（已截断）";
            }
            scripts.append("```js\n").append(script).append("\n```\n");
        }
        String message = "### 浏览器 JS 执行请求\n\n"
                + "AI 请求在浏览器操作链中执行以下 JavaScript：\n\n"
                + scripts
                + "\n> ⚠️ 脚本可读取页面数据、触发页面行为或发起网络请求，请确认安全后再允许执行整条操作链。";
        securityService.ask(NAME, message, null, context);
    }

    private String normalizeType(String type) {
        return type == null ? "" : type.trim().toLowerCase();
    }

    /** 延迟指定毫秒；毫秒数为 null 或 <0 时不延迟 */
    private void sleepQuietly(Integer millis) throws ToolExecutor.ToolExecuteException {
        if (millis == null || millis < 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolExecutor.ToolExecuteException("操作被中断");
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
    public static class Arguments {
        @JsonProperty("head_delay")
        private Integer headDelay;
        @JsonProperty("tail_delay")
        private Integer tailDelay;
        @JsonProperty("default_delay")
        private Integer defaultDelay;
        private Boolean screenshot;
        private List<BrowserChainOperation> operations;
    }
}
