package com.fishsunny.assistant.mvc.controller;

/*
 * @Usage 后台命令 / 扩展脚本的运行面板接口 —— 供前端「后台任务」页签轮询与操作。
 *
 *        面板只关心「此刻还在跑的任务」：列表按会话过滤，任务一收尾就自动消失。
 *        日志走增量拉取（offset 游标），首次打开只取尾部一小段，之后每次只回传新增部分，
 *        长跑命令刷出几百 MB 输出也不会把页面拖死。
 *
 *        结束操作会杀整棵进程树（Windows 上 cmd.exe 下面才是真正干活的 java.exe），
 *        杀完立即从登记表摘掉，日志由收尾线程补上「已被用户手动结束」。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/11
 */

import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.engine.tool.service.background.CommandTaskRegistry;
import com.fishsunny.assistant.utils.SessionFileManager;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/command/task")
public class CommandTaskController {

    private static final Logger log = LoggerFactory.getLogger(CommandTaskController.class);

    /** 单次最多回传的日志字节数：再大就该走下载了，面板里塞不下也没人看 */
    private static final int MAX_CHUNK_BYTES = 256 * 1024;

    /** 首次拉取（或游标失效）时只回传尾部这么多字节，面板一打开就能看到最新进度 */
    private static final int INITIAL_TAIL_BYTES = 32 * 1024;

    private final CommandTaskRegistry commandTaskRegistry;
    private final SessionFileManager sessionFileManager;

    public CommandTaskController(CommandTaskRegistry commandTaskRegistry,
                                 SessionFileManager sessionFileManager) {
        this.commandTaskRegistry = commandTaskRegistry;
        this.sessionFileManager = sessionFileManager;
    }

    /** 列出当前会话还在跑的后台任务；sessionId 为空时返回全部（排查用） */
    @GetMapping("/list")
    public RestResponse list(@RequestParam(required = false) String sessionId) {
        try {
            List<TaskView> views = commandTaskRegistry.list(sessionId).stream()
                    .map(TaskView::of)
                    .toList();
            return new RestResponse().success(views);
        } catch (Exception e) {
            log.error("列出后台任务失败: sessionId={}", sessionId, e);
            return new RestResponse().error("列出后台任务失败: " + e.getMessage());
        }
    }

    /**
     * 增量读取任务日志。
     * <p>
     * 传 offset 表示「已经拿到这里了」，只回传它之后的新内容；不传、传负数或传得比文件还大
     * （说明文件被重建过）时，退回尾部 {@link #INITIAL_TAIL_BYTES} 字节并置 reset，
     * 前端据此清空已有内容重新渲染。
     *
     * @param offset 已读到的字节位置，首次调用可不传
     */
    @GetMapping("/log")
    public RestResponse log(@RequestParam(required = false) String sessionId,
                            @RequestParam(required = false) String id,
                            @RequestParam(required = false) Long offset) {
        if (!StringUtils.hasText(sessionId)) {
            return new RestResponse().error("会话 ID 不能为空");
        }
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("任务 ID 不能为空");
        }
        CommandTaskRegistry.RunningTask task = commandTaskRegistry.get(sessionId, id);
        if (task == null) {
            return new RestResponse().error("任务不存在或已结束");
        }
        try {
            Path file = sessionFileManager.resolveSessionFilePath(sessionId, task.getLogPath());
            if (!Files.isRegularFile(file)) {
                return new RestResponse().success(new LogChunk("", 0, true));
            }
            return new RestResponse().success(readChunk(file, offset));
        } catch (Exception e) {
            log.error("读取后台任务日志失败: sessionId={}, id={}", sessionId, id, e);
            return new RestResponse().error("读取日志失败: " + e.getMessage());
        }
    }

    /** 结束一个后台任务（杀整棵进程树） */
    @PostMapping("/kill")
    public RestResponse kill(@RequestBody(required = false) KillRequest request) {
        if (request == null || !StringUtils.hasText(request.getSessionId())) {
            return new RestResponse().error("会话 ID 不能为空");
        }
        if (!StringUtils.hasText(request.getId())) {
            return new RestResponse().error("任务 ID 不能为空");
        }
        try {
            boolean killed = commandTaskRegistry.kill(request.getSessionId(), request.getId());
            if (!killed) {
                return new RestResponse().error("任务不存在或进程已退出");
            }
            return new RestResponse().success("已结束");
        } catch (Exception e) {
            log.error("结束后台任务失败: sessionId={}, id={}", request.getSessionId(), request.getId(), e);
            return new RestResponse().error("结束失败: " + e.getMessage());
        }
    }

    /**
     * 从 offset 处读一段日志，并把游标修正到「最后一个完整字符之后」。
     * <p>
     * 为什么不在字节边界上直接切：UTF-8 一个汉字三字节，切在中间会解码成乱码，
     * 而且前端是增量拼接的，那半个字会永久留在面板里。所以两头都要对齐到字符边界，
     * 末尾不完整的字符本次不返回，留到下一轮文件写全了再取。
     */
    private LogChunk readChunk(Path file, Long offset) throws Exception {
        long size = Files.size(file);
        long start;
        boolean reset = false;
        if (offset == null || offset < 0 || offset > size) {
            start = Math.max(0, size - INITIAL_TAIL_BYTES);
            reset = true;
        } else {
            start = offset;
        }
        if (start >= size) {
            return new LogChunk("", size, reset);
        }

        int readLength = (int) Math.min(size - start, MAX_CHUNK_BYTES);
        byte[] buffer = new byte[readLength];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(start);
            raf.readFully(buffer);
        }

        int head = alignHead(buffer);
        int end = alignTail(buffer, head);
        String content = new String(buffer, head, Math.max(0, end - head), StandardCharsets.UTF_8);
        return new LogChunk(content, start + end, reset);
    }

    /** 起点对齐：跳过开头那几个「续字节」（10xxxxxx），保证从字符起始位置解码 */
    private static int alignHead(byte[] buffer) {
        int head = 0;
        while (head < buffer.length && head < 3 && (buffer[head] & 0xC0) == 0x80) {
            head++;
        }
        return head;
    }

    /**
     * 终点对齐：末尾若是一个没写全的多字节字符，就截到它前面，本轮不返回它。
     *
     * @param from 起点偏移，截断结果不会小于它
     */
    private static int alignTail(byte[] buffer, int from) {
        int end = buffer.length;
        int i = end - 1;
        int back = 0;
        while (i >= from && back < 3 && (buffer[i] & 0xC0) == 0x80) {
            i--;
            back++;
        }
        if (i < from) {
            return from;
        }
        int first = buffer[i] & 0xFF;
        int need = first < 0x80 ? 1 : (first >= 0xF0 ? 4 : (first >= 0xE0 ? 3 : 2));
        return i + need > end ? i : end;
    }

    /** 任务视图：只把前端要用的字段吐出去，进程句柄这类东西一律不序列化 */
    public record TaskView(String id, String type, String title, String logPath,
                           long startTime, boolean killed) {

        static TaskView of(CommandTaskRegistry.RunningTask task) {
            return new TaskView(task.getId(), task.getType().name(), task.getTitle(),
                    task.getLogPath(), task.getStartTime(), task.getKilled().get());
        }
    }

    /** 一段日志：新增文本、更新后的游标、是否要求前端清空重来 */
    public record LogChunk(String content, long offset, boolean reset) {
    }

    /** 结束任务的请求体 */
    @Data
    public static class KillRequest {

        /** 会话 ID */
        private String sessionId;

        /** 任务 ID */
        private String id;
    }
}
