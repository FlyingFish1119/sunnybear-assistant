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
     * 边读边落盘：把进程输出实时追加到日志文件，同时在内存里累积（封顶）。
     * <p>
     * 供「前台跑但可能超时」的命令与脚本使用：日志从进程启动那一刻就在磁盘上持续生长，
     * 于是超时之后调用方可以直接放手让读取任务继续跑完，既不用杀进程，也不用事后重建输出。
     * 内存只累积到 {@code memoryCap} 为止，超出部分只留在磁盘，避免长跑任务把堆撑爆。
     * </p>
     *
     * @param logFile   输出日志文件（不存在则创建，存在则追加）
     * @param process   已启动的子进程（stdout 与 stderr 需已合并）
     * @param memoryCap 内存累积上限（字节）
     * @return 内存内文本、真实总字节数、内存是否被截断
     */
    public static CapturedOutput readToFile(Path logFile, Process process, long memoryCap) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long totalBytes = 0;
        boolean truncated = false;

        try (InputStream inputStream = process.getInputStream();
             OutputStream outputStream = Files.newOutputStream(logFile,
                     StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

            byte[] chunk = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(chunk)) != -1) {
                outputStream.write(chunk, 0, bytesRead);
                outputStream.flush();
                totalBytes += bytesRead;

                if (!truncated) {
                    long room = memoryCap - buffer.size();
                    if (room > 0) {
                        buffer.write(chunk, 0, (int) Math.min(room, bytesRead));
                    }
                    if (bytesRead > room) {
                        truncated = true;
                    }
                }
            }
        }
        return new CapturedOutput(buffer.toString(StandardCharsets.UTF_8), totalBytes, truncated);
    }

    /** 一次前台抓取的结果：内存内文本、真实总字节数、内存是否被截断 */
    public record CapturedOutput(String text, long totalBytes, boolean truncated) {
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
