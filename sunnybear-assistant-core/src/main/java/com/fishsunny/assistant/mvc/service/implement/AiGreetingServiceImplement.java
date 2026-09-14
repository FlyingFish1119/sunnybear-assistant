package com.fishsunny.assistant.mvc.service.implement;

/*
 * @Usage AI 问候语业务层实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.AiGreeting;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.mvc.dao.AiGreetingRepository;
import com.fishsunny.assistant.mvc.service.AiGreetingService;
import com.fishsunny.assistant.mvc.service.MemoryService;
import com.fishsunny.assistant.settings.AISettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AiGreetingServiceImplement implements AiGreetingService {

    private static final Logger log = LoggerFactory.getLogger(AiGreetingServiceImplement.class);

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AiGreetingRepository aiGreetingRepository;
    private final ChatHttpHandler chatHttpHandler;
    private final MemoryService memoryService;
    private final AISettings missionAISettings;
    private final AISettings chatAISettings;
    private final TaskExecutor taskExecutor;

    public AiGreetingServiceImplement(AiGreetingRepository aiGreetingRepository,
                                      ChatHttpHandler chatHttpHandler,
                                      MemoryService memoryService,
                                      @Qualifier(AISettings.MISSION) AISettings missionAISettings,
                                      @Qualifier(AISettings.CHAT) AISettings chatAISettings,
                                      @Qualifier("chatAsyncExecutor") TaskExecutor taskExecutor) {
        this.aiGreetingRepository = aiGreetingRepository;
        this.chatHttpHandler = chatHttpHandler;
        this.memoryService = memoryService;
        this.missionAISettings = missionAISettings;
        this.chatAISettings = chatAISettings;
        this.taskExecutor = taskExecutor;
    }

    /** 所有时间段 */
    private static final List<Map.Entry<String, String>> TIME_PERIODS = List.of(
            Map.entry("上午",   "早上6点到中午12点之前"),
            Map.entry("中午",   "中午12点到下午2点之前"),
            Map.entry("下午",   "下午2点到傍晚6点之前"),
            Map.entry("晚上",   "傍晚6点到晚上10点之前"),
            Map.entry("深夜",   "晚上10点到第二天凌晨6点之前")
    );

    /** 每条问候语附带的建议提问数量 */
    private static final int SUGGESTIONS_PER_PERIOD = 4;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public List<AiGreeting> generateGreeting() throws Exception {
        String currentDate = LocalDateTime.now().format(FORMATTER);

        // 读取核心记忆，作为生成问候语的上下文注入提示词
        String memorySection;
        try {
            memorySection = memoryService.buildMemorySection();
        } catch (Exception e) {
            log.warn("读取核心记忆失败，本次生成不带记忆: {}", e.getMessage());
            memorySection = "";
        }
        String memoryHint = StringUtils.hasText(memorySection)
                ? "\n\n额外要求：可以在问候语/建议中自然融入下面的核心记忆（不要生硬堆砌，没有合适的就不提）：\n" + memorySection
                : "";

        // mission AI 作为专业的问候语 + 建议生成器
        String generatorSystemPrompt = """
                你是一个专业的问候语与建议生成器。你的任务是接收一个角色设定，然后完全模仿该角色的语气、口吻和说话风格，
                为指定时间段生成 1 句问候语，以及 %d 条用户可能想说的「建议提问」。

                要求：
                - 问候语不超过50个字，要体现对指定时间段的感知，语气欢快、有活力、带点俏皮
                - 建议提问共 %d 条，每条不超过20个字，彼此角度不同、具体可执行
                - 只输出一个 JSON 对象，不要任何解释、代码块或多余文字，格式严格如下：
                {"greeting":"问候语","suggestions":["建议1","建议2","建议3","建议4"]}"""
                .formatted(SUGGESTIONS_PER_PERIOD, SUGGESTIONS_PER_PERIOD);

        // 并行生成所有时间段的内容（每个时段 1 句问候 + N 条建议，并发控制在时段数以内）
        List<CompletableFuture<AiGreeting>> futures = TIME_PERIODS.stream()
                .map(period -> CompletableFuture.supplyAsync(() -> {
                    String timeOfDay = period.getKey();
                    String timeDesc = period.getValue();

                    String userPrompt = String.format("""
                            请按照下面的角色设定，模仿其语气和口吻，为指定时间段生成 1 句问候语和 %d 条建议提问。

                            当前日期：%s
                            目标时间段：%s（%s）

                            角色设定：
                            %s
                            %s""",
                            SUGGESTIONS_PER_PERIOD, currentDate, timeOfDay, timeDesc, chatAISettings.getPrompt(), memoryHint);

                    try {
                        String generatedText = callGenerator(generatorSystemPrompt, userPrompt);
                        if (!StringUtils.hasText(generatedText)) {
                            log.warn("AI 未能为[{}]生成有效内容，跳过", timeOfDay);
                            return null;
                        }
                        AiGreeting greeting = parseGenerated(generatedText, timeOfDay);
                        AiGreeting saved = aiGreetingRepository.insert(greeting);
                        log.info("已生成[{}]问候语: {}（建议 {} 条）", timeOfDay, greeting.getText(),
                                greeting.getSuggestions() == null ? 0 : greeting.getSuggestions().size());
                        return saved;
                    } catch (Exception e) {
                        log.error("生成[{}]问候语失败: {}", timeOfDay, e.getMessage(), e);
                        return null;
                    }
                }, taskExecutor))
                .toList();

        // 等待所有任务完成，收集结果
        List<AiGreeting> greetings = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();

        if (greetings.isEmpty()) {
            throw new Exception("所有时间段的问候语生成均失败");
        }
        return greetings;
    }

    /** 调 mission AI 生成一条内容（同步等回调），返回纯文本 */
    private String callGenerator(String systemPrompt, String userPrompt) throws Exception {
        ChatRequest request = new ChatRequest()
                .loadSettings(missionAISettings)
                .setMessages(List.of(
                        new ChatMessage().system(systemPrompt),
                        new ChatMessage().user(userPrompt)
                ));
        AtomicReference<String> generatedText = new AtomicReference<>();
        ChatHttpHandler.CompleteCallback onComplete = (result, lastRes) -> {
            generatedText.set(result.content() != null ? result.content().trim() : null);
        };
        chatHttpHandler.translate(UUID.randomUUID().toString(), missionAISettings.getAdapterName(), request,
                missionAISettings.getStream(), null, onComplete);
        return generatedText.get();
    }

    /** 解析生成结果：优先按 JSON 解析出 greeting + suggestions，失败则整体当问候语 */
    private AiGreeting parseGenerated(String raw, String greetingTime) {
        String json = extractJson(raw);
        String text = null;
        List<String> suggestions = new ArrayList<>();
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            text = node.path("greeting").asText(null);
            JsonNode arr = node.path("suggestions");
            if (arr != null && arr.isArray()) {
                for (JsonNode item : arr) {
                    String suggestion = item.asText();
                    if (StringUtils.hasText(suggestion)) {
                        suggestions.add(suggestion.trim());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析生成结果 JSON 失败，降级为纯问候语: {}", e.getMessage());
        }
        if (!StringUtils.hasText(text)) {
            text = raw;
        }
        return new AiGreeting()
                .setId(UUID.randomUUID().toString())
                .setText(text)
                .setSuggestions(suggestions)
                .setGreetingTime(greetingTime)
                .setCreateTime(LocalDateTime.now());
    }

    /** 去掉可能的 ```json 代码围栏，并截取第一个 { 到最后一个 } 之间的 JSON */
    private String extractJson(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.trim();
        }
        int left = text.indexOf('{');
        int right = text.lastIndexOf('}');
        if (left >= 0 && right > left) {
            text = text.substring(left, right + 1);
        }
        return text;
    }

    @Override
    public AiGreeting getCurrentGreeting() throws Exception {
        String timeOfDay = getTimeOfDay();
        AiGreeting greeting = aiGreetingRepository.selectByGreetingTime(timeOfDay);
        if (greeting == null) {
            log.info("未找到[{}]时段的问候语，随机获取", timeOfDay);
            greeting = aiGreetingRepository.selectRandom();
        }
        return greeting;
    }

    /** 清晨结束/上午开始的小时（6点） */
    private static final int MORNING_START_HOUR = 6;
    /** 上午结束/中午开始的小时（12点） */
    private static final int NOON_START_HOUR = 12;
    /** 中午结束/下午开始的小时（14点） */
    private static final int AFTERNOON_START_HOUR = 14;
    /** 下午结束/晚上开始的小时（18点） */
    private static final int EVENING_START_HOUR = 18;
    /** 晚上结束/深夜开始的小时（22点） */
    private static final int NIGHT_START_HOUR = 22;

    private String getTimeOfDay() {
        int hour = LocalDateTime.now().getHour();
        if (hour >= MORNING_START_HOUR && hour < NOON_START_HOUR) return "上午";
        if (hour >= NOON_START_HOUR && hour < AFTERNOON_START_HOUR) return "中午";
        if (hour >= AFTERNOON_START_HOUR && hour < EVENING_START_HOUR) return "下午";
        if (hour >= EVENING_START_HOUR && hour < NIGHT_START_HOUR) return "晚上";
        return "深夜";
    }

    @Override
    public int deleteBefore(LocalDateTime cutoff) {
        int deleted = aiGreetingRepository.deleteBefore(cutoff);
        log.info("已删除 {} 条创建时间早于 {} 的问候语", deleted, cutoff.format(FORMATTER));
        return deleted;
    }

}
