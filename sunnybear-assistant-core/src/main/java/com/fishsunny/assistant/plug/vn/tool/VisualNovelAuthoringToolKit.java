package com.fishsunny.assistant.plug.vn.tool;

/*
 * @Usage 视觉小说编写工具包 —— 让主助手直接读写视觉小说（大纲元数据 / 章节梗概 / 节点剧本 JSON）。
 *        本工具集默认不对主对话开放（excludeFromMainAgent 声明为 true），需要在设置页显式开启；
 *        可用 plug.vn.tool.authoring.enable 关闭。
 *
 *        节点写回统一走「全量覆盖」（VisualNovelLoader#saveNodes）：get 把整章节点摊成会话目录下的
 *        nodes.json，AI 用文件工具就地编辑，upsert 把文件内容整份写回并出一份体检报告。
 *        本类的静态方法负责把节点数据翻译成给 AI 读的 Markdown（HISTORY.md / README.md）与模板。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/25 12:00
 */

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.plug.vn.entity.node.VisualNovelNode;
import com.fishsunny.assistant.plug.vn.entity.node.dispatch.VisualNovelBranchNode;
import com.fishsunny.assistant.plug.vn.entity.node.dispatch.VisualNovelChapterNode;
import com.fishsunny.assistant.plug.vn.entity.node.dispatch.VisualNovelChoiceNode;
import com.fishsunny.assistant.plug.vn.entity.node.resources.VisualNovelCGNode;
import com.fishsunny.assistant.plug.vn.entity.node.resources.VisualNovelDialogues;
import com.fishsunny.assistant.plug.vn.entity.node.resources.VisualNovelNormalNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "plug.vn.tool.authoring.enable", havingValue = "true", matchIfMissing = true)
public class VisualNovelAuthoringToolKit extends ToolKit {

    public VisualNovelAuthoringToolKit(List<ToolHandler> tools) {
        super(tools);
    }

    @Override
    public String displayName() {
        return "视觉小说创作";
    }

    @Override
    public String description() {
        return "让主助手新建、查看、编写视觉小说（大纲元数据、章节梗概与节点剧本 JSON）";
    }

    @Override
    public boolean excludeFromMainAgent() {
        return true;
    }

    // ==================== 节点数据读写辅助 ====================

    /**
     * 解析 nodes.json 文本为有序节点表。
     * 解析失败时抛原始异常（Jackson 的报错信息里带行号与列号），由调用方转成可读提示。
     */
    static LinkedHashMap<String, VisualNovelNode> parseNodes(ObjectMapper objectMapper, String json) throws IOException {
        JavaType type = objectMapper.getTypeFactory()
                .constructMapType(LinkedHashMap.class, String.class, VisualNovelNode.class);
        LinkedHashMap<String, VisualNovelNode> nodes = objectMapper.readValue(json, type);
        return nodes == null ? new LinkedHashMap<>() : nodes;
    }

    /**
     * 把节点集合渲染成便于 AI 编辑的 JSON 文本：忽略 null 与空集合 / 空串（避免满屏 "requiredEvents": [ ] 噪音），
     * 缩进 2 空格。解析回来语义等价，只是少了空字段的视觉噪音。
     */
    static String toNodesJson(ObjectMapper objectMapper, Map<String, VisualNovelNode> nodes) {
        ObjectMapper vnMapper = objectMapper.copy();
        vnMapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        vnMapper.enable(SerializationFeature.INDENT_OUTPUT);
        // 与 VisualNovelLoader 落盘时的排版一致：缩进 2 空格、换行 LF、键值间不留多余空格
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentObjectsWith(new DefaultIndenter("  ", "\n"));
        vnMapper.setDefaultPrettyPrinter(printer.withSeparators(
                Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER)));
        try {
            return vnMapper.writeValueAsString(nodes);
        } catch (Exception e) {
            throw new IllegalStateException("序列化节点失败: " + e.getMessage(), e);
        }
    }

    // ==================== HISTORY.md ====================

    /**
     * 把节点集合渲染成 HISTORY.md —— 剥离 JSON 语法噪音与立绘坐标的纯文本速览，
     * 让 AI 用最低成本读完剧情脉络与流向。本文件是只读物，改它不会生效。
     */
    static String buildHistoryMarkdown(String title, String chapterId, Map<String, VisualNovelNode> nodes) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append(" / ").append(chapterId).append(" 剧情速览\n\n");
        sb.append("> 本文件由 nodes.json 生成，仅供低成本阅读剧情脉络与流向，修改本文件不会生效；\n");
        sb.append("> 如需修改或续写，请按节点 ID 用 `file_search_tool` 在 `nodes.json` 中定位行号，\n");
        sb.append("> 再用 `file_edit_tool` 编辑 `nodes.json`，最后用 `vn_node_upsert_tool` 全量写回。\n\n");
        if (nodes == null || nodes.isEmpty()) {
            sb.append("（本章暂无节点）\n");
            return sb.toString();
        }
        for (Map.Entry<String, VisualNovelNode> entry : nodes.entrySet()) {
            appendHistoryNode(sb, entry.getKey(), entry.getValue());
        }
        return sb.toString();
    }

    private static void appendHistoryNode(StringBuilder sb, String nodeId, VisualNovelNode node) {
        sb.append("## [").append(nodeId).append("] (").append(node == null ? "null" : node.getType()).append(")");
        if (node != null && StringUtils.hasText(node.getNextNodeId())) {
            sb.append(" -> next: `").append(node.getNextNodeId()).append("`");
        }
        sb.append("\n");

        if (node instanceof VisualNovelNormalNode normal) {
            sb.append("- 资源：bg=`").append(value(normal.getBackground())).append("` | 立绘：")
              .append(describeCharacters(normal.getCharacters())).append("\n");
            appendDialogues(sb, normal.getDialogues());
        } else if (node instanceof VisualNovelCGNode cg) {
            sb.append("- 资源：cg=`").append(value(cg.getImg())).append("`\n");
            appendDialogues(sb, cg.getDialogues());
        } else if (node instanceof VisualNovelChoiceNode choice) {
            appendChoice(sb, choice);
        } else if (node instanceof VisualNovelBranchNode branch) {
            appendBranch(sb, branch);
        } else if (node instanceof VisualNovelChapterNode chapter) {
            sb.append("- 跳转章节：targetChapter=`").append(value(chapter.getTargetChapter()))
              .append("` | targetNodeId=`").append(value(chapter.getTargetNodeId()))
              .append("` | toast=`").append(value(chapter.getToast())).append("`\n");
        } else {
            sb.append("- （未知节点类型）\n");
        }
        sb.append("\n");
    }

    /** 立绘速览：id(`sprite`)，坐标与缩放属于画面细节，不占上下文 */
    private static String describeCharacters(List<VisualNovelNormalNode.Character> characters) {
        if (characters == null || characters.isEmpty()) {
            return "（无）";
        }
        List<String> items = new ArrayList<>();
        for (VisualNovelNormalNode.Character character : characters) {
            if (character == null) {
                continue;
            }
            items.add(value(character.getId()) + "(`" + value(character.getSprite()) + "`)");
        }
        return items.isEmpty() ? "（无）" : String.join(", ", items);
    }

    private static void appendDialogues(StringBuilder sb, List<VisualNovelDialogues> dialogues) {
        if (dialogues == null || dialogues.isEmpty()) {
            sb.append("- 对话：（无）\n");
            return;
        }
        sb.append("- 对话：\n");
        for (VisualNovelDialogues dialogue : dialogues) {
            if (dialogue == null) {
                continue;
            }
            sb.append("  - **").append(displayName(dialogue)).append("**(`").append(value(dialogue.id()))
              .append("`)：").append(value(dialogue.text())).append("\n");
        }
    }

    private static void appendChoice(StringBuilder sb, VisualNovelChoiceNode choice) {
        sb.append("- 提示：").append(value(choice.getPrompt())).append("\n");
        if (choice.getOptions() == null || choice.getOptions().isEmpty()) {
            sb.append("- 选项：（无）\n");
            return;
        }
        sb.append("- 选项：\n");
        int index = 1;
        for (VisualNovelChoiceNode.Options option : choice.getOptions()) {
            if (option == null) {
                continue;
            }
            sb.append("  ").append(index++).append(". ").append(quote(option.getText()))
              .append(" -> `").append(value(option.getTargetNodeId())).append("`\n");
            if (option.getRequiredEvents() != null && !option.getRequiredEvents().isEmpty()) {
                sb.append("     - 条件：").append(events(option.getRequiredEvents()))
                  .append(option.getMinMatch() == null ? "" : "（至少匹配 " + option.getMinMatch() + " 个）")
                  .append("\n");
            }
            if (option.getRemember() != null && !option.getRemember().isEmpty()) {
                sb.append("     - 记住：").append(remember(option.getRemember())).append("\n");
            }
        }
    }

    private static void appendBranch(StringBuilder sb, VisualNovelBranchNode branch) {
        sb.append("- 默认兜底跳转：`").append(value(branch.getDefaultTargetNodeId())).append("`\n");
        if (branch.getBranches() == null || branch.getBranches().isEmpty()) {
            sb.append("- 分支：（无）\n");
            return;
        }
        sb.append("- 分支：\n");
        int index = 1;
        for (VisualNovelBranchNode.Branch item : branch.getBranches()) {
            if (item == null) {
                continue;
            }
            sb.append("  ").append(index++).append(". 优先级 ")
              .append(item.getPriority() == null ? "-" : item.getPriority())
              .append(" -> `").append(value(item.getTargetNodeId())).append("`");
            if (item.getRequiredEvents() != null && !item.getRequiredEvents().isEmpty()) {
                sb.append("；条件：").append(events(item.getRequiredEvents()));
            }
            if (item.getMinMatch() != null) {
                sb.append("；minMatch=").append(item.getMinMatch());
            }
            if (StringUtils.hasText(item.getToast())) {
                sb.append("；toast：").append(item.getToast());
            }
            sb.append("\n");
        }
    }

    private static String quote(String text) {
        String value = StringUtils.hasText(text) ? text : "（空）";
        return value.startsWith("「") && value.endsWith("」") ? value : "「" + value + "」";
    }

    private static String remember(List<VisualNovelChoiceNode.Remember> remembers) {
        List<String> items = new ArrayList<>();
        for (VisualNovelChoiceNode.Remember item : remembers) {
            if (item == null) {
                continue;
            }
            items.add(value(item.getKey()) + "(" + value(item.getDesc()) + " / " + value(item.getToast()) + ")");
        }
        return items.isEmpty() ? "（无）" : String.join("、", items);
    }

    private static String events(List<String> events) {
        List<String> items = new ArrayList<>();
        for (String event : events) {
            if (StringUtils.hasText(event)) {
                items.add("`" + event + "`");
            }
        }
        return items.isEmpty() ? "（无）" : String.join("、", items);
    }

    // ==================== README.md ====================

    /**
     * 生成 README.md —— 节点编写指南：工作流、五类节点字段与示例、核心设计准则、图片资源现状。
     *
     * @param assetsDir      小说 assets/ 目录；模板场景传 null，只给示例路径
     * @param existingAssets assets/ 下已有的图片文件名
     */
    static String buildReadmeMarkdown(String title, String chapterId, Path assetsDir, List<String> existingAssets) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 视觉小说节点编写指南：").append(title).append(" / ").append(chapterId).append("\n\n");
        sb.append("本目录三个文件：`nodes.json`（本章节点数据，唯一需要编辑的文件）、")
          .append("`HISTORY.md`（剧情速览，只读）、`README.md`（本文件）。\n\n");

        sb.append("## 一、工作流\n\n");
        sb.append("1. 先读 `HISTORY.md`，了解前情与节点流向。\n");
        sb.append("2. 用 `file_search_tool` 在 `nodes.json` 中搜节点 ID，定位要改的行。\n");
        sb.append("3. 用 `file_edit_tool` 局部改，或 `file_write_tool` 整段重写 `nodes.json`。\n");
        sb.append("4. 调用 `vn_node_upsert_tool`（title / chapterId / nodesPath）写回。\n\n");
        sb.append("> 写回是**全量覆盖**：`nodes.json` 就是本章的权威快照，")
          .append("文件里没有的节点会被删除，新增的节点会被创建。\n\n");

        sb.append("## 二、节点通用字段\n\n");
        sb.append("| 字段 | 类型 | 说明 |\n|------|------|------|\n");
        sb.append("| `type` | string | 节点类型，五选一：`normal` / `cg` / `choice` / `branch` / `chapter`，必填 |\n");
        sb.append("| `nextNodeId` | string | 可选，本章下一个节点 ID（`choice` / `branch` / `chapter` 用各自的目标字段） |\n\n");
        sb.append("键（如 `node_1`）就是节点 ID，同章内唯一，被别的节点用 `nextNodeId` / `targetNodeId` 引用。\n\n");

        sb.append("## 三、五类节点\n\n");

        sb.append("### 1. normal —— 静态画面 + 连续对话\n\n");
        sb.append("| 字段 | 类型 | 说明 |\n|------|------|------|\n");
        sb.append("| `background` | string | 背景图文件名（相对 `assets/`） |\n");
        sb.append("| `characters` | array | 立绘列表，字段见下 |\n");
        sb.append("| `dialogues` | array | 对话列表，字段见下 |\n\n");
        sb.append("`characters[]`：`id`（立绘角色 ID）、`sprite`（立绘文件名）、`position`（0~100 整数百分比，")
          .append("25 左 / 50 中 / 75 右）、可选 `scale`（缩放）、`z`（层级）、`flipX`（水平翻转）。\n\n");
        sb.append("`dialogues[]`：`id`（对应立绘角色 ID，旁白用 `\"narrator\"`）、`name`（对话框显示名，")
          .append("支持聊到一半才揭晓真名）、`text`（台词）。\n\n");
        sb.append("""
                ```json
                "node_1": {
                  "type": "normal",
                  "background": "classroom_day.png",
                  "characters": [
                    { "id": "yuki", "sprite": "yuki_normal.png", "position": 35 }
                  ],
                  "dialogues": [
                    { "id": "narrator", "name": "旁白", "text": "放学后的教室，只剩两个人。" },
                    { "id": "yuki", "name": "雪", "text": "你还没走？" }
                  ],
                  "nextNodeId": "node_2"
                }
                ```

                """);

        sb.append("### 2. cg —— 整张插画 + 连续对话\n\n");
        sb.append("| 字段 | 类型 | 说明 |\n|------|------|------|\n");
        sb.append("| `img` | string | 插画文件名（相对 `assets/`） |\n");
        sb.append("| `dialogues` | array | 同 `normal` |\n\n");
        sb.append("""
                ```json
                "node_4": {
                  "type": "cg",
                  "img": "cg_confession.png",
                  "dialogues": [
                    { "id": "yuki", "name": "雪", "text": "……那，一起回去吧。" }
                  ],
                  "nextNodeId": "node_5"
                }
                ```

                """);

        sb.append("### 3. choice —— 玩家选项\n\n");
        sb.append("| 字段 | 类型 | 说明 |\n|------|------|------|\n");
        sb.append("| `prompt` | string | 选项提示语 |\n");
        sb.append("| `options` | array | 至少一项 |\n\n");
        sb.append("`options[]`：`text`（选项文本）、`targetNodeId`（跳转目标）、")
          .append("可选 `requiredEvents`（该选项出现/可选所需事件）、`minMatch`（至少匹配几个事件）、")
          .append("`remember`（选中后记住的事件，每项 `key` / `desc` / `toast`）。\n\n");
        sb.append("""
                ```json
                "node_2": {
                  "type": "choice",
                  "prompt": "要怎么回答？",
                  "options": [
                    {
                      "text": "「在等你。」",
                      "remember": [
                        { "key": "confessed", "desc": "向雪表达了心意", "toast": "雪的脸红了" }
                      ],
                      "targetNodeId": "node_3"
                    },
                    {
                      "text": "「收拾东西而已。」",
                      "targetNodeId": "node_4"
                    }
                  ]
                }
                ```

                """);

        sb.append("### 4. branch —— 按事件自动分流（无玩家交互）\n\n");
        sb.append("| 字段 | 类型 | 说明 |\n|------|------|------|\n");
        sb.append("| `defaultTargetNodeId` | string | 必填，当所有条件分支均未命中时的默认兜底目标节点 ID（防止卡死） |\n");
        sb.append("| `branches` | array | 条件分支列表，按 `priority` 优先级匹配，第一条命中的生效 |\n\n");
        sb.append("`branches[]`：`priority`（优先级，数字越小优先级越高，如 1 优先于 2）、`targetNodeId`（跳转目标）、")
          .append("`requiredEvents`（条件事件列表）、可选 `minMatch`（至少匹配几个事件，缺省视作全部匹配）、`toast`（匹配时的一句提示）。\n\n");
        sb.append("""
                ```json
                "node_3": {
                  "type": "branch",
                  "defaultTargetNodeId": "node_5",
                  "branches": [
                    {
                      "priority": 1,
                      "requiredEvents": ["confessed", "rain_together"],
                      "minMatch": 2,
                      "toast": "想起了那场雨",
                      "targetNodeId": "node_4"
                    },
                    {
                      "priority": 2,
                      "requiredEvents": ["confessed"],
                      "minMatch": 1,
                      "targetNodeId": "node_4"
                    }
                  ]
                }
                ```

                """);

        sb.append("### 5. chapter —— 跳转到另一章\n\n");
        sb.append("| 字段 | 类型 | 说明 |\n|------|------|------|\n");
        sb.append("| `targetChapter` | string | 目标章节 ID，形如 `\"2-第二章标题\"` |\n");
        sb.append("| `targetNodeId` | string | 目标章节内的起始节点 ID |\n");
        sb.append("| `toast` | string | 可选，跳转时的提示文本 |\n\n");
        sb.append("""
                ```json
                "node_5": {
                  "type": "chapter",
                  "targetChapter": "2-第二章标题",
                  "targetNodeId": "node_1",
                  "toast": "第二章 开启"
                }
                ```

                """);

        sb.append("## 四、核心设计准则\n\n");
        sb.append("1. **一个资源节点 = 一次固定的静态画面 + 该画面下的多段连续对话。** ")
          .append("`normal` / `cg` 都遵循这条：只要换背景、增减人物、或换立绘差分，就切到下一个节点；")
          .append("画面没变就继续在同一个节点里堆 `dialogues`，不要为每句台词开一个节点。\n");
        sb.append("2. **位置用 0~100 的整数百分比**：`position` 从左到右，如 25 左、50 中、75 右；")
          .append("`scale` / `z` / `flipX` 是可选字段，没有特殊需求就不要写。\n");
        sb.append("3. **`dialogues[].id` 对应立绘角色的 `id`**，旁白统一用 `\"narrator\"`；")
          .append("`name` 是对话框里显示的名字，可以前后不同（例如先显示「？？？」再揭晓真名）。\n");
        sb.append("4. **条件判定只用正向的 `requiredEvents` + `minMatch`**：`remember` 只出现在 `choice` 的选项里，")
          .append("用来记录玩家做过的事；判定时配合 `branch` 的 `priority` 从高到低匹配，")
          .append("即可表达各种正面、负面或爱恨交织的组合分支（高优先级写「同时满足 A 和 B」，低优先级写「只满足 A」）。\n");
        sb.append("5. **自然断点机制**：当某个节点的 `nextNodeId` / `targetNodeId` 指向一个当前 `nodes.json` 中")
          .append("尚不存在的节点 ID 时，游戏运行到此处就停下等待续写 —— 这就是「自然断点」。")
          .append("想留待续写，不必造占位节点，直接让指针悬空即可；`vn_node_upsert_tool` 会把它们列出来给你核对。\n");
        sb.append("6. **图片资源与节点解耦**：节点里只写纯文件名（如 `\"study_room_night.png\"`），")
          .append("不写路径、不写 URL；所有图片统一扁平存放在小说的 `assets/` 目录下。\n\n");

        sb.append("## 五、图片资源\n\n");
        sb.append("- `assets/` 目录：`").append(assetsDir == null
                ? "（示例）{小说目录}/assets/"
                : assetsDir.toAbsolutePath().normalize().toString()).append("`\n");
        if (existingAssets == null || existingAssets.isEmpty()) {
            sb.append("- 现有图片：（暂无）\n");
        } else {
            sb.append("- 现有图片（").append(existingAssets.size()).append("）：");
            List<String> items = new ArrayList<>();
            for (String asset : existingAssets) {
                items.add("`" + asset + "`");
            }
            sb.append(String.join("、", items)).append("\n");
        }
        sb.append("- 引用了不存在的图片时，写回报告里会单独列出来，提醒补图或改名。\n");
        return sb.toString();
    }

    // ==================== 节点模板 ====================

    /** 五类节点的最小标准写法，覆盖 normal / choice / branch / cg / chapter 一条完整链路 */
    static String buildTemplateNodesJson() {
        return """
                {
                  "node_1": {
                    "type": "normal",
                    "background": "classroom_day.png",
                    "characters": [
                      { "id": "yuki", "sprite": "yuki_normal.png", "position": 35 }
                    ],
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
                      {
                        "text": "在等你。",
                        "remember": [
                          { "key": "confessed", "desc": "向雪表达了心意", "toast": "雪的脸红了" }
                        ],
                        "targetNodeId": "node_3"
                      },
                      {
                        "text": "收拾东西而已。",
                        "targetNodeId": "node_4"
                      }
                    ]
                  },
                  "node_3": {
                    "type": "branch",
                    "defaultTargetNodeId": "node_5",
                    "branches": [
                      {
                        "priority": 1,
                        "requiredEvents": ["confessed", "rain_together"],
                        "minMatch": 2,
                        "toast": "想起了那场雨",
                        "targetNodeId": "node_4"
                      },
                      {
                        "priority": 2,
                        "requiredEvents": ["confessed"],
                        "minMatch": 1,
                        "targetNodeId": "node_4"
                      }
                    ]
                  },
                  "node_4": {
                    "type": "cg",
                    "img": "cg_confession.png",
                    "dialogues": [
                      { "id": "yuki", "name": "雪", "text": "……那，一起回去吧。" }
                    ],
                    "nextNodeId": "node_5"
                  },
                  "node_5": {
                    "type": "chapter",
                    "targetChapter": "2-第二章标题",
                    "targetNodeId": "node_1",
                    "toast": "第二章 开启"
                  }
                }
                """;
    }

    // ==================== 文本辅助 ====================

    /** 缺失值统一渲染成「（空）」，保证 Markdown 结构稳定、不留空档 */
    private static String value(String text) {
        return StringUtils.hasText(text) ? text : "（空）";
    }

    /** 对话名缺失时退回角色 id，避免出现空的 **粗体** */
    private static String displayName(VisualNovelDialogues dialogue) {
        return StringUtils.hasText(dialogue.name()) ? dialogue.name() : value(dialogue.id());
    }
}
