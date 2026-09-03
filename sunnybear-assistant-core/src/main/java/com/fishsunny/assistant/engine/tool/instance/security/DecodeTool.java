package com.fishsunny.assistant.engine.tool.instance.security;

/*
 * @Usage 解码工具 - 把疑似被编码/混淆的内容还原，让"被编码的危险行为"暴露出来供安全审查判断
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/3
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.SecurityToolKit;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解码工具
 * 输入一段可能被 base64 / hex / unicode 转义 / URL 编码过的文本，自动或按指定类型解码还原。
 * 主要用于安全审查子 Agent 取证：把编码过的恶意命令/脚本还原后再做危险性判断。
 * 纯解码、无任何写副作用。长输入做大小保护，避免内存与回显膨胀。
 */
@ToolKitComponent(SecurityToolKit.class)
@ConditionalOnExpression("${engine.tool.security.enable:true} && ${engine.tool.security.decode.enable:true}")
public class DecodeTool implements ToolHandler {

    public static final String NAME = "decode_tool";

    /** 输入最大长度保护 */
    private static final int MAX_INPUT_LENGTH = 200_000;

    /** 解码结果最大回显长度（超出截断并提示） */
    private static final int MAX_OUTPUT_LENGTH = 50_000;

    /** 反斜杠字符（运行时拼串，规避 Java 源码 unicode 转义对字面反斜杠的预处理） */
    private static final String BS = String.valueOf((char) 92);
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("(?i)" + Pattern.quote(BS + "u") + "([0-9a-fA-F]{4})");
    private static final Pattern UNICODE_BRACED = Pattern.compile("(?i)" + Pattern.quote(BS + "u" + "{") + "([0-9a-fA-F]{1,6})}");

    private final ToolRegister register;
    private final ObjectMapper objectMapper;

    public DecodeTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("解码工具：把疑似被编码/混淆的内容还原成可读文本，用于识破编码掩盖的真实命令或脚本。支持 base64、hex(16进制)、unicode 转义(反斜杠u开头)、URL 编码，默认自动探测。只解码不执行、无副作用。")
                .setRequired(List.of("data"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("data", "string", "待解码的文本内容"),
                        new ToolRegister.Parameters("type", "string",
                                "解码类型，可选：auto(自动探测,默认)、base64、hex、unicode、url")
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
        if (arguments.getData() == null || !StringUtils.hasText(arguments.getData())) {
            throw new ToolExecutor.ToolExecuteException("参数 data 不能为空");
        }
        String data = arguments.getData();
        if (data.length() > MAX_INPUT_LENGTH) {
            throw new ToolExecutor.ToolExecuteException("data 过长（" + data.length() + " 字符，上限 " + MAX_INPUT_LENGTH + "），请分段解码");
        }

        String type = arguments.getType();
        if (!StringUtils.hasText(type)) {
            type = "auto";
        }

        DecodeResult result;
        switch (type.trim().toLowerCase()) {
            case "base64" -> result = decodeBase64(data);
            case "hex" -> result = decodeHex(data);
            case "unicode" -> result = decodeUnicode(data);
            case "url" -> result = decodeUrl(data);
            case "auto" -> result = decodeAuto(data);
            default -> throw new ToolExecutor.ToolExecuteException(
                    "参数 type 值非法[" + type + "]，可选：auto、base64、hex、unicode、url");
        }

        if (!result.recognized()) {
            String tried = result.triedType();
            String head = ("auto".equals(tried))
                    ? "未识别出 base64 / hex / unicode / url 中任何一种有效编码，输入可能本身就是明文或使用了其他编码。"
                    : "无法识别为 " + tried + " 编码，输入可能并非该编码或已损坏。";
            return new ToolExecutor.ToolExecuteResponse(name(), head + " 原文如下：\n\n" + result.output());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("解码成功，编码类型：").append(result.triedType()).append("\n\n");
        sb.append("解码结果（共 ").append(result.output().length()).append(" 字符）：\n");
        sb.append("```\n").append(result.output()).append("\n```");
        return new ToolExecutor.ToolExecuteResponse(name(), sb.toString());
    }

    // ======================== 解码实现 ========================

    private record DecodeResult(String triedType, String output, boolean recognized) {
    }

    /** auto：按 base64 → hex → unicode → url 顺序探测，取第一个还原成功且可读的结果 */
    private DecodeResult decodeAuto(String data) {
        DecodeResult[] candidates = {decodeBase64(data), decodeHex(data), decodeUnicode(data), decodeUrl(data)};
        for (DecodeResult candidate : candidates) {
            if (candidate.recognized()) {
                return candidate;
            }
        }
        return new DecodeResult("auto", data, false);
    }

    private DecodeResult decodeBase64(String data) {
        String compact = data.replaceAll("\\s+", "");
        if (compact.isEmpty() || compact.length() % 4 != 0 || !compact.matches("[A-Za-z0-9+/]*={0,2}")) {
            return new DecodeResult("base64", data, false);
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(compact);
            String text = bytesToText(bytes);
            if (text == null || text.equals(data) || !looksDecoded(bytes)) {
                return new DecodeResult("base64", data, false);
            }
            return new DecodeResult("base64", truncate(text), true);
        } catch (IllegalArgumentException e) {
            return new DecodeResult("base64", data, false);
        }
    }

    private DecodeResult decodeHex(String data) {
        // 允许 空格/逗号 分隔或连续 16 进制，也可能带 0x 前缀
        String compact = data.replaceAll("(?i)0x", "").replaceAll("[,\\s]", "");
        if (compact.isEmpty() || compact.length() % 2 != 0 || !compact.matches("[0-9a-fA-F]+")) {
            return new DecodeResult("hex", data, false);
        }
        byte[] bytes = new byte[compact.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(compact.substring(i * 2, i * 2 + 2), 16);
        }
        String text = bytesToText(bytes);
        if (text == null || text.equals(data) || !looksDecoded(bytes)) {
            return new DecodeResult("hex", data, false);
        }
        return new DecodeResult("hex", truncate(text), true);
    }

    private DecodeResult decodeUnicode(String data) {
        // 覆盖 反斜杠+u 四十六进制数 与 反斜杠+u 大括号形态（单反斜杠形态）
        String out = data;
        Matcher m1 = UNICODE_BRACED.matcher(out);
        StringBuffer sb = new StringBuffer();
        while (m1.find()) {
            int cp = Integer.parseInt(m1.group(1), 16);
            m1.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m1.appendTail(sb);
        out = sb.toString();

        Matcher m2 = UNICODE_ESCAPE.matcher(out);
        StringBuffer sb2 = new StringBuffer();
        while (m2.find()) {
            char c = (char) Integer.parseInt(m2.group(1), 16);
            m2.appendReplacement(sb2, Matcher.quoteReplacement(String.valueOf(c)));
        }
        m2.appendTail(sb2);
        String decoded = sb2.toString();

        if (decoded.equals(data)) {
            return new DecodeResult("unicode", data, false);
        }
        return new DecodeResult("unicode", truncate(decoded), true);
    }

    private DecodeResult decodeUrl(String data) {
        try {
            String decoded = URLDecoder.decode(data, StandardCharsets.UTF_8);
            if (decoded.equals(data)) {
                return new DecodeResult("url", data, false);
            }
            return new DecodeResult("url", truncate(decoded), true);
        } catch (Exception e) {
            return new DecodeResult("url", data, false);
        }
    }

    // ======================== 辅助 ========================

    /** 字节转 UTF-8 文本；不是合法文本（乱码/二进制）返回 null */
    private String bytesToText(byte[] bytes) {
        if (bytes.length == 0) {
            return "";
        }
        String s = new String(bytes, StandardCharsets.UTF_8);
        // 合法 UTF-8 才能无损往返；含替换符说明不是文本编码
        if (!Arrays.equals(bytes, s.getBytes(StandardCharsets.UTF_8)) || s.indexOf('�') >= 0) {
            return null;
        }
        return s;
    }

    /** 判定解码产物是否"看起来像文本"：控制字符占比很低 */
    private boolean looksDecoded(byte[] bytes) {
        String s = new String(bytes, StandardCharsets.UTF_8);
        if (s.isEmpty()) {
            return false;
        }
        int control = 0;
        for (char c : s.toCharArray()) {
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                control++;
            }
        }
        return (double) control / s.length() < 0.1;
    }

    private String truncate(String text) {
        if (text.length() <= MAX_OUTPUT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_OUTPUT_LENGTH) + "\n\n... (内容过长，已截断 " + (text.length() - MAX_OUTPUT_LENGTH) + " 字符)";
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
    private static class Arguments {
        private String data;
        private String type;
    }
}
