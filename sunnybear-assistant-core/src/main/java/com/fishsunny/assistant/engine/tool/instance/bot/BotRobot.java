package com.fishsunny.assistant.engine.tool.instance.bot;

/*
 * @Usage 桌面操作底层句柄。收敛 Robot 的创建与异常、屏幕尺寸获取、归一化坐标换算、截图抓取，
 *        供 bot_chain_tool 及截图工具共用。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/27
 */

import com.fishsunny.assistant.engine.tool.ToolExecutor;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * 桌面操作底层句柄。
 * <p>
 * 把 AWT {@link Robot} 的创建、屏幕尺寸、光标位置、坐标换算、屏幕抓取收口到一处，
 * 使上层操作链代码不必反复处理 {@link AWTException} 与屏幕度量。
 * <p>
 * 设计约定：一次操作链执行期间通过 {@link #create()} 得到一个实例，整链共用同一份屏幕尺寸，
 * 避免中途重复取分辨率。
 */
public final class BotRobot {

    private final Robot robot;
    private final Dimension screenSize;

    private BotRobot(Robot robot, Dimension screenSize) {
        this.robot = robot;
        this.screenSize = screenSize;
    }

    /**
     * 创建底层句柄。会捕获 AWTException 与无头环境异常并转为工具异常。
     *
     * @return 桌面操作句柄
     * @throws ToolExecutor.ToolExecuteException 当前环境无法创建 Robot（如无图形界面）时抛出
     */
    public static BotRobot create() throws ToolExecutor.ToolExecuteException {
        try {
            Dimension size = Toolkit.getDefaultToolkit().getScreenSize();
            return new BotRobot(new Robot(), size);
        } catch (AWTException | HeadlessException e) {
            throw new ToolExecutor.ToolExecuteException("无法初始化桌面操作环境（可能运行在无图形界面环境）：" + e.getMessage());
        }
    }

    public Robot robot() {
        return robot;
    }

    /** 屏幕宽度（像素） */
    public int screenWidth() {
        return screenSize.width;
    }

    /** 屏幕高度（像素） */
    public int screenHeight() {
        return screenSize.height;
    }

    /**
     * 将归一化坐标（0-1）换算为屏幕像素坐标。
     * <p>
     * x/y 超出 [0,1] 时直接抛出异常中断，交由上层判定为链失败，而不是静默钳制——
     * 越界通常意味着模型算错了，报错更利于其纠正。
     *
     * @param x 归一化 X（0-1，相对屏幕宽度）
     * @param y 归一化 Y（0-1，相对屏幕高度）
     * @return 像素坐标
     * @throws ToolExecutor.ToolExecuteException 坐标为 null 或越界时抛出
     */
    public Point toPixel(Double x, Double y) throws ToolExecutor.ToolExecuteException {
        if (x == null || y == null) {
            throw new ToolExecutor.ToolExecuteException("鼠标坐标 x、y 不能为空");
        }
        if (x < 0 || x > 1 || y < 0 || y > 1) {
            throw new ToolExecutor.ToolExecuteException(
                    "鼠标坐标为归一化值（0-1），当前值越界：x=" + x + ", y=" + y);
        }
        int px = (int) Math.round(x * screenWidth());
        int py = (int) Math.round(y * screenHeight());
        return new Point(px, py);
    }

    /**
     * 获取当前光标位置。
     *
     * @return 光标像素坐标；无头环境无法获取时返回 null
     */
    public Point pointerLocation() {
        PointerInfo info = MouseInfo.getPointerInfo();
        return info == null ? null : info.getLocation();
    }

    /**
     * 抓取整屏并编码为 Base64 PNG。
     *
     * @return Base64 图片数据（不含 data URI 前缀）
     * @throws ToolExecutor.ToolExecuteException 抓取或编码失败时抛出
     */
    public String captureScreenBase64() throws ToolExecutor.ToolExecuteException {
        try {
            Rectangle rect = new Rectangle(screenSize);
            BufferedImage capture = robot.createScreenCapture(rect);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(capture, "png", out);
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("截取屏幕失败：" + e.getMessage());
        }
    }
}
