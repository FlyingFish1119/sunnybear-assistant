package com.fishsunny.assistant.engine.tool.instance.file;

/*
 * @Usage FileSearchTool rg 后端冒烟测试
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/26 15:00
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSearchToolTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 搜索范围：项目 engine/tool 目录（含 FileSearchTool 自身） */
    private static final String SEARCH_ROOT = "src/main/java/com/fishsunny/assistant/engine/tool";

    private FileSearchTool newTool() {
        return new FileSearchTool(OBJECT_MAPPER);
    }

    private ToolExecutor.ToolExecuteResponse search(String args) throws Exception {
        return newTool().action(args, Map.of());
    }

    @Test
    void literalSearchFindsToolFile() throws Exception {
        ToolExecutor.ToolExecuteResponse resp = search(
                "{\"path\":\"" + SEARCH_ROOT + "\",\"pattern\":\"file_search_tool\"}");
        String result = resp.getResult();
        System.out.println("===== literalSearchFindsToolFile =====");
        System.out.println(result);
        assertTrue(result.contains("匹配结果:"), result);
        assertTrue(result.contains("FileSearchTool.java"), "应命中 FileSearchTool.java");
        assertTrue(result.contains("file_search_tool"), "匹配行应包含搜索词");
    }

    @Test
    void caseSensitiveMissReturnsZero() throws Exception {
        ToolExecutor.ToolExecuteResponse resp = search(
                "{\"path\":\"" + SEARCH_ROOT + "\",\"pattern\":\"FILE_SEARCH_TOOL\",\"caseSensitive\":true}");
        String result = resp.getResult();
        System.out.println("===== caseSensitiveMissReturnsZero =====");
        System.out.println(result);
        assertTrue(result.contains("匹配结果: 0 条"), result);
        assertTrue(result.contains("未找到匹配内容"), result);
    }

    @Test
    void globFilterAndContextLines() throws Exception {
        ToolExecutor.ToolExecuteResponse resp = search(
                "{\"path\":\"" + SEARCH_ROOT + "\",\"pattern\":\"RipgrepRunner\",\"filter\":\"RipgrepRunner.java\",\"contextLines\":1}");
        String result = resp.getResult();
        System.out.println("===== globFilterAndContextLines =====");
        System.out.println(result);
        assertTrue(result.contains("文件过滤: RipgrepRunner.java"), result);
        assertTrue(result.contains("RipgrepRunner.java"), result);
        // 上下文行用 │  前缀标记（匹配行用 │─）
        assertTrue(result.contains("│─"), "应包含匹配行标记");
        assertTrue(result.contains("│  "), "应包含上下文行标记");
    }

    @Test
    void regexSearch() throws Exception {
        ToolExecutor.ToolExecuteResponse resp = search(
                "{\"path\":\"" + SEARCH_ROOT + "\",\"pattern\":\"public\\\\s+final\\\\s+class\",\"useRegex\":true}");
        String result = resp.getResult();
        System.out.println("===== regexSearch =====");
        System.out.println(result);
        assertTrue(result.contains("（正则"), result);
        assertFalse(result.contains("未找到匹配内容"), result);
    }

    @Test
    void hiddenParamControlsDotFileSearch() throws Exception {
        Path searchRoot = Files.createTempDirectory("rg-hidden-test");
        Path dotFile = searchRoot.resolve(".secret.txt");
        Files.writeString(dotFile, "hidden_marker_42");
        String rootPath = searchRoot.toString().replace("\\", "\\\\");
        try {
            // 默认不搜索隐藏文件
            ToolExecutor.ToolExecuteResponse withoutHidden = search(
                    "{\"path\":\"" + rootPath + "\",\"pattern\":\"hidden_marker_42\"}");
            assertTrue(withoutHidden.getResult().contains("匹配结果: 0 条"), withoutHidden.getResult());

            // hidden=true 时搜到隐藏文件
            ToolExecutor.ToolExecuteResponse withHidden = search(
                    "{\"path\":\"" + rootPath + "\",\"pattern\":\"hidden_marker_42\",\"hidden\":true}");
            String result = withHidden.getResult();
            System.out.println("===== hiddenParamControlsDotFileSearch =====");
            System.out.println(result);
            assertTrue(result.contains(".secret.txt"), result);
            assertTrue(result.contains("hidden_marker_42"), result);
        } finally {
            Files.deleteIfExists(dotFile);
            Files.deleteIfExists(searchRoot);
        }
    }
}
