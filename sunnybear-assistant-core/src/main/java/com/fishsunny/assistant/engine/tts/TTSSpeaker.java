package com.fishsunny.assistant.engine.tts;

/*
 * @Usage TTS 同步句子合成器：纯文本进、mp3 base64 出，协议无关。
 *       断句状态随实例存在，每 AI 回合（一次 translate / 一个 adapter）一个实例。
 *       规则很简单：围栏内（``` 代码块）不朗读；正文凑到句子终止符立即合成一段返回；
 *       无终止符但攒得过长时按软断点/硬切。合成同步阻塞调用线程——文本先由引擎推出，
 *       音频随文本节奏经 onTTSAudio 交还，顺序天然正确。
 *       不依赖 Spring 与任何具体协议类。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/10
 */

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Base64;

@Slf4j
public class TTSSpeaker {

    /** 句子终止符：凑到即出句（不设最短长度等待，短句也能立刻出声） */
    private static final String SENTENCE_TERMINATORS = "。！？!?；;…\n";
    /** 无终止符时的软断点：攒超 maxChars 时优先在此切 */
    private static final String SOFT_BREAKS = "，,、：: ";
    /** 单次合成文本上限：防止长句（无标点）憋着不出声 */
    private static final int MAX_SENTENCE_CHARS = 120;

    private final TTSClient ttsClient;

    /** 待朗读正文（代码围栏已剔除） */
    private final StringBuilder pending = new StringBuilder();
    /** 正在代码围栏内 */
    private boolean inFence = false;
    /** 下一字符是否位于行首（围栏开关只在行首识别） */
    private boolean lineStart = true;
    private boolean cancelled = false;

    public TTSSpeaker(TTSClient ttsClient) {
        this.ttsClient = ttsClient;
    }

    /**
     * 喂入一段流式文本（adapter 已完成 typed 抽取，只喂该朗读的正文）。
     * 缓冲凑成句则**同步合成一批**并返回该批音频（mp3 base64）；未成句返回 null。
     * 每次调用最多合成一批（剩余留在缓冲，等下一次 feedText / flush）。
     */
    public String feedText(String text) {
        if (cancelled || !StringUtils.hasText(text)) {
            return null;
        }
        scan(text);
        return emitSentence();
    }

    /**
     * 冲刷全部剩余文本（合成成一段返回），缓冲为空返回 null。一次流结束后调用。
     */
    public String flush() {
        if (cancelled || !StringUtils.hasText(pending)) {
            return null;
        }
        String speakable = pending.toString().trim();
        pending.setLength(0);
        return synth(speakable);
    }

    /** 中断/异常：丢弃缓冲，不再合成。幂等 */
    public void cancel() {
        cancelled = true;
        pending.setLength(0);
    }

    /**
     * 从完整文本中剔除代码围栏等不可读区域（供整轮完整合成复用）。
     *
     * @return 可读正文；无可读内容返回 null
     */
    public static String filterSpeakableText(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        boolean inFence = false;
        boolean lineStart = true;
        for (int i = 0; i < raw.length(); ) {
            if (raw.startsWith("```", i)) {
                if (!inFence && lineStart) {
                    inFence = true;            // 行首 ``` 开围栏
                } else if (inFence && lineStart) {
                    inFence = false;           // 行首 ``` 关围栏
                } else if (!inFence) {
                    out.append("```");         // 行中 ``` 视为普通字符（罕见，保守朗读）
                }
                i += 3;
                lineStart = false;
                continue;
            }
            char c = raw.charAt(i);
            if (inFence) {
                if (c == '\n') {
                    lineStart = true;
                }
            } else {
                out.append(c);
                if (c == '\n') {
                    lineStart = true;
                } else if (c != ' ' && c != '\t') {
                    lineStart = false;
                }
            }
            i++;
        }
        return StringUtils.hasText(out) ? out.toString().trim() : null;
    }

    /**
     * 便捷：整段文本过滤后一次性合成，返回 mp3 base64（供整轮完整音频入库）。
     * ttsClient 为 null / 无可读内容 / 合成失败时返回 null。
     */
    public static String synthesizeSpeakable(TTSClient ttsClient, String rawText) {
        if (ttsClient == null) {
            return null;
        }
        String speakable = filterSpeakableText(rawText);
        if (speakable == null) {
            return null;
        }
        try {
            return Base64.getEncoder().encodeToString(ttsClient.synthesize(speakable));
        } catch (Exception e) {
            log.warn("TTS full synth failed: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 流式处理
    // ------------------------------------------------------------------

    /** 逐字符扫描一段文本：围栏内跳过，正文收进 pending */
    private void scan(String text) {
        for (int i = 0; i < text.length(); ) {
            if (text.startsWith("```", i)) {
                if (lineStart) {
                    inFence = !inFence;        // 行首 ``` 开/关围栏
                    i += 3;
                    lineStart = false;
                    continue;
                }
                // 行中 ```：不作围栏开关，按普通字符处理
            }
            char c = text.charAt(i);
            if (!inFence) {
                pending.append(c);
            }
            if (c == '\n') {
                lineStart = true;
            } else if (c != ' ' && c != '\t') {
                lineStart = false;
            }
            i++;
        }
    }

    /**
     * 从 pending 里取出一句（第一个终止符为止）合成返回；删掉已消费部分。
     * 无终止符但长度超限时按软断点/硬切取一段，避免长句不出声。
     *
     * @return 该句音频 mp3 base64；无内容返回 null
     */
    private String emitSentence() {
        if (!StringUtils.hasText(pending)) {
            return null;
        }
        int end = -1;
        for (int i = 0; i < pending.length(); i++) {
            if (SENTENCE_TERMINATORS.indexOf(pending.charAt(i)) >= 0) {
                end = i + 1;
                break;
            }
        }
        if (end < 0 && pending.length() >= MAX_SENTENCE_CHARS) {
            // 超长无终止符：优先在上一个软断点后切，否则硬切
            for (int i = MAX_SENTENCE_CHARS - 1; i >= 0; i--) {
                if (SOFT_BREAKS.indexOf(pending.charAt(i)) >= 0) {
                    end = i + 1;
                    break;
                }
            }
            end = end > 0 ? end : MAX_SENTENCE_CHARS;
        }
        if (end < 0) {
            return null;
        }
        String speakable = pending.substring(0, end).trim();
        pending.delete(0, end);
        return StringUtils.hasText(speakable) ? synth(speakable) : null;
    }

    private String synth(String speakable) {
        try {
            return Base64.getEncoder().encodeToString(ttsClient.synthesize(speakable));
        } catch (Exception e) {
            // 合成失败的句子跳过，不打断整体朗读
            log.warn("TTS synth failed: {}", e.getMessage());
            return null;
        }
    }
}
