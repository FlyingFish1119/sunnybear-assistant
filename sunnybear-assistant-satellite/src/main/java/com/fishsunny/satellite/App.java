package com.fishsunny.satellite;

import com.fishsunny.satellite.dispatch.CommandDispatcher;
import com.fishsunny.satellite.engine.DownloadEngine;
import com.fishsunny.satellite.engine.ExecEngine;
import com.fishsunny.satellite.network.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;

/**
 * 卫星代理入口：通用远程命令执行通道。
 * <p>
 * 用法：
 * <pre>
 * java -jar satellite-agent.jar \
 *   --server ws://192.168.1.100:11451/satellite-bridge \
 *   --name "我的4090机器"
 * </pre>
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        // 1. 加载配置文件（同级目录 agent-config.json）
        AgentConfig config = AgentConfig.load();

        // 2. 命令行参数覆盖配置文件
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--server" -> config.setServer(args[++i]);
                case "--name" -> config.setName(args[++i]);
                case "--username" -> config.setUsername(args[++i]);
                case "--password" -> config.setPassword(args[++i]);
                case "--config" -> config = AgentConfig.load(args[++i]);
                case "--help", "-h" -> { printUsage(); return; }
            }
        }

        String serverUrl = config.getServer();
        String deviceName = config.getName();
        String username = config.getUsername();
        String password = config.getPassword();

        if (serverUrl == null || serverUrl.isEmpty()) {
            log.error("缺少 --server 参数");
            printUsage();
            System.exit(1);
        }

        // 自动生成 deviceId（hostname）
        String deviceId;
        try {
            deviceId = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            deviceId = "satellite-" + System.currentTimeMillis() % 100000;
        }

        if (deviceName == null || deviceName.isEmpty()) {
            deviceName = deviceId;
        }

        log.info("============================================");
        log.info("  Satellite Agent v1.0");
        log.info("  Device  : {} ({})", deviceId, deviceName);
        log.info("  Server  : {}", serverUrl);
        log.info("============================================");

        // 构建组件
        ExecEngine engine = new ExecEngine(deviceId);
        DownloadEngine downloadEngine = new DownloadEngine(deviceId);
        CommandDispatcher dispatcher = new CommandDispatcher(engine, downloadEngine);
        WebSocketClient wsClient = new WebSocketClient(serverUrl, deviceId, deviceName,
                username, password, dispatcher);
        engine.attach(wsClient);
        downloadEngine.attach(wsClient);

        // 注册 shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("正在关闭...");
            wsClient.disconnect();
        }));

        // 连接并保持运行
        wsClient.connect();

        // 主线程保持运行
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(60000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        wsClient.disconnect();
        log.info("Satellite Agent 已停止");
    }

    private static void printUsage() {
        System.out.println("""
                用法: java -jar satellite-agent.jar [选项]

                启动时自动读取同级目录下的 agent-config.json 配置文件。
                CLI 参数优先级高于配置文件。旧版配置文件里的多余字段会被忽略。

                选项:
                  --config <path>        指定配置文件路径，默认 ./agent-config.json
                  --server <url>         SunnyBear Server 的 WebSocket 地址
                                         例如 ws://192.168.1.100:11451/satellite-bridge
                  --name <name>           设备名称（显示在服务端），默认使用 hostname
                  --username <user>      Basic Auth 用户名（可选）
                  --password <pass>      Basic Auth 密码（可选）
                  --help, -h             显示此帮助
                """);
    }
}
