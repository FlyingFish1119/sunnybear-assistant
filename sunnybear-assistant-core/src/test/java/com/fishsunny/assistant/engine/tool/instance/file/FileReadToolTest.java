package com.fishsunny.assistant.engine.tool.instance.file;

/*
 * @Usage FileReadTool 安全模式与超长单行截断测试
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/17 10:00
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileReadToolTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final FileReadTool tool = new FileReadTool(OBJECT_MAPPER);

    private String read(Path file, String extraArgs) throws Exception {
        String path = file.toString().replace("\\", "\\\\");
        String args = "{\"paths\":[{\"path\":\"" + path + "\"}]" + extraArgs + "}";
        ToolExecutor.ToolExecuteResponse resp = tool.action(args, Map.of());
        return resp.getResult();
    }

    @Test
    void smallFileReadsContentInSafeModeByDefault(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("small.txt");
        Files.writeString(file, "line1\nline2\n");

        String result = read(file, "");
        assertTrue(result.contains("line1"), result);
        assertTrue(result.contains("line2"), result);
        assertFalse(result.contains("安全模式已开启"), result);
    }

    @Test
    void manyLinesButSmallFileReadsContentInSafeMode(@TempDir Path dir) throws Exception {
        // 行数很多但体积未超上限：安全模式不以行数为限制，应正常返回正文
        Path file = dir.resolve("many-lines.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append("line-").append(i).append('\n');
        }
        Files.writeString(file, sb);

        String result = read(file, "");
        assertTrue(result.contains("line-0"), result);
        assertTrue(result.contains("line-2999"), result);
        assertFalse(result.contains("安全模式已开启"), result);
    }

    @Test
    void largeFileBlockedBySizeInSafeMode(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("huge-line.txt");
        Files.writeString(file, "start-" + "x".repeat(200_000) + "-end");

        String result = read(file, "");
        assertTrue(result.contains("安全模式已开启"), result);
        assertFalse(result.contains("-end"), "安全模式不应返回正文");
        assertTrue(result.length() < 2000, "拦截后输出应远小于文件体积");
    }

    @Test
    void largeFileReadableWhenSafeFalse(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("big.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            sb.append("line-").append(i).append("-").append("x".repeat(40)).append('\n');
        }
        Files.writeString(file, sb);
        assertTrue(Files.size(file) > 128 * 1024L, "测试文件应超过安全上限");

        String result = read(file, ",\"safe\":false");
        assertTrue(result.contains("line-0"), "safe=false 应返回正文");
        assertTrue(result.contains("line-3999"), result);
        assertFalse(result.contains("安全模式已开启"), result);
    }

    @Test
    void longSingleLineReturnedFullyWhenFileUnderSizeLimit(@TempDir Path dir) throws Exception {
        // 单行 5000 字符但文件体积未超上限：安全模式只按大小拦截，应原样返回正文
        Path file = dir.resolve("long-line.txt");
        Files.writeString(file, "head-" + "y".repeat(4995));

        String result = read(file, "");
        assertFalse(result.contains("单行过长"), result);
        assertTrue(result.contains("y".repeat(4000)), "safe 模式不应截断正文");
    }
}
