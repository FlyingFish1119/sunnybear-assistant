package com.fishsunny.assistant.mvc.controller;

import com.fishsunny.assistant.dto.RestResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 全局页面路由注册表。
 * <p>
 * 各模块（含插件）在启动时通过 {@link #register} 把自己的前端页面登记进来，
 * 前端 {@code router.html} 调 {@code GET /router/list} 拉取导航清单，并按 group 分组展示。
 * <p>
 * path 为相对站点根目录的路径，例如 {@code index.html}、
 * {@code plug/character/character_index.html}。
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/14 13:42
 */
@RestController
@RequestMapping("/router")
public class GlobalRouteController {

    private static final List<Router> ROUTERS = new CopyOnWriteArrayList<>();

    static {
        // 核心页面（主应用）
        register("主应用", "index.html", "主助手对话", "与助手对话的主界面", "message-circle");
        register("主应用", "settings.html", "设置页面", "调整用户、助手、模型与工具设置", "settings");
    }

    /**
     * 登记一个页面到全局导航。供各模块 / 插件在启动时调用。
     *
     * @param group       分组名（前端按此分组，建议一个模块一组）
     * @param path        相对站点根目录的页面路径
     * @param title       导航标题
     * @param description 一句话描述
     * @param icon        lucide 图标名（kebab-case）
     */
    public static void register(String group, String path, String title, String description, String icon) {
        ROUTERS.add(new Router(group, path, title, description, icon));
    }

    @GetMapping("/list")
    public RestResponse list() {
        return new RestResponse().success(ROUTERS);
    }

    public record Router(String group, String path, String title, String description, String icon) {
    }
}
