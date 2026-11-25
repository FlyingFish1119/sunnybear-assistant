package com.fishsunny.assistant.plug.vn.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.config.AssistantPathConfig;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.plug.vn.VisualNovelLoader;
import com.fishsunny.assistant.settings.UserSettings;
import com.fishsunny.assistant.utils.SessionFileManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/** 临时验证：四个 VN 创作工具的正常与异常路径（跑完即删） */
class VnAuthoringToolSmokeTest {

    private Path root;
    private VisualNovelLoader loader;
    private SessionFileManager sessionFileManager;
    private ObjectMapper objectMapper;
    private Map<String, Object> context;

    private static final String TITLE = "测试小说";
    private static final String CHAPTER_ID = "1-章节目录结构";

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("vn-authoring-test");
        objectMapper = new ObjectMapper();

        loader = new VisualNovelLoader(root.toString() + "/", objectMapper);

        AssistantPathConfig pathConfig = new AssistantPathConfig();
        ReflectionTestUtils.setField(pathConfig, "fileBasePath", root.toString());
        ReflectionTestUtils.setField(pathConfig, "settingsBasePath", root.resolve("settings").toString());
        sessionFileManager = new SessionFileManager(pathConfig, new UserSettings());

        context = Map.of("chatSession", new ChatSession().setId("sess_vn_test"));
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void runAll() throws Exception {
        section("① vn_meta_upsert_tool 新建小说 + 第一章");
        UpsertVisualNovelMetaTool metaUpsert = new UpsertVisualNovelMetaTool(objectMapper, loader);
        print(metaUpsert.action("""
                {"title":"测试小说","desc":"一部测试用视觉小说","outline":"第一章：相识。第二章：坦白。",
                 "coverImg":"cover.png","chapterOrder":1,"chapterTitle":"章节目录结构",
                 "chapterContent":"主角在放学后的教室遇到雪，决定如何回应。"}""", context));
        System.out.println("-> 落盘 data.json:\n" + readFile(root.resolve("vn/" + TITLE + "/data.json")));

        section("①-b 章节已存在且未传 chapterContent → 梗概保留");
        print(metaUpsert.action("{\"title\":\"测试小说\",\"chapterOrder\":1,\"chapterTitle\":\"章节目录结构\"}", context));
        System.out.println("-> chapter.md: " + readFile(root.resolve("vn/" + TITLE + "/" + CHAPTER_ID + "/chapter.md")));

        section("①-c 异常：只传 chapterOrder");
        try {
            metaUpsert.action("{\"title\":\"测试小说\",\"chapterOrder\":2}", context);
            System.out.println("!! 未抛异常");
        } catch (ToolExecutor.ToolExecuteException e) {
            System.out.println("ToolExecuteException: " + e.getMessage());
        }

        section("② vn_meta_get_tool 列表");
        GetVisualNovelMetaTool metaGet = new GetVisualNovelMetaTool(objectMapper, loader);
        print(metaGet.action("{}", context));

        section("②-b vn_meta_get_tool 详情");
        print(metaGet.action("{\"title\":\"测试小说\"}", context));

        section("②-c 异常：不存在的小说");
        try {
            metaGet.action("{\"title\":\"不存在\"}", context);
            System.out.println("!! 未抛异常");
        } catch (ToolExecutor.ToolExecuteException e) {
            System.out.println("ToolExecuteException: " + e.getMessage());
        }

        section("③ vn_node_get_tool template=true");
        GetVisualNovelNodeTool nodeGet = new GetVisualNovelNodeTool(objectMapper, loader, sessionFileManager);
        print(nodeGet.action("{\"template\":true}", context));
        System.out.println("-> template/HISTORY.md:\n" + readFile(sessionDir().resolve("vn/template/HISTORY.md")));
        System.out.println("-> template/README.md 前 30 行:\n" + firstLines(sessionDir().resolve("vn/template/README.md"), 30));

        section("③-b vn_node_get_tool 正常（本章暂无节点）");
        print(nodeGet.action("{\"title\":\"测试小说\",\"chapterId\":\"1-章节目录结构\"}", context));
        String nodesRel = "vn/" + TITLE + "/" + CHAPTER_ID + "/nodes.json";
        System.out.println("-> nodes.json 内容: " + readFile(sessionDir().resolve(nodesRel)));

        section("③-c 异常：chapterId 不存在");
        try {
            nodeGet.action("{\"title\":\"测试小说\",\"chapterId\":\"9-不存在\"}", context);
            System.out.println("!! 未抛异常");
        } catch (ToolExecutor.ToolExecuteException e) {
            System.out.println("ToolExecuteException: " + e.getMessage());
        }

        // 准备一张已存在的图片，验证缺失清单只报缺的
        Files.write(root.resolve("vn/" + TITLE + "/assets/classroom_day.png"), new byte[]{1});

        section("④ vn_node_upsert_tool 正常写回");
        Files.writeString(sessionDir().resolve(nodesRel), """
                {
                  "node_1": {
                    "type": "normal",
                    "background": "classroom_day.png",
                    "characters": [ { "id": "yuki", "sprite": "yuki_normal.png", "position": 35 } ],
                    "dialogues": [
                      { "id": "narrator", "name": "旁白", "text": "放学后的教室，只剩两个人。" },
                      { "id": "yuki", "name": "雪", "text": "你还没走？" }
                    ],
                    "nextNodeId": "node_2"
                  },
                  "node_2": {
                    "type": "choice",
                    "prompt": "要怎么回答？",
                    "options": [
                      { "text": "「在等你。」",
                        "remember": [ { "key": "confessed", "desc": "向雪表达了心意", "toast": "雪的脸红了" } ],
                        "targetNodeId": "node_3" },
                      { "text": "「收拾东西而已。」", "targetNodeId": "node_9" }
                    ]
                  },
                  "node_3": {
                    "type": "branch",
                    "branches": [
                      { "priority": 2, "requiredEvents": ["confessed", "rain_together"], "minMatch": 2,
                        "toast": "想起了那场雨", "targetNodeId": "node_4" }
                    ]
                  },
                  "node_4": { "type": "cg", "img": "cg_confession.png", "nextNodeId": "node_5" },
                  "node_5": { "type": "chapter", "targetChapter": "2-第二章", "targetNodeId": "node_1", "toast": "第二章 开启" }
                }
                """, StandardCharsets.UTF_8);
        UpsertVisualNovelNodeTool nodeUpsert = new UpsertVisualNovelNodeTool(objectMapper, loader, sessionFileManager);
        print(nodeUpsert.action("""
                {"title":"测试小说","chapterId":"1-章节目录结构",
                 "nodesPath":"sessionId:vn/测试小说/1-章节目录结构/nodes.json"}""", context));

        System.out.println("-> 落盘 nodes.json:\n" + readFile(root.resolve("vn/" + TITLE + "/" + CHAPTER_ID + "/nodes.json")));
        System.out.println("-> 会话 HISTORY.md 已同步:\n" + readFile(sessionDir().resolve("vn/" + TITLE + "/" + CHAPTER_ID + "/HISTORY.md")));

        section("④-b 全量覆盖语义：删掉节点后写回");
        String trimmed = Files.readString(sessionDir().resolve(nodesRel), StandardCharsets.UTF_8)
                .replaceAll("(?s),?\\s*\"node_5\"\\s*:\\s*\\{[^}]*\\}", "");
        Files.writeString(sessionDir().resolve(nodesRel), trimmed, StandardCharsets.UTF_8);
        print(nodeUpsert.action("""
                {"title":"测试小说","chapterId":"1-章节目录结构",
                 "nodesPath":"sessionId:vn/测试小说/1-章节目录结构/nodes.json"}""", context));
        System.out.println("-> 落盘节点键: " + loader.loadNodes(TITLE, CHAPTER_ID).keySet());

        section("④-c 异常：JSON 语法错误");
        Files.writeString(sessionDir().resolve(nodesRel), "{ \"node_1\": { \"type\": \"normal\", }", StandardCharsets.UTF_8);
        try {
            nodeUpsert.action("""
                    {"title":"测试小说","chapterId":"1-章节目录结构",
                     "nodesPath":"sessionId:vn/测试小说/1-章节目录结构/nodes.json"}""", context);
            System.out.println("!! 未抛异常");
        } catch (ToolExecutor.ToolExecuteException e) {
            System.out.println("ToolExecuteException: " + e.getMessage());
        }

        section("④-d 异常：choice 缺 options / chapter 缺 targetChapter");
        Files.writeString(sessionDir().resolve(nodesRel), """
                { "node_1": { "type": "choice", "prompt": "？" } }
                """, StandardCharsets.UTF_8);
        try {
            nodeUpsert.action("""
                    {"title":"测试小说","chapterId":"1-章节目录结构",
                     "nodesPath":"sessionId:vn/测试小说/1-章节目录结构/nodes.json"}""", context);
            System.out.println("!! 未抛异常");
        } catch (ToolExecutor.ToolExecuteException e) {
            System.out.println("ToolExecuteException: " + e.getMessage());
        }
        Files.writeString(sessionDir().resolve(nodesRel), """
                { "node_1": { "type": "chapter", "targetNodeId": "node_1" } }
                """, StandardCharsets.UTF_8);
        try {
            nodeUpsert.action("""
                    {"title":"测试小说","chapterId":"1-章节目录结构",
                     "nodesPath":"sessionId:vn/测试小说/1-章节目录结构/nodes.json"}""", context);
            System.out.println("!! 未抛异常");
        } catch (ToolExecutor.ToolExecuteException e) {
            System.out.println("ToolExecuteException: " + e.getMessage());
        }

        section("④-e 异常：nodesPath 不存在 / chapterId 不存在");
        try {
            nodeUpsert.action("""
                    {"title":"测试小说","chapterId":"1-章节目录结构",
                     "nodesPath":"sessionId:vn/不存在/nodes.json"}""", context);
            System.out.println("!! 未抛异常");
        } catch (ToolExecutor.ToolExecuteException e) {
            System.out.println("ToolExecuteException: " + e.getMessage());
        }
        Files.createDirectories(sessionDir().resolve("tmp"));
        Files.writeString(sessionDir().resolve("tmp/nodes.json"), "{}", StandardCharsets.UTF_8);
        try {
            nodeUpsert.action("""
                    {"title":"测试小说","chapterId":"9-不存在","nodesPath":"sessionId:tmp/nodes.json"}""", context);
            System.out.println("!! 未抛异常");
        } catch (ToolExecutor.ToolExecuteException e) {
            System.out.println("ToolExecuteException: " + e.getMessage());
        }

        section("⑤ vn_node_get_tool 再导出（有节点 + 同步后的 HISTORY）");
        print(nodeGet.action("{\"title\":\"测试小说\",\"chapterId\":\"1-章节目录结构\"}", context));
        System.out.println("-> HISTORY.md:\n" + readFile(sessionDir().resolve("vn/" + TITLE + "/" + CHAPTER_ID + "/HISTORY.md")));
        System.out.println("-> README.md 五、图片资源:\n" + lastLines(sessionDir().resolve("vn/" + TITLE + "/" + CHAPTER_ID + "/README.md"), 8));
        System.out.println("-> 会话 nodes.json（AI 实际编辑的文件）:\n" + readFile(sessionDir().resolve(nodesRel)));

        section("⑥ 工具导出的 nodes.json 原样写回（往返一致性）");
        print(nodeUpsert.action("""
                {"title":"测试小说","chapterId":"1-章节目录结构",
                 "nodesPath":"sessionId:vn/测试小说/1-章节目录结构/nodes.json"}""", context));
    }

    private Path sessionDir() {
        return root.resolve("session/sess_vn_test/file");
    }

    private void print(ToolExecutor.ToolExecuteResponse response) {
        System.out.println("=== " + response.getName() + " ===\n" + response.getResult());
    }

    private void section(String title) {
        System.out.println("\n\n########## " + title + " ##########");
    }

    private String readFile(Path path) throws Exception {
        return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : "(文件不存在: " + path + ")";
    }

    private String firstLines(Path path, int count) throws Exception {
        return Files.readAllLines(path, StandardCharsets.UTF_8).stream().limit(count).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private String lastLines(Path path, int count) throws Exception {
        var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        return String.join("\n", lines.subList(Math.max(0, lines.size() - count), lines.size()));
    }
}
