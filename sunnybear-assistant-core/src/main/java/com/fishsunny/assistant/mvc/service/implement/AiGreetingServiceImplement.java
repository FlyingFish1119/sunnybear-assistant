package com.fishsunny.assistant.mvc.service.implement;

/*
 * @Usage AI 问候语业务层实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

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

    /** 每个时间段生成的问候语数量 */
    private static final int GREETINGS_PER_PERIOD = 3;

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
                ? "\n\n额外要求：可以在问候语中自然融入下面的核心记忆（不要生硬堆砌，没有合适的就不提）：\n" + memorySection
                : "";

        // mission AI 作为专业的问候语生成器
        String generatorSystemPrompt = """
                你是一个专业的问候语生成器。你的任务是接收一个角色设定，然后完全模仿该角色的语气、口吻和说话风格，生成一句简短、好玩、有趣的问候语。

                要求：
                - 问候语不超过50个字
                - 要体现出对指定时间段的感知
                - 语气要欢快、有活力、带点俏皮，让人一看到就心情变好
                - 只输出问候语本身，不要添加任何解释、引号或多余的标点""";

        // 并行生成所有时间段的问候语（每个时段按序生成 3 条，并发控制在时段数以内）
        List<CompletableFuture<List<AiGreeting>>> futures = TIME_PERIODS.stream()
                .map(period -> CompletableFuture.supplyAsync(() -> {
                    String timeOfDay = period.getKey();
                    String timeDesc = period.getValue();

                    String userPrompt = String.format("""
                            请按照下面的角色设定，模仿其语气和口吻，为指定时间段生成一句问候语。

                            当前日期：%s
                            目标时间段：%s（%s）

                            角色设定：
                            %s
                            %s""",
                            currentDate, timeOfDay, timeDesc, chatAISettings.getPrompt(), memoryHint);

                    List<AiGreeting> periodGreetings = new ArrayList<>();
                    for (int i = 0; i < GREETINGS_PER_PERIOD; i++) {
                        ChatRequest request = new ChatRequest()
                                .loadSettings(missionAISettings)
                                .setMessages(List.of(
                                        new ChatMessage().system(generatorSystemPrompt),
                                        new ChatMessage().user(userPrompt)
                                ));
                        AtomicReference<String> generatedText = new AtomicReference<>();
                        try {
                            ChatHttpHandler.CompleteCallback onComplete = (result, lastRes) -> {
                                generatedText.set(result.content() != null ? result.content().trim() : null);
                            };
                            chatHttpHandler.translate(UUID.randomUUID().toString(), missionAISettings.getAdapterName(), request,
                                    missionAISettings.getStream(),
                                    null, onComplete);

                            if (!StringUtils.hasText(generatedText.get())) {
                                log.warn("AI 未能为[{}]生成有效的问候语（第{}条），跳过", timeOfDay, i + 1);
                                continue;
                            }

                            AiGreeting greeting = new AiGreeting()
                                    .setId(UUID.randomUUID().toString())
                                    .setText(generatedText.get())
                                    .setGreetingTime(timeOfDay)
                                    .setCreateTime(LocalDateTime.now());

                            AiGreeting saved = aiGreetingRepository.insert(greeting);
                            periodGreetings.add(saved);
                            log.info("已生成[{}]问候语(第{}条): {}", timeOfDay, i + 1, generatedText.get());
                        } catch (Exception e) {
                            log.error("生成[{}]问候语失败(第{}条): {}", timeOfDay, i + 1, e.getMessage(), e);
                        }
                    }
                    return periodGreetings;
                }, taskExecutor))
                .toList();

        // 等待所有任务完成，收集结果
        List<AiGreeting> greetings = futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .toList();

        if (greetings.isEmpty()) {
            throw new Exception("所有时间段的问候语生成均失败");
        }
        return greetings;
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
