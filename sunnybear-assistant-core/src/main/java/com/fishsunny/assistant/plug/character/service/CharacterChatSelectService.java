package com.fishsunny.assistant.plug.character.service;

/*
 * @Usage 角色快捷选项（chat-select）生成服务
 *
 * 职责：
 *  1) 解析 character_info.chat_select 配置（enable / format）
 *  2) 把对话历史按“QA 整轮”切片，并做“满 7 丢 5 保 2”的滑动窗口压缩，
 *     只把最新一段连续窗口发给 mission 模型，用于为最新一条 assistant 回复生成快捷选项
 *  3) 解析并清洗 mission 返回的 <chat-select> 标记，输出标准标记
 *  4) 提供把 <chat-select> 标签从文本中剥除的工具（主对话喂给对话 AI 前使用）
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/3
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.plug.character.entity.CharacterInfo;
import com.fishsunny.assistant.settings.AISettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CharacterChatSelectService {

    private static final Logger log = LoggerFactory.getLogger(CharacterChatSelectService.class);

    /** 角色字段缺省值 */
    private static final String DEFAULT_CONFIG_JSON = "{}";

    /** 一个 QA 窗口最多攒到多少条后裁剪 */
    private static final int WINDOW_MAX = 7;
    /** 每满 7 条丢掉的条数（丢开头的 5，剩 2 继续累计） */
    private static final int WINDOW_DROP = 5;
    /** 发给 mission 的对话正文最大长度保护 */
    private static final int MAX_CONTEXT_CHARS = 9000;
    /** 单条选项内容最大长度 */
    private static final int MAX_OPTION_CHARS = 200;
    /** 单轮最多保留的选项数 */
    private static final int MAX_OPTION_COUNT = 8;

    /** <chat-select ...> ... </chat-select>（含自闭合形式） */
    private static final Pattern SELECT_BLOCK = Pattern.compile(
            "<chat-select\\b[^>]*>.*?</chat-select>|<chat-select\\b[^>]*/>", Pattern.DOTALL);
    /** <chat-option .../> 或 <chat-option ...>...</chat-option> */
    private static final Pattern OPTION_TAG = Pattern.compile(
            "<chat-option\\b([^>]*?)/>|<chat-option\\b([^>]*)>(.*?)</chat-option>", Pattern.DOTALL);

    private final ChatMessageService chatMessageService;
    private final ChatHttpHandler chatHttpHandler;
    private final AISettings missionAISettings;
    private final ObjectMapper objectMapper;

    public CharacterChatSelectService(ChatMessageService chatMessageService,
                                      ChatHttpHandler chatHttpHandler,
                                      @Qualifier(AISettings.MISSION) AISettings missionAISettings,
                                      ObjectMapper objectMapper) {
        this.chatMessageService = chatMessageService;
        this.chatHttpHandler = chatHttpHandler;
        this.missionAISettings = missionAISettings;
        this.objectMapper = objectMapper;
    }

    /** 角色 chat_select 配置 */
    public record ChatSelectConfig(boolean enable, String format) {
    }

    /**
     * 解析角色配置。JSON 结构：{"enable": bool, "format": "..."}
     */
    public ChatSelectConfig parseConfig(CharacterInfo character) {
        if (character == null) {
            return new ChatSelectConfig(false, "");
        }
        String json = character.getChatSelect();
        if (!StringUtils.hasText(json)) {
            return new ChatSelectConfig(false, "");
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            boolean enable = Boolean.TRUE.equals(map.get("enable"));
            Object formatObj = map.get("format");
            String format = formatObj == null ? "" : String.valueOf(formatObj);
            return new ChatSelectConfig(enable, format);
        } catch (Exception e) {
            log.warn("解析角色 [{}] chat_select JSON 失败: {}", character.getId(), e.getMessage());
            return new ChatSelectConfig(false, "");
        }
    }

    // ==================== 标签工具 ====================

    /**
     * 从一段文本中剥除快捷选项标记。
     * 约定标记总是在消息正文末尾追加，因此从首个 <chat-select 出现处直接截断、丢弃其后内容即可。
     * 对话 AI 的请求在构造前调用，确保它看不到选项。
     */
    public static String stripTags(String text) {
        if (text == null) {
            return null;
        }
        int idx = text.indexOf("<chat-select");
        if (idx < 0) {
            return text;
        }
        String prefix = text.substring(0, idx);
        // 清理截断处可能残留的空行/空格
        return prefix.replaceFirst("\\s+$", "");
    }

    /**
     * 就地清理一批消息：把每条消息文本块里的 chat-select 标签剥掉。
     * 返回原集合（引用不变），便于作为 sessionMessageProvider 直接返回。
     */
    public static List<ChatMessage> stripTagsFromMessages(List<ChatMessage> messages) {
        if (CollectionUtils.isEmpty(messages)) {
            return messages;
        }
        for (ChatMessage message : messages) {
            if (message.getContents() == null) {
                continue;
            }
            for (MessageContent content : message.getContents()) {
                if (content instanceof TextContent textContent) {
                    String clean = stripTags(textContent.getContent());
                    if (!java.util.Objects.equals(clean, textContent.getContent())) {
                        textContent.setContent(clean);
                    }
                }
            }
        }
        return messages;
    }

    /**
     * 将标准标记追加到 assistant 消息正文末尾。
     */
    public static void appendChatSelect(ChatMessage chatMessage, String markup) {
        List<MessageContent> contents = chatMessage.getContents();
        if (contents == null) {
            contents = new ArrayList<>();
            chatMessage.setContents(contents);
        }
        TextContent lastText = null;
        for (MessageContent content : contents) {
            if (content instanceof TextContent textContent) {
                lastText = textContent;
            }
        }
        if (lastText == null) {
            contents.add(new TextContent(markup));
        } else {
            lastText.setContent(lastText.getContent() + "\n\n" + markup);
        }
    }

    // ==================== 生成快捷选项 ====================

    /**
     * 为指定会话最新一条 assistant 回复生成快捷选项。
     *
     * @param sessionId      会话 ID
     * @param currentAssistant 刚生成、尚未落库的 assistant 消息（作为最新一轮的结尾）
     * @param format          角色配置的格式/风格指导，可为空（空则用内置默认）
     * @return 标准化的 <chat-select> 标记；不可用/失败返回 null（调用方静默跳过）
     */
    public String generateOptions(String sessionId, ChatMessage currentAssistant, String format) {
        try {
            List<ChatMessage> history;
            try {
                history = chatMessageService.getConversationHistory(sessionId);
            } catch (Exception e) {
                log.warn("加载会话 [{}] 历史失败，跳过选项生成: {}", sessionId, e.getMessage());
                return null;
            }
            if (history == null) {
                history = List.of();
            }

            // 1. 把历史 + 最新 assistant 序列化成“QA 整轮”（user 开头、含随后的 assistant 文本）
            List<String> turns = buildQaTurns(history, currentAssistant);
            if (turns.isEmpty()) {
                return null;
            }

            // 2. 满 7 丢 5 保 2 的滑动窗口，仅保留最新一段连续窗口
            List<String> kept = sliceTurns(turns);
            String transcript = String.join("\n", kept);
            transcript = trimToCap(transcript);

            // 3. 组装 system + user，调用 mission 模型（阻塞、非流式）
            String systemPrompt = buildSystemPrompt(format);
            String userPrompt = """
                    请阅读下面的对话，为“最后一条 角色(AI) 回复之后”的玩家生成一组快捷选项。
                    只输出 <chat-select> 标记，不要任何解释。

                    对话：
                    %s
                    """.formatted(transcript);

            ChatRequest request = new ChatRequest()
                    .quickBuild(systemPrompt, userPrompt, missionAISettings);

            AtomicReference<String> result = new AtomicReference<>();
            ChatHttpHandler.CompleteCallback onComplete = (res, lastRes) ->
                    result.set(res.content() == null ? null : res.content().trim());
            chatHttpHandler.translate(missionAISettings.getAdapterName(), request,
                    missionAISettings.getStream(), null, onComplete);

            String raw = result.get();
            if (!StringUtils.hasText(raw)) {
                log.debug("mission 未返回选项内容 [sessionId={}]", sessionId);
                return null;
            }

            // 4. 解析并清洗成标准标记
            String markup = normalizeMarkup(raw);
            if (!StringUtils.hasText(markup)) {
                log.warn("mission 返回内容无法解析为有效 chat-select，已跳过 [sessionId={}]，原文片段: {}",
                        sessionId, preview(raw));
                return null;
            }
            return markup;
        } catch (Exception e) {
            log.warn("生成快捷选项失败 [sessionId={}]: {}", sessionId, e.getMessage());
            return null;
        }
    }

    // ==================== QA 切片与滑动窗口 ====================

    /**
     * 把消息列表序列化为 QA 整轮文本。
     * 一条 QA = 以一个 user 消息开头、直至下一条 user 消息之前的完整轮次。
     * 只保留 user / assistant 的纯文本；assistant 正文中已有的 <chat-select> 原文原样保留（供 mission 参考风格）。
     * 最新的 assistant（尚未落库）补到最后一个 QA 末尾。
     */
    private List<String> buildQaTurns(List<ChatMessage> history, ChatMessage currentAssistant) {
        List<String> turns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (ChatMessage message : history) {
            String role = message.getRole();
            if (ChatMessage.ROLE_USER.equals(role)) {
                // 新的一轮：上一轮结束
                if (!current.isEmpty()) {
                    turns.add(current.toString().stripTrailing());
                    current = new StringBuilder();
                }
                String text = singleLine(message.resolveText());
                if (StringUtils.hasText(text)) {
                    current.append("玩家：").append(text).append("\n");
                }
            } else if (ChatMessage.ROLE_ASSISTANT.equals(role)) {
                String text = singleLine(message.resolveText());
                if (StringUtils.hasText(text)) {
                    current.append("角色：").append(text).append("\n");
                }
            }
            // tool / system 消息不进 QA 上下文
        }

        // 补最新一条 assistant（无论 current 是否为空都作为最后一轮结尾）
        String currentText = singleLine(currentAssistant == null ? "" : currentAssistant.resolveText());
        if (!current.isEmpty() || StringUtils.hasText(currentText)) {
            if (StringUtils.hasText(currentText)) {
                current.append("角色：").append(currentText).append("\n");
            }
            turns.add(current.toString().stripTrailing());
        }
        return turns;
    }

    /**
     * 满 7 丢 5 保 2 的滑动窗口。
     * 从最老开始逐个累计，每满 7 条就丢开头 5 条、保留最新 2 条继续累计；
     * 结束时窗口永远是最新的一段连续内容（长度 2~6）。
     */
    private List<String> sliceTurns(List<String> turns) {
        List<String> window = new ArrayList<>();
        for (String turn : turns) {
            window.add(turn);
            if (window.size() == WINDOW_MAX) {
                window = new ArrayList<>(window.subList(WINDOW_MAX - WINDOW_DROP, WINDOW_MAX));
            }
        }
        return window;
    }

    private String trimToCap(String text) {
        if (text.length() <= MAX_CONTEXT_CHARS) {
            return text;
        }
        return text.substring(text.length() - MAX_CONTEXT_CHARS);
    }

    // ==================== 提示词组装 ====================

    private String buildSystemPrompt(String format) {
        StringBuilder sb = new StringBuilder("""
                你是一个“快捷选项（quick reply）生成器”。你会看到一段玩家与角色(AI)的对话记录，
                请为“这段对话最后一条 角色(AI) 回复之后”的玩家，生成他接下来最可能点击发送的几个快捷选项。

                硬性输出要求：
                - 只输出一个 <chat-select> 标记块，不要输出任何解释、编号列表、Markdown 代码块或多余文字。
                - 结构如下（title 可选，没有合适的标题可以省略 title 属性）：
                  <chat-select title="标题">
                    <chat-option content="选项一">
                    </chat-option><chat-option content="选项二">
                    </chat-option><chat-option content="选项三">
                  </chat-option></chat-select>
                - 默认生成 3 个选项；没有好的“可说的话 / 可做的动作”时可以只生成 2 个，不要硬凑。
                - 选项站在玩家视角，是他此时最可能输入的话或想做的动作，口语化、贴合剧情与角色性格，每条不超过 40 字。
                - content 属性内不要使用双引号 "、尖括号 < > 或 & 等特殊字符；需要引号时使用中文引号‘’“”。
                """);
        sb.append("\n\n");
        if (StringUtils.hasText(format)) {
            sb.append("风格指导（来自角色配置，优先级高于默认，请严格遵守）：\n").append(format);
        } else {
            sb.append("风格指导（默认）：选项应是玩家自己会说的话/会做的事，第一人称，简短自然，按最可能到次可能的顺序排列。");
        }
        return sb.toString();
    }

    // ==================== 标记解析与清洗 ====================

    /**
     * 从 mission 输出中提取第一个 <chat-select> 块并标准化。
     * 即便模型在里面夹带了列表文字、代码块等，也只信任该块内的 chat-option。
     */
    private String normalizeMarkup(String raw) {
        Matcher blockMatcher = SELECT_BLOCK.matcher(raw);
        if (!blockMatcher.find()) {
            return null;
        }
        String block = blockMatcher.group();
        String title = readAttr(block, "title");

        List<String> options = new ArrayList<>();
        Matcher optionMatcher = OPTION_TAG.matcher(block);
        while (optionMatcher.find()) {
            String attrs = optionMatcher.group(1) != null ? optionMatcher.group(1) : optionMatcher.group(2);
            String body = optionMatcher.group(3);
            String content = readAttr(attrs == null ? "" : attrs, "content");
            if (!StringUtils.hasText(content) && StringUtils.hasText(body)) {
                content = body;
            }
            if (!StringUtils.hasText(content)) {
                continue;
            }
            content = decodeEntities(content).trim().replaceAll("\\s+", " ");
            if (content.isEmpty() || content.length() > MAX_OPTION_CHARS) {
                continue;
            }
            options.add(content);
            if (options.size() >= MAX_OPTION_COUNT) {
                break;
            }
        }
        if (options.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder("<chat-select");
        if (StringUtils.hasText(title)) {
            sb.append(" title=\"").append(encodeAttr(title.trim())).append("\"");
        }
        sb.append(">");
        for (String option : options) {
            sb.append("<chat-option content=\"").append(encodeAttr(option)).append("\"></chat-option>");
        }
        sb.append("</chat-select>");
        return sb.toString();
    }

    /** 读取标签属性（双引号包裹），返回解码后的值 */
    private String readAttr(String tag, String name) {
        Matcher matcher = Pattern.compile(name + "\\s*=\\s*\"([^\"]*)\"").matcher(tag);
        if (matcher.find()) {
            return decodeEntities(matcher.group(1));
        }
        return "";
    }

    private String encodeAttr(String text) {
        return text.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String decodeEntities(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
    }

    /** 压缩为单行（供上下文紧凑展示），保留原文标记不受影响 */
    private String singleLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s*\n\\s*", "\n").strip();
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        String t = text.replace("\n", " ").strip();
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }
}
