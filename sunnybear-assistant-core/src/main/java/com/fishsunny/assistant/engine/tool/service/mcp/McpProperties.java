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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "engine.tool.mcp")
public class McpProperties {

    private List<Client> clients = new ArrayList<>();

    @Data
    public static class Client {

        private String serverName = "MCP Server";

        /** 传输方式：http（Streamable HTTP，默认）| stdio（本地子进程） */
        private String transport = "http";

        /** transport=http 时的服务端地址，例如 http://127.0.0.1:8080/mcp */
        private String url;

        /** transport=stdio 时的可执行命令，例如 uvx */
        private String command;

        /** transport=stdio 时的命令行参数，例如 [blender-mcp] */
        private List<String> args = new ArrayList<>();

        /** transport=stdio 时注入子进程的额外环境变量；留空表示继承当前进程环境 */
        private Map<String, String> env = new LinkedHashMap<>();

        /** transport=stdio 时子进程的工作目录；留空表示继承当前进程 */
        private String cwd;

        /** MCP 协议超时时间（秒），需小于对应工具自身的超时配置 */
        private Integer timeoutS = 20;

        private String clientVersion = "1.0.0";

        private String clientName = "MCP Client";

        /** 是否走 stdio 子进程传输；transport 不区分大小写 */
        public boolean isStdio() {
            return "stdio".equalsIgnoreCase(transport);
        }
    }
}
