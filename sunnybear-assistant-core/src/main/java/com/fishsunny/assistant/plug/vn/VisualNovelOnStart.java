package com.fishsunny.assistant.plug.vn;

/*
 * @Usage 视觉小说插件启动初始化 —— 登记视觉小说剧场页面
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/25
 */

import com.fishsunny.assistant.mvc.controller.GlobalRouteController;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
public class VisualNovelOnStart implements InitializingBean {

    @Override
    public void afterPropertiesSet() {
        registerPages();
    }

    /** 登记视觉小说插件的前端页面到全局导航 */
    private void registerPages() {
        GlobalRouteController.register(
                "视觉小说", "plug/vn/vn_index.html", "视觉小说", "浏览与游玩 AI 驱动的无限续写视觉小说", "book-open");
    }
}
