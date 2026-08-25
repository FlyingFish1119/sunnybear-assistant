package com.fishsunny.assistant.engine.tool.service.mcp;

/*
 * @Usage MCP 配置属性（engine.tool.mcp 前缀）
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/25 11:04
 */

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "engine.tool.mcp")
public class McpProperties {

    private List<Client> clients = new ArrayList<>();

    @Data
    public static class Client {

        private String serverName = "MCP Server";

        /** Streamable HTTP MCP Server 地址，例如 <a href="http://127.0.0.1:8080">...</a> */
        private String url = "http://127.0.0.1:8080";

        private String tokenUrl = "http://127.0.0.1:8080";

        private String username = "MCP User";

        private String password = "MCP Password";

        /** MCP 协议超时时间（秒） */
        private Integer timeoutS = 20;

        private String clientVersion = "1.0.0";

        private String clientName = "MCP Client";
    }
}
