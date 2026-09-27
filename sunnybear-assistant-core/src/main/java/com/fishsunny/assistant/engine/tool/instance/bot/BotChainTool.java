package com.fishsunny.assistant.engine.tool.instance.bot;

/*
 * @Usage 桌面操作链工具。AI 不再一次调用一个操作，而是提交一串操作原语组成的链，
 *        由工具在本地按序执行。支持鼠标、键盘原语、节点级/链级延迟、失败整链中断、链尾默认截图。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/27
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ContentType;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.MultimodalResultAble;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.instance.BotToolKit;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.awt.Point;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.InputEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 桌面操作链工具。
 * <p>
 * 对外只暴露本工具：模型提交一组操作原语组成的链，工具在本地一次性按序执行，
 * 中间无需再与模型往返。原语集合与参数见 {@link BotChainOperation}。
 */
@ToolKitComponent(BotToolKit.class)
@ConditionalOnExpression("${engine.tool.bot.enable:true}")
public class BotChainTool implements ToolHandler, MultimodalResultAble {

    public static final String NAME = "bot_chain_tool";

    // ---- 原语类型 ----
    private static final String OP_MOUSE_MOVE = "mouse_move";
    private static final String OP_MOUSE_DOWN = "mouse_down";
    private static final String OP_MOUSE_UP = "mouse_up";
    private static final String OP_MOUSE_SCROLL = "mouse_scroll";
    private static final String OP_KEY_DOWN = "key_down";
    private static final String OP_KEY_UP = "key_up";
    private static final String OP_TYPE = "type";
    private static final String OP_INPUT_TEXT = "input_text";
    private static final String OP_CLICK = "click";
    private static final String OP_WAIT = "wait";

    // ---- 鼠标按键 ----
    private static final String BUTTON_LEFT = "left";
    private static final String BUTTON_RIGHT = "right";
    private static final String BUTTON_MIDDLE = "middle";

    /** 组合键内各键之间的间隔（毫秒） */
    private static final int COMBO_KEY_DELAY_MS = 20;
    /** 鼠标按键按下/抬起之间的间隔（毫秒） */
    private static final int MOUSE_PRESS_DELAY_MS = 20;
    /** 单次滚轮间隔（毫秒），用于多格滚动时逐格平滑 */
    private static final int SCROLL_STEP_DELAY_MS = 30;
    /** 写入剪贴板后、触发粘贴前的等待（毫秒），等待系统剪贴板同步完成 */
    private static final int CLIPBOARD_SYNC_DELAY_MS = 60;

    private final ObjectMapper objectMapper;
    private final ToolRegister register;

    public BotChainTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        桌面操作链。AI 不一次调用一个动作，而是提交一串操作按序执行，整链在本地一次性完成。
                        坐标使用归一化值 0-1（相对屏幕宽高），与截图定位结果一致。
                        操作分两层：【原语】mouse_move/mouse_down/mouse_up/mouse_scroll/key_down/key_up/input_text/wait，
                        【语法糖】click（当前位置点击）、type（敲击，支持组合键如 ctrl+c）——语法糖只是把高频组合打包，不引入原语不具备的能力。
                        input_text 可输入中文等任意字符（经剪贴板粘贴，会备份并恢复用户原剪贴板）。
                        每步可带 delay（毫秒，<0 表示不延迟），链级可用 default_delay 设默认延迟、head_delay/tail_delay 设链头链尾延迟。
                        任何一步失败则整链中断，返回已执行到的步骤序号与失败原因。""")
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
                .setDescription("（可选）链执行完成后是否截取屏幕并随结果返回，默认 true。截图用于操作后确认效果");

        ToolRegister.Parameters operationsParam = new ToolRegister.Parameters()
                .setParameterName("operations")
                .setType("array")
                .setDescription("操作列表，按顺序执行。每个元素是一个操作节点，字段 type 声明操作类型，其余字段按类型取用；"
                        + "每个节点可带 delay（毫秒，<0 不延迟）。操作类型："
                        + "【原语】"
                        + "mouse_move（x、y 归一化坐标 0-1）、"
                        + "mouse_down（button：left/right/middle，默认 left）、"
                        + "mouse_up（button）、"
                        + "mouse_scroll（amount：滚轮格数，正下负上）、"
                        + "key_down（keys：按键名或组合键，如 ctrl、ctrl+c，按序按下）、"
                        + "key_up（keys：同上，自动逆序抬起）、"
                        + "input_text（text：输入文本内容，支持任意 Unicode 含中文，通过剪贴板粘贴实现，不污染用户剪贴板）、"
                        + "wait（无参数，等待时长由 delay 给出）；"
                        + "【语法糖】"
                        + "click（在当前位置点击，可选 button，不带坐标，先 mouse_move 再 click 即为移动点击）、"
                        + "type（敲击，keys 支持组合键，一次完成按下+抬起）。"
                        + "示例：移动后点击 = mouse_move + click；拖动 = mouse_move(A) + mouse_down + mouse_move(B) + mouse_up；"
                        + "复制 = type(keys=ctrl+c)")
                .setItems(ToolRegister.Parameters.object("操作节点", null, null));

        register.setParameters(List.of(headDelayParam, tailDelayParam, defaultDelayParam, screenshotParam, operationsParam));
    }

    @Override
    @ToolIncludeContext(key = "chatSession", type = ChatSession.class)
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("操作链参数解析失败：" + e.getMessage());
        }

        List<BotChainOperation> operations = arguments.getOperations();
        if (CollectionUtils.isEmpty(operations)) {
            throw new ToolExecutor.ToolExecuteException("operations 不能为空，至少需要一个操作节点");
        }

        BotRobot bot = BotRobot.create();

        // 链头延迟
        sleepQuietly(arguments.getHeadDelay());

        // 逐步执行
        int index = 0;
        for (BotChainOperation op : operations) {
            index++;
            try {
                executeOperation(bot, op);
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

        String result = "操作链执行完成，共 " + index + " 步。";

        // 可选：链尾截图（默认开启，仅显式设为 false 时跳过）
        if (!Boolean.FALSE.equals(arguments.getScreenshot())) {
            String base64 = bot.captureScreenBase64();
            String fileName = UUID.randomUUID() + ".png";
            return new ToolExecutor.ToolExecuteResponse(name(), result + " 已截取屏幕：" + fileName)
                    .modalContent(fileName, ContentType.IMAGE, base64);
        }

        return new ToolExecutor.ToolExecuteResponse(name(), result);
    }

    /**
     * 执行单个操作节点。未识别的 type 直接抛异常触发整链中断。
     */
    private void executeOperation(BotRobot bot, BotChainOperation op) throws ToolExecutor.ToolExecuteException {
        String type = op.getType();
        if (!StringUtils.hasText(type)) {
            throw new ToolExecutor.ToolExecuteException("操作节点缺少 type 字段");
        }

        switch (type.trim().toLowerCase()) {
            case OP_MOUSE_MOVE -> mouseMove(bot, op);
            case OP_MOUSE_DOWN -> mouseButton(bot, op, true);
            case OP_MOUSE_UP -> mouseButton(bot, op, false);
            case OP_MOUSE_SCROLL -> mouseScroll(bot, op);
            case OP_KEY_DOWN -> keyDown(bot, op);
            case OP_KEY_UP -> keyUp(bot, op);
            case OP_TYPE -> typeKeys(bot, op);
            case OP_INPUT_TEXT -> inputText(bot, op);
            case OP_CLICK -> click(bot, op);
            case OP_WAIT -> {
                // wait 节点本身不做动作，等待时长由 delay 表达；delay 缺省视为 0
            }
            default -> throw new ToolExecutor.ToolExecuteException("不支持的操作类型 [" + type + "]");
        }
    }

    /** 移动鼠标到归一化坐标 */
    private void mouseMove(BotRobot bot, BotChainOperation op) throws ToolExecutor.ToolExecuteException {
        Point target = bot.toPixel(op.getX(), op.getY());
        bot.robot().mouseMove(target.x, target.y);
    }

    /** 按下或抬起鼠标按键 */
    private void mouseButton(BotRobot bot, BotChainOperation op, boolean press) throws ToolExecutor.ToolExecuteException {
        int mask = resolveButtonMask(op.getButton());
        if (press) {
            bot.robot().mousePress(mask);
        } else {
            bot.robot().mouseRelease(mask);
        }
        try {
            Thread.sleep(MOUSE_PRESS_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolExecutor.ToolExecuteException("操作被中断");
        }
    }

    /** 按格数滚动滚轮，正下负上 */
    private void mouseScroll(BotRobot bot, BotChainOperation op) throws ToolExecutor.ToolExecuteException {
        Integer amount = op.getAmount();
        if (amount == null || amount == 0) {
            throw new ToolExecutor.ToolExecuteException("mouse_scroll 需要非零的 amount（滚轮格数）");
        }
        int direction = amount > 0 ? 1 : -1;
        int steps = Math.abs(amount);
        for (int i = 0; i < steps; i++) {
            bot.robot().mouseWheel(direction);
            if (i < steps - 1) {
                try {
                    Thread.sleep(SCROLL_STEP_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ToolExecutor.ToolExecuteException("操作被中断");
                }
            }
        }
    }

    /**
     * 按下按键或组合键。
     * 组合键格式 key1+key2+…，按书写顺序依次按下；与 {@link #keyUp} 配对使用以保证逆序抬起。
     */
    private void keyDown(BotRobot bot, BotChainOperation op) throws ToolExecutor.ToolExecuteException {
        int[] codes = parseKeys(op.getKeys(), "key_down");
        for (int i = 0; i < codes.length; i++) {
            bot.robot().keyPress(codes[i]);
            if (i < codes.length - 1) {
                interruptibleSleep(COMBO_KEY_DELAY_MS);
            }
        }
    }

    /**
     * 抬起按键或组合键。
     * 组合键自动逆序抬起（与 {@link #keyDown} 的按下顺序相反），保证修饰键最后释放。
     */
    private void keyUp(BotRobot bot, BotChainOperation op) throws ToolExecutor.ToolExecuteException {
        int[] codes = parseKeys(op.getKeys(), "key_up");
        for (int i = codes.length - 1; i >= 0; i--) {
            bot.robot().keyRelease(codes[i]);
            if (i > 0) {
                interruptibleSleep(COMBO_KEY_DELAY_MS);
            }
        }
    }

    /**
     * 敲击按键或组合键（语法糖：一次完成 key_down + key_up）。
     * 组合键按书写顺序按下、逆序抬起。
     */
    private void typeKeys(BotRobot bot, BotChainOperation op) throws ToolExecutor.ToolExecuteException {
        int[] codes = parseKeys(op.getKeys(), "type");
        try {
            for (int i = 0; i < codes.length; i++) {
                bot.robot().keyPress(codes[i]);
                if (i < codes.length - 1) {
                    Thread.sleep(COMBO_KEY_DELAY_MS);
                }
            }
            for (int i = codes.length - 1; i >= 0; i--) {
                bot.robot().keyRelease(codes[i]);
                if (i > 0) {
                    Thread.sleep(COMBO_KEY_DELAY_MS);
                }
            }
        } catch (InterruptedException e) {
            // 确保释放所有已按下的键
            releaseQuietly(bot, codes);
            Thread.currentThread().interrupt();
            throw new ToolExecutor.ToolExecuteException("操作被中断");
        }
    }

    /**
     * 在当前位置点击（语法糖：mouse_down + mouse_up，不带坐标）。
     * 需要移动请先在链中安排 mouse_move。
     */
    private void click(BotRobot bot, BotChainOperation op) throws ToolExecutor.ToolExecuteException {
        int mask = resolveButtonMask(op.getButton());
        bot.robot().mousePress(mask);
        bot.robot().mouseRelease(mask);
        interruptibleSleep(MOUSE_PRESS_DELAY_MS);
    }

    /**
     * 输入文本内容（支持任意 Unicode，含中文）。
     * <p>
     * 实现方式：把文本写入系统剪贴板后模拟 ctrl+v 粘贴，粘贴完成后恢复原剪贴板内容——
     * 因为 AWT Robot 只能按物理键位，无法直接键入中文等无对应键位的字符。
     * 输入前请确保目标输入框已获得焦点（如需点击，请先在链中安排 mouse_move + click）。
     */
    private void inputText(BotRobot bot, BotChainOperation op) throws ToolExecutor.ToolExecuteException {
        String text = op.getText();
        if (text == null) {
            throw new ToolExecutor.ToolExecuteException("input_text 需要 text 字段");
        }

        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        // 备份原剪贴板内容（可能是任意 Transferable 类型，原样持有引用以便恢复）
        Transferable backup = clipboard.getContents(null);

        try {
            clipboard.setContents(new StringSelection(text), null);
            interruptibleSleep(CLIPBOARD_SYNC_DELAY_MS);

            Integer ctrl = KeyboardKeys.resolveKeyCode("ctrl");
            Integer v = KeyboardKeys.resolveKeyCode("v");
            if (ctrl == null || v == null) {
                throw new ToolExecutor.ToolExecuteException("无法解析 ctrl+v 按键");
            }
            try {
                bot.robot().keyPress(ctrl);
                bot.robot().keyPress(v);
                // 逆序释放：先 v 后 ctrl，与 key_up 的组合键释放顺序一致
                bot.robot().keyRelease(v);
                bot.robot().keyRelease(ctrl);
            } catch (Exception e) {
                // 确保释放修饰键，避免 ctrl 卡住
                releaseQuietly(bot, new int[]{ctrl, v});
                throw e;
            }
        } finally {
            // 恢复原剪贴板内容；备份为空时清空剪贴板，避免残留输入的文本
            clipboard.setContents(backup, null);
        }
    }

    /**
     * 解析 keys 字段为 keycode 数组，支持 key1+key2+… 组合形式。
     *
     * @param keys    按键名或组合键
     * @param opName  操作类型名，仅用于错误提示
     * @return keycode 数组（顺序与书写一致）
     */
    private int[] parseKeys(String keys, String opName) throws ToolExecutor.ToolExecuteException {
        if (!StringUtils.hasText(keys)) {
            throw new ToolExecutor.ToolExecuteException(opName + " 需要 keys 字段");
        }
        String[] parts = keys.trim().split("\\+");
        int[] codes = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Integer code = KeyboardKeys.resolveKeyCode(parts[i]);
            if (code == null) {
                throw new ToolExecutor.ToolExecuteException("不支持的按键 [" + parts[i].trim() + "]");
            }
            codes[i] = code;
        }
        return codes;
    }

    private void releaseQuietly(BotRobot bot, int[] codes) {
        for (int code : codes) {
            try {
                bot.robot().keyRelease(code);
            } catch (Exception ignored) {
                // 尽力释放，忽略二次异常
            }
        }
    }

    /** 可中断的延时，被中断时抛工具异常 */
    private void interruptibleSleep(int millis) throws ToolExecutor.ToolExecuteException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolExecutor.ToolExecuteException("操作被中断");
        }
    }

    private int resolveButtonMask(String button) throws ToolExecutor.ToolExecuteException {
        String b = StringUtils.hasText(button) ? button.trim().toLowerCase() : BUTTON_LEFT;
        return switch (b) {
            case BUTTON_LEFT -> InputEvent.BUTTON1_DOWN_MASK;
            case BUTTON_RIGHT -> InputEvent.BUTTON3_DOWN_MASK;
            case BUTTON_MIDDLE -> InputEvent.BUTTON2_DOWN_MASK;
            default -> throw new ToolExecutor.ToolExecuteException(
                    "不支持的鼠标按键 [" + button + "]，可选值：left/right/middle");
        };
    }

    /** 延迟指定毫秒；毫秒数为 null 或 <0 时不延迟 */
    private void sleepQuietly(Integer millis) throws ToolExecutor.ToolExecuteException {
        if (millis == null || millis < 0) {
            return;
        }
        interruptibleSleep(millis);
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
        private Integer headDelay;
        private Integer tailDelay;
        private Integer defaultDelay;
        private Boolean screenshot;
        private List<BotChainOperation> operations;
    }
}
