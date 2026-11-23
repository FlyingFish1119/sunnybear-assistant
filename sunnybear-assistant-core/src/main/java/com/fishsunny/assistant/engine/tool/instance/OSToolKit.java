package com.fishsunny.assistant.engine.tool.instance;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/29 00:53
 */

import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.utils.SessionFileManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@ConditionalOnProperty(name = "engine.tool.os.enable", havingValue = "true", matchIfMissing = true)
public class OSToolKit extends ToolKit {

    public OSToolKit(List<ToolHandler> tools, @Value("${engine.tool.os.enable:true}") boolean enable) {
        super(tools);
    }

    @Override
    public String displayName() {
        return "系统命令";
    }

    @Override
    public String description() {
        return "执行命令行与扩展脚本";
    }

    /** 运行日志子目录名，相对会话文件目录（file/logs）——命令与扩展脚本的输出统一收在这里，不跟用户文件混在顶层 */
    public static final String LOG_DIR = "logs";

    /**
     * 日志文件相对会话文件目录的路径（logs/文件名），落盘与对外引用共用同一拼法。
     * <p>
     * 日志落盘是本套工具的行为，位置约定归这里管；落盘走通用入口
     * {@code sessionFileManager.prepareSessionFile(sessionId, 相对路径)}，
     * SessionFileManager 只负责会话文件的通用读写，无需为日志提供特化方法。
     */
    public static String buildLogRelativePath(String fileName) {
        return LOG_DIR + "/" + fileName;
    }

    /**
     * 桥接（卫星机）任务的回传输出落库为会话资产：写入 session 的 logs 目录，返回沙箱标记。
     */
    public static String persistBridgeLog(SessionFileManager sessionFileManager, String sessionId,
                                          String logName, String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        try {
            return sessionFileManager.writeSessionFileMarker(sessionId, buildLogRelativePath(logName),
                    output.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // 落库失败降级：调用方拿 null 走无引用文案，不影响命令/脚本本身的结果回传
            return null;
        }
    }

    public static void writeLog(Path logFile, Process process) throws IOException, InterruptedException {
        try (InputStream inputStream = process.getInputStream();
             OutputStream outputStream = Files.newOutputStream(logFile,
                     StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                outputStream.flush();
            }
        }
    }

    /**
     * 边读边落盘：把进程输出实时追加到日志文件，同时在内存里累积完整输出。
     * <p>
     * 供「前台跑但可能超时」的命令与脚本使用：日志从进程启动那一刻就在磁盘上持续生长，
     * 于是超时之后调用方可以直接放手让读取任务继续跑完，既不用杀进程，也不用事后重建输出。
     * </p>
     *
     * @param logFile 输出日志文件（不存在则创建，存在则追加）
     * @param process 已启动的子进程（stdout 与 stderr 需已合并）
     * @return 完整输出文本（UTF-8）
     */
    public static String readToFile(Path logFile, Process process) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        try (InputStream inputStream = process.getInputStream();
             OutputStream outputStream = Files.newOutputStream(logFile,
                     StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

            byte[] chunk = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(chunk)) != -1) {
                outputStream.write(chunk, 0, bytesRead);
                outputStream.flush();
                buffer.write(chunk, 0, bytesRead);
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /**
     * 向日志文件追加结束标记（结果说明 + 结束时间）。
     * <p>命令与扩展脚本共用：四条收尾路径（显式后台、前台超时转后台）都走这里，
     * 免得同一种格式在两处各写一遍、哪天改文案还漏一处。写不进去就静默跳过，
     * 日志尾巴缺失不影响任务本身是否已经跑完。
     *
     * @param logFile 输出日志文件
     * @param message 结束说明，如「命令执行完成，退出码: 0」「命令已被用户手动结束」
     */
    public static void appendFooter(Path logFile, String message) {
        try (BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.newLine();
            writer.write("=".repeat(60));
            writer.newLine();
            writer.write(message);
            writer.newLine();
            writer.write("结束时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {
            // 无法写入结束信息，静默忽略
        }
    }
}
