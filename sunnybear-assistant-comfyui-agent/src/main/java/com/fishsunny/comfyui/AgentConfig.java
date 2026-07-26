package com.fishsunny.comfyui;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.experimental.Accessors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Agent 配置文件加载器。
 * <p>
 * 启动时自动读取同级目录下的 {@code agent-config.json}，CLI 参数可覆盖配置文件中的值。
 * <p>
 * 优先级：CLI 参数 > 配置文件 > 默认值
 */
@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    /** SunnyBear Server WebSocket 地址 */
    private String server = "http://127.0.0.1:11451/comfyui-bridge";

    /** ComfyUI API 地址 */
    private String comfyui = "http://127.0.0.1:8188";

    /** 工作流文件目录 */
    private String workflowPath = "./workflow";

    /** 生成超时（秒），默认 1800（30 分钟） */
    private int generateTimeout = 1800;

    /** 设备名称（显示在服务端） */
    private String name;

    /** Basic Auth 用户名 */
    private String username;

    /** Basic Auth 密码 */
    private String password;

    // ==================== 加载器 ====================

    private static final String DEFAULT_CONFIG_FILE = "agent-config.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 从默认路径加载配置。文件不存在则返回默认配置。
     */
    public static AgentConfig load() {
        return load(DEFAULT_CONFIG_FILE);
    }

    /**
     * 从指定路径加载配置。
     */
    public static AgentConfig load(String configPath) {
        Path path = Paths.get(configPath);

        if (Files.exists(path)) {
            try {
                String content = Files.readString(path);
                AgentConfig config = MAPPER.readValue(content, AgentConfig.class);
                log.info("已加载配置文件: {}", path.toAbsolutePath());
                return config;
            } catch (Exception e) {
                log.error("[Config] 配置文件解析失败: {}，使用默认配置", e.getMessage());
            }
        } else {
            log.info("[Config] 未找到配置文件 {}，使用默认配置", path.toAbsolutePath());
        }

        return new AgentConfig();
    }
}
