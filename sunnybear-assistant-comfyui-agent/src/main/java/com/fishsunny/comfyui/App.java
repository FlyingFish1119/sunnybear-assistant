package com.fishsunny.comfyui;

import com.fishsunny.comfyui.comfyui.ComfyUIHttpClient;
import com.fishsunny.comfyui.dispatch.CommandDispatcher;
import com.fishsunny.comfyui.network .WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;

/**
 * ComfyUI Agent 入口。
 * <p>
 * 用法：
 * <pre>
 * java -jar comfyui-agent.jar \
 *   --server ws://192.168.1.100:11451/comfyui-bridge \
 *   --comfyui http://127.0.0.1:8188 \
 *   --name "我的4090机器"
 * </pre>
 */
public class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        // 解析命令行参数
        String serverUrl = "http://127.0.0.1:11451/comfyui-bridge";
        String comfyuiUrl = "http://127.0.0.1:8188";
        String deviceName = null;
        String username = null;
        String password = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--server" -> serverUrl = args[++i];
                case "--comfyui" -> comfyuiUrl = args[++i];
                case "--name" -> deviceName = args[++i];
                case "--username" -> username = args[++i];
                case "--password" -> password = args[++i];
                case "--help", "-h" -> { printUsage(); return; }
            }
        }

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
            deviceId = "comfyui-" + System.currentTimeMillis() % 100000;
        }

        if (deviceName == null || deviceName.isEmpty()) {
            deviceName = deviceId;
        }

        log.info("============================================");
        log.info("  ComfyUI Agent v1.0");
        log.info("  Device  : {} ({})", deviceId, deviceName);
        log.info("  Server  : {}", serverUrl);
        log.info("  ComfyUI  : {}", comfyuiUrl);
        log.info("============================================");

        // 构建组件
        ComfyUIHttpClient comfyUI = new ComfyUIHttpClient(comfyuiUrl);
        CommandDispatcher dispatcher = new CommandDispatcher(comfyUI);
        WebSocketClient wsClient = new WebSocketClient(serverUrl, deviceId, deviceName,
                username, password, dispatcher);

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
        log.info("ComfyUI Agent 已停止");
    }

    private static void printUsage() {
        System.out.println("""
                用法: java -jar comfyui-agent.jar [选项]

                选项:
                  --server <url>         SunnyBear Server 的 WebSocket 地址（必填）
                                        例如 ws://192.168.1.100:11451/comfyui-bridge
                  --comfyui <url>        ComfyUI API 地址，默认 http://127.0.0.1:8188

                  --name <name>          设备名称（显示在服务端），默认使用 hostname
                  --username <user>      Basic Auth 用户名（可选）
                  --password <pass>      Basic Auth 密码（可选）
                  --help, -h             显示此帮助
                """);
    }
}
