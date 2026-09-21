package com.fishsunny.assistant.mvc.controller;

/*
 * @Usage Shell 终端接口 —— 前端「终端」面板用。
 *
 *        exec 立即返回 jobId（命令在后台跑，不受 HTTP 超时限制），
 *        输出由 /output 按 offset 增量拉取，/kill 随时强杀。
 *
 *        ⚠ 访问来源：本接口能执行任意命令，而服务绑的是 0.0.0.0
 *        （application.yml 没配 server.address），局域网里任何设备都能访问这个端口。
 *        因此这里对每个请求做本机来源校验，非本机一律拒绝。
 *        这不是防外人攻破本机，是防止「能访问端口」直接等价于「能在本机执行命令」。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/21
 */

import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.mvc.service.ShellService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/shell")
public class ShellController {

    private static final Logger log = LoggerFactory.getLogger(ShellController.class);

    /**
     * 允许访问本接口的来源地址（回环地址的各种写法）。
     * 服务监听 0.0.0.0，remoteAddr 就是真实来源，这里只放行本机。
     */
    private static final Set<String> LOCAL_ADDRESSES = Set.of(
            "127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost"
    );

    private final ShellService shellService;

    public ShellController(ShellService shellService) {
        this.shellService = shellService;
    }

    /** 平台信息：默认工作目录、系统名、shell 名 */
    @GetMapping("/info")
    public RestResponse info(HttpServletRequest request) {
        if (!isLocal(request)) {
            return rejected(request);
        }
        try {
            return new RestResponse().success(shellService.info());
        } catch (Exception e) {
            log.error("获取 shell 环境信息失败", e);
            return new RestResponse().error("获取环境信息失败: " + e.getMessage());
        }
    }

    /** 启动命令：立即返回 jobId */
    @PostMapping("/exec")
    public RestResponse exec(@RequestBody(required = false) ShellCommand body,
                             HttpServletRequest request) {
        if (!isLocal(request)) {
            return rejected(request);
        }
        if (body == null || !StringUtils.hasText(body.getCommand())) {
            return new RestResponse().error("命令不能为空");
        }
        try {
            ShellService.ShellJob job = shellService.start(body.getCommand(), body.getCwd());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("jobId", job.id);
            data.put("cwd", job.cwd);
            data.put("startedAt", job.startedAt);
            return new RestResponse().success(data);
        } catch (Exception e) {
            log.error("启动命令失败: command={}", body.getCommand(), e);
            return new RestResponse().error("启动命令失败: " + e.getMessage());
        }
    }

    /**
     * 拉取输出增量。
     *
     * @param offset 上次拿到的位置（首次传 0），返回体里的 offset 是下次该传的值
     */
    @GetMapping("/output")
    public RestResponse output(@RequestParam(required = false) String jobId,
                               @RequestParam(required = false, defaultValue = "0") long offset,
                               HttpServletRequest request) {
        if (!isLocal(request)) {
            return rejected(request);
        }
        if (!StringUtils.hasText(jobId)) {
            return new RestResponse().error("任务 ID 不能为空");
        }
        try {
            ShellService.ShellJob job = shellService.get(jobId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("chunk", job.readFrom(offset));
            data.put("offset", job.outputLength());
            data.put("running", job.running);
            data.put("exitCode", job.exitCode);
            data.put("truncated", job.truncated);
            data.put("startedAt", job.startedAt);
            data.put("finishedAt", job.finishedAt);
            return new RestResponse().success(data);
        } catch (Exception e) {
            log.error("读取命令输出失败: jobId={}", jobId, e);
            return new RestResponse().error("读取命令输出失败: " + e.getMessage());
        }
    }

    /** 强杀任务（含其子进程） */
    @PostMapping("/kill")
    public RestResponse kill(@RequestParam(required = false) String jobId,
                             HttpServletRequest request) {
        if (!isLocal(request)) {
            return rejected(request);
        }
        if (!StringUtils.hasText(jobId)) {
            return new RestResponse().error("任务 ID 不能为空");
        }
        try {
            boolean killed = shellService.kill(jobId);
            return new RestResponse().success(killed ? "已终止" : "任务早已结束");
        } catch (Exception e) {
            log.error("终止命令失败: jobId={}", jobId, e);
            return new RestResponse().error("终止失败: " + e.getMessage());
        }
    }

    /* ======================== 来源校验 ======================== */

    private boolean isLocal(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address != null && LOCAL_ADDRESSES.contains(address);
    }

    private RestResponse rejected(HttpServletRequest request) {
        log.warn("拒绝非本机来源的 shell 请求: remoteAddr={}, uri={}",
                request.getRemoteAddr(), request.getRequestURI());
        return new RestResponse().error(
                "shell 接口只接受本机请求（当前来源：" + request.getRemoteAddr() + "）");
    }

    /* ======================== 请求体 ======================== */

    @Data
    public static class ShellCommand {

        /** 要执行的命令（交给平台 shell：Windows 走 cmd.exe /c，Linux 走 bash -c） */
        private String command;

        /** 工作目录；为空时用项目根目录 */
        private String cwd;
    }
}
