package com.fishsunny.assistant.task;

/*
 * @Usage 定时检测程序启动目录下的 application.yml，内容变更后热加载 adapter-register 并重建工厂配方，
 *        后续请求产出的适配器实例即基于新配置（免重启生效）
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/14
 */

import com.fishsunny.assistant.engine.adapter.AIAdapterRegister;
import com.fishsunny.assistant.engine.adapter.factory.AIAdapterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

@Component
public class AdapterConfigReloadTask {

    private static final Logger log = LoggerFactory.getLogger(AdapterConfigReloadTask.class);

    /** 启动目录下待检测的外部配置文件 */
    private static final String FILE_NAME = "application.yml";

    private final AIAdapterFactory adapterFactory;
    private final ExternalAdapterConfigLoader configLoader;
    private final Path configFile;

    /** 最近一次处理（成功或失败）的文件内容 hash —— 内容未变不重复加载 */
    private volatile String processedHash;

    /** 是否已成功加载过 —— 用于文件出现/消失的状态变化日志 */
    private volatile boolean loaded = false;

    public AdapterConfigReloadTask(AIAdapterFactory adapterFactory, ExternalAdapterConfigLoader configLoader) {
        this.adapterFactory = adapterFactory;
        this.configLoader = configLoader;
        this.configFile = Paths.get(System.getProperty("user.dir"), FILE_NAME);
    }

    @Scheduled(fixedDelayString = "${assistant.adapter-reload.interval-ms:60000}")
    public void check() {
        if (!Files.exists(configFile)) {
            if (loaded) {
                log.info("外部配置 {} 已不存在，保留当前适配器配置", configFile.toAbsolutePath());
                loaded = false;
            }
            return;
        }

        String hash;
        try {
            hash = sha256(Files.readAllBytes(configFile));
        } catch (Exception e) {
            log.error("读取 {} 失败: {}", configFile.toAbsolutePath(), e.getMessage());
            return;
        }
        if (hash.equals(processedHash)) {
            return; // 内容未变化
        }

        try {
            List<AIAdapterRegister> registers = configLoader.load(new FileSystemResource(configFile.toFile()));
            if (CollectionUtils.isEmpty(registers)) {
                log.error("{} 中未找到 engine.adapter-register.register 配置，保留当前适配器配置", configFile.toAbsolutePath());
                processedHash = hash;
                return;
            }
            adapterFactory.reload(registers);
            processedHash = hash;
            loaded = true;
            log.info("已从 {} 热加载适配器配置，可用适配器: {}", configFile.toAbsolutePath(), adapterFactory.getAvailableAdapterNames());
        } catch (Exception e) {
            // 读取/绑定/加载失败：保留旧配方，记住本次内容避免每分钟重复报错，文件内容变化后自动重试
            log.error("从 {} 加载适配器配置失败: {}", configFile.toAbsolutePath(), e.getMessage());
            processedHash = hash;
        }
    }

    private String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }
}
