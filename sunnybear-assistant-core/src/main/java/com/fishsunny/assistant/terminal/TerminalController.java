package com.fishsunny.assistant.terminal;

/*
 * @Usage 终端 AI 辅助接口 —— 前端「终端」面板底部的 AI 输入框用。
 *
 *        描述自然语言 → 生成一条 shell 命令返回（不执行）。
 *        与终端 WebSocket 一样只接受本机来源：虽然它不直接执行命令，但会把
 *        「本机能跑什么」暴露给调用方，延续 /shell 时代的本机限定策略。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/28
 */

import com.fishsunny.assistant.dto.RestResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/terminal")
public class TerminalController {

    private static final Logger log = LoggerFactory.getLogger(TerminalController.class);

    private static final Set<String> LOCAL_ADDRESSES = Set.of(
            "127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost"
    );

    private final TerminalAssistService assistService;

    public TerminalController(TerminalAssistService assistService) {
        this.assistService = assistService;
    }

    /** 自然语言 → 单条 shell 命令 */
    @PostMapping("/assist")
    public RestResponse assist(@RequestBody(required = false) TerminalAssistRequest body,
                               HttpServletRequest request) {
        if (!isLocal(request)) {
            return rejected(request);
        }
        if (body == null || !StringUtils.hasText(body.getPrompt())) {
            return new RestResponse().error("描述不能为空");
        }
        try {
            String command = assistService.generateCommand(body.getPrompt().trim());
            if (!StringUtils.hasText(command)) {
                return new RestResponse().error("没能生成命令，换个说法再试试");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("command", command);
            return new RestResponse().success(data);
        } catch (Exception e) {
            log.error("生成命令失败: prompt={}", body.getPrompt(), e);
            return new RestResponse().error("生成命令失败: " + e.getMessage());
        }
    }

    private boolean isLocal(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address != null && LOCAL_ADDRESSES.contains(address);
    }

    private RestResponse rejected(HttpServletRequest request) {
        log.warn("拒绝非本机来源的终端辅助请求: remoteAddr={}, uri={}",
                request.getRemoteAddr(), request.getRequestURI());
        return new RestResponse().error(
                "终端接口只接受本机请求（当前来源：" + request.getRemoteAddr() + "）");
    }

    @Data
    public static class TerminalAssistRequest {
        /** 用户用自然语言描述的需求 */
        private String prompt;
    }
}
