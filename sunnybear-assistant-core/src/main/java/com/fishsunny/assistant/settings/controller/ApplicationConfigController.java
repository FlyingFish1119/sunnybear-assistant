package com.fishsunny.assistant.settings.controller;

import com.fishsunny.assistant.config.ApplicationRestartService;
import com.fishsunny.assistant.dto.RestResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用配置控制器：读写启动目录下的 application.yml。
 *
 * <p>保存前只用 Spring 启动时那套解析器（YamlPropertySourceLoader）跑一遍语法检测，
 * 拦掉纯粹的 YAML 格式错误；字段写错、类型不对就让它在下一次启动时暴露，不做字段级校验。
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/10/14
 */
@RestController
@RequestMapping("/settings")
public class ApplicationConfigController {

    private static final Logger log = LoggerFactory.getLogger(ApplicationConfigController.class);

    private static final String CONFIG_NAME = "application.yml";

    /** 配置文件体积上限，防手滑粘贴进一大坨东西 */
    private static final long MAX_CONTENT_BYTES = 1024 * 1024;

    private final ApplicationRestartService restartService;
    private final Path configFile;

    public ApplicationConfigController(ApplicationRestartService restartService) {
        this.restartService = restartService;
        this.configFile = Paths.get(System.getProperty("user.dir"), CONFIG_NAME);
    }

    /** 读取当前生效的配置内容 */
    @RequestMapping("/application-config/get")
    public RestResponse getApplicationConfig() {
        if (!Files.exists(configFile)) {
            return new RestResponse().error("启动目录下没有找到 application.yml：" + configFile.toAbsolutePath());
        }
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", configFile.toAbsolutePath().toString());
            data.put("content", Files.readString(configFile, StandardCharsets.UTF_8));
            return new RestResponse().success(data);
        } catch (IOException e) {
            log.error("读取 {} 失败: {}", configFile.toAbsolutePath(), e.getMessage());
            return new RestResponse().error("读取失败：" + e.getMessage());
        }
    }

    /** 检测语法并保存配置，随后按新配置软重启 */
    @PostMapping("/application-config/save")
    public RestResponse saveApplicationConfig(@RequestBody(required = false) Map<String, String> body) {
        String content = body == null ? null : body.get("content");
        if (content == null || content.isBlank()) {
            return new RestResponse().error("配置内容为空");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            return new RestResponse().error("配置内容过大（上限 1MB）");
        }

        String syntaxError = checkYamlSyntax(content);
        if (syntaxError != null) {
            log.warn("配置 YAML 语法检测未通过，已阻止保存：{}", syntaxError);
            RestResponse response = new RestResponse().error("YAML 语法错误，已阻止保存");
            return response.setData(List.of(syntaxError));
        }

        try {
            Files.writeString(configFile, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("写入 {} 失败: {}", configFile.toAbsolutePath(), e.getMessage());
            return new RestResponse().error("保存失败：" + e.getMessage());
        }
        log.info("已保存 {}，即将按新配置软重启", configFile.toAbsolutePath());

        if (!restartService.restartAsync()) {
            return new RestResponse().error("已有一次重启正在进行，本次未触发重启");
        }
        return new RestResponse().success("配置已保存，服务正在按新配置重启");
    }

    /**
     * 用启动时同一套解析器做一次只读解析。
     *
     * @return 解析失败的说明；null 表示语法没问题
     */
    private String checkYamlSyntax(String content) {
        try {
            new YamlPropertySourceLoader().load("candidate-application-yml",
                    new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)));
            return null;
        } catch (Exception e) {
            String message = e.getMessage();
            return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
        }
    }
}
