package com.fishsunny.assistant.engine.tool.instance.knowledge;

/*
 * @Usage 知识库列表工具 — 分页查询知识条目，支持语义搜索
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/25
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.KnowledgeRecord;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.KnowledgeToolKit;
import com.fishsunny.assistant.mvc.service.KnowledgeService;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 知识库列表工具
 * 支持通过 name 关键词做语义搜索（embedding + 余弦相似度），
 * 也可不传 name 按创建时间倒序返回全部条目。
 * 每页固定 10 条，支持 offset 翻页。
 */
@ToolKitComponent(KnowledgeToolKit.class)
@ConditionalOnExpression("${engine.tool.knowledge.enable:true} && ${engine.tool.knowledge.knowledge-list.enable:true}")
public class KnowledgeListTool implements ToolHandler {

    public static final String NAME = "knowledge_list_tool";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int FIXED_LIMIT = 10;

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final KnowledgeService knowledgeService;

    @Autowired
    public KnowledgeListTool(ObjectMapper objectMapper, KnowledgeService knowledgeService) {
        this.objectMapper = objectMapper;
        this.knowledgeService = knowledgeService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("分页查询知识库条目列表。传入 name 时按语义相似度排序，不传 name 时按创建时间倒序。每页固定返回 10 条，使用 offset 翻页。返回条目摘要（ID、简介、创建时间），查看完整内容请用 knowledge_read_tool。")
                .setRequired(List.of())
                .setParameters(List.of(
                        new ToolRegister.Parameters("name", "string",
                                "搜索关键词，用于语义匹配知识条目简介。不传时返回全部条目（按创建时间倒序）"),
                        new ToolRegister.Parameters("offset", "integer",
                                "跳过的条目数，默认 0（从第一条开始）。例如 offset=10 表示跳过前 10 条，配合每页 10 条实现翻页")
                ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }

        String name = arguments.getName();
        int offset = arguments.getOffset() != null ? arguments.getOffset() : 0;

        if (offset < 0) {
            throw new ToolExecutor.ToolExecuteException("offset 不能为负数");
        }

        try {
            KnowledgeService.ListKnowledgeResult result = knowledgeService.listKnowledge(name, offset);
            List<KnowledgeRecord> items = result.items();
            int total = result.total();

            StringBuilder sb = new StringBuilder();

            if (total == 0) {
                sb.append("当前知识库中没有任何条目。\n\n");
                sb.append("你可以使用 post_knowledge_tool 添加知识条目。");
            } else {
                int from = offset + 1;
                int to = Math.min(offset + items.size(), total);
                int remaining = total - to;

                sb.append("知识库列表（共 **").append(total).append("** 条");
                sb.append("，当前显示第 **").append(from).append("** ~ **").append(to).append("** 条");
                sb.append("）\n");

                for (int i = 0; i < items.size(); i++) {
                    KnowledgeRecord record = items.get(i);
                    sb.append("\n---\n\n");
                    sb.append("**").append(from + i).append("**. **").append(record.getIntro()).append("**\n");
                    sb.append("- ID: `").append(record.getId()).append("`\n");
                    sb.append("- 创建时间: ").append(record.getCreateTime() != null
                            ? record.getCreateTime().format(FORMATTER) : "未知").append("\n");
                }

                sb.append("\n---\n\n");
                sb.append("剩余 **").append(remaining).append("** 条\n");

                if (remaining > 0) {
                    sb.append("💡 分页提示：当前 offset=").append(offset)
                            .append("，每页 ").append(FIXED_LIMIT).append(" 条。")
                            .append("查看后续条目可将 offset 设为 ").append(offset + FIXED_LIMIT)
                            .append("。");
                } else {
                    sb.append("已是最后一页。");
                }
            }

            return new ToolExecutor.ToolExecuteResponse(name(), sb.toString());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("查询知识库列表失败: " + e.getMessage());
        }
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
    @Accessors(chain = true)
    private static class Arguments {
        private String name;
        private Integer offset;
    }
}