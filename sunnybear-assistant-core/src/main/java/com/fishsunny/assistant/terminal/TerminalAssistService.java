package com.fishsunny.assistant.terminal;

/*
 * @Usage 终端 AI 辅助 —— 把自然语言需求翻译成一条 shell 命令。
 *
 *        走 CUB 轻量场景做一次性生成（与标题生成/知识简介同一套用法）：
 *        拼一个「只输出命令」的系统提示词，把用户描述作为 user 消息，同步等一次返回，
 *        再清洗掉模型可能多吐的代码围栏、提示符、解释文字。
 *
 *        只生成、不执行：是否运行由前端把命令填进命令行后由用户确认，
 *        这样即使模型给错命令也不会在本机直接跑起来。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/28
 */

import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.settings.AISettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class TerminalAssistService {

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private static final String SYSTEM_PROMPT = """
            你是命令行生成器。把用户的自然语言需求翻译成一条可直接在目标 shell 中执行的命令。

            规则：
            1. 只输出命令本身：不要解释、不要前后缀、不要 Markdown 代码围栏、不要多余空行。
            2. 只输出一行；若确实需要多条，用目标 shell 的连接符连成一行。
            3. 使用目标平台真实可用的命令与语法，注意 Windows 与 Linux/macOS 的差异。
            4. 需求不明确时，选择最常见、最安全的做法。
            5. 不要执行任何命令，只负责生成。

            目标平台：%s
            目标 shell：%s
            """;

    private final ChatHttpHandler chatHttpHandler;
    private final AISettings cubAISettings;

    public TerminalAssistService(ChatHttpHandler chatHttpHandler,
                                 @Qualifier(AISettings.CUB) AISettings cubAISettings) {
        this.chatHttpHandler = chatHttpHandler;
        this.cubAISettings = cubAISettings;
    }

    /**
     * 生成命令。
     *
     * @param prompt 用户用自然语言描述的需求
     * @return 清洗后的单行命令；失败或模型没给出有效内容时返回空串
     */
    public String generateCommand(String prompt) throws Exception {
        String systemPrompt = SYSTEM_PROMPT.formatted(osName(), shellName());
        ChatRequest chatRequest = new ChatRequest().quickBuild(systemPrompt, prompt, cubAISettings);

        AtomicReference<String> result = new AtomicReference<>();
        chatHttpHandler.translate(
                new ChatHttpHandler.TranslateData(UUID.randomUUID().toString(),
                        cubAISettings.getAdapterName(), chatRequest),
                new ChatHttpHandler.TranslateHandler(null, (resp, lastRes) -> {
                    if (resp != null) {
                        result.set(resp.content());
                    }
                }),
                new ChatHttpHandler.TranslateOption().setStream(cubAISettings.getStream()));

        return sanitize(result.get());
    }

    /** 清洗模型输出：剥掉代码围栏、提示符前缀、项目符号，只留第一行有效命令 */
    static String sanitize(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String text = raw.replace("```", " ");
        for (String line : text.split("\\R")) {
            String candidate = line.strip();
            if (!StringUtils.hasText(candidate)) {
                continue;
            }
            // 去掉常见的提示符 / 项目符号前缀：PS C:\>、$、>、#、1. 等
            candidate = candidate.replaceFirst("^(?:PS\\s.*?>|[$>#]|\\d+[.)])\\s*", "");
            // 去掉包裹的反引号
            candidate = candidate.replace("`", "").strip();
            if (StringUtils.hasText(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private static String osName() {
        return IS_WINDOWS ? "Windows" : "Linux/macOS";
    }

    private static String shellName() {
        if (IS_WINDOWS) {
            return "PowerShell";
        }
        String shell = System.getenv("SHELL");
        return (shell == null || shell.isBlank()) ? "bash" : shell;
    }
}
