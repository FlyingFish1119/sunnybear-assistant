package com.fishsunny.assistant.engine.tool.instance.file;

/*
 * @Usage FileReadTool 安全模式、超长单行窗口与截断测试
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

    /**
     * 读取文件：fileExtra 追加到文件描述对象内，topExtra 追加到顶层参数
     */
    private String read(Path file, String fileExtra, String topExtra) throws Exception {
        String path = file.toString().replace("\\", "\\\\");
        String args = "{\"paths\":[{\"path\":\"" + path + "\"" + fileExtra + "}]" + topExtra + "}";
        ToolExecutor.ToolExecuteResponse resp = tool.action(args, Map.of());
        return resp.getResult();
    }

    @Test
    void smallFileReadsContentInSafeModeByDefault(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("small.txt");
        Files.writeString(file, "line1\nline2\n");

        String result = read(file, "", "");
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

        String result = read(file, "", "");
        assertTrue(result.contains("line-0"), result);
        assertTrue(result.contains("line-2999"), result);
        assertFalse(result.contains("安全模式已开启"), result);
    }

    @Test
    void largeFileBlockedBySizeInSafeMode(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("huge-line.txt");
        Files.writeString(file, "start-" + "x".repeat(200_000) + "-end");

        String result = read(file, "", "");
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

        String result = read(file, "", ",\"safe\":false");
        assertTrue(result.contains("line-0"), "safe=false 应返回正文");
        assertTrue(result.contains("line-3999"), result);
        assertFalse(result.contains("安全模式已开启"), result);
    }

    @Test
    void longSingleLineTruncatedByDefaultWithContinuationHint(@TempDir Path dir) throws Exception {
        // 单行 5000 字符但体积未超上限：默认按字符窗口截断，并提示续读位置
        Path file = dir.resolve("long-line.txt");
        Files.writeString(file, "head-" + "y".repeat(4995));

        String result = read(file, "", "");
        assertTrue(result.contains("head-"), result);
        assertTrue(result.contains("y".repeat(1995)), "默认应返回前 2000 字符");
        assertFalse(result.contains("y".repeat(1996)), "默认不应返回超过 2000 字符");
        assertTrue(result.contains("续读请设 startChar=2001"), result);
    }

    @Test
    void longSingleLineReadByCharWindow(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("long-line.txt");
        Files.writeString(file, "head-" + "y".repeat(4995));

        // 第二窗口：从第 2001 个字符开始
        String second = read(file, ",\"startChar\":2001", "");
        assertTrue(second.contains("y".repeat(2000)), second);
        assertTrue(second.contains("续读请设 startChar=4001"), second);

        // 窗口足够大时可一次读完，不再提示续读
        String full = read(file, ",\"charLimit\":10000", "");
        assertTrue(full.contains("head-"), full);
        assertTrue(full.contains("y".repeat(4995)), "charLimit 足够大时应返回整行");
        assertFalse(full.contains("续读"), full);
    }

    @Test
    void hugeSingleLineWindowedWithSafeFalse(@TempDir Path dir) throws Exception {
        // 超过安全上限的单行文件：safe=false + 字符窗口，可安全读取而不撑爆上下文
        Path file = dir.resolve("huge-line.txt");
        Files.writeString(file, "start-" + "x".repeat(200_000) + "-end");

        String result = read(file, "", ",\"safe\":false");
        assertFalse(result.contains("-end"), "默认窗口不应读到行尾");
        assertTrue(result.contains("续读请设 startChar=2001"), result);
        assertTrue(result.length() < 4000, "窗口化输出应远小于文件体积");
    }

    @Test
    void startCharBeyondLineLengthReportsRange(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("short.txt");
        Files.writeString(file, "abc\n");

        String result = read(file, ",\"startChar\":100", "");
        assertTrue(result.contains("startChar=100 超出范围"), result);
    }
}
