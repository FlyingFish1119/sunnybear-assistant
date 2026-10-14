package com.fishsunny.assistant.config;

import com.fishsunny.assistant.App;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 应用软重启：在当前 JVM 里关掉容器，再按启动目录那份 application.yml 重新拉一次。
 *
 * <p>这样与容器生命周期绑死的配置（端口、数据源、profiles、插件开关）也能生效，
 * 代价是运行中的对话与 WebSocket 会断开。重启动作跑在独立线程里并先等一小会儿，
 * 让触发的那个 HTTP 响应能正常写回去，否则前端只会看到连接被掐断。
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/10/14
 */
@Component
public class ApplicationRestartService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationRestartService.class);

    /** 留给触发重启的那个 HTTP 响应写出的时间 */
    private static final long RESPONSE_FLUSH_DELAY_MS = 800L;

    /** 等容器把端口、数据源等资源彻底放开 */
    private static final long SHUTDOWN_SETTLE_DELAY_MS = 1000L;

    private final ConfigurableApplicationContext context;

    private final String[] args;

    /** 同一时刻只允许一次重启 */
    private final AtomicBoolean restarting = new AtomicBoolean(false);

    public ApplicationRestartService(ConfigurableApplicationContext context, ApplicationArguments applicationArguments) {
        this.context = context;
        this.args = applicationArguments.getSourceArgs();
    }

    /**
     * 提交一次软重启，立刻返回。
     *
     * @return true 表示已受理；false 表示已有重启在进行中
     */
    public boolean restartAsync() {
        if (!restarting.compareAndSet(false, true)) {
            return false;
        }
        Thread thread = new Thread(this::restart, "application-restart");
        // 非守护线程：重启期间别让 JVM 有机会退出去
        thread.setDaemon(false);
        thread.start();
        return true;
    }

    private void restart() {
        Path configFile = Paths.get(System.getProperty("user.dir"), "application.yml");
        try {
            Thread.sleep(RESPONSE_FLUSH_DELAY_MS);
            log.info("正在关闭当前容器，准备按 {} 重新启动", configFile.toAbsolutePath());
            context.close();
            Thread.sleep(SHUTDOWN_SETTLE_DELAY_MS);
            new SpringApplicationBuilder(App.class).run(args);
            log.info("已按 {} 重新启动完成", configFile.toAbsolutePath());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("重启被中断，服务当前不可用");
        } catch (Throwable e) {
            log.error("按 {} 重启失败：{}。服务当前不可用，请修正该配置后手动启动。",
                    configFile.toAbsolutePath(), e.getMessage(), e);
        }
    }
}
