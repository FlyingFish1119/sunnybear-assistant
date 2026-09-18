package com.fishsunny.assistant.plug.character;

/*
 * @Usage 角色插件启动初始化 —— 登记角色扮演相关前端页面
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/4
 */

import com.fishsunny.assistant.mvc.controller.GlobalRouteController;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
public class CharacterOnStart implements InitializingBean {

    @Override
    public void afterPropertiesSet() {
        registerPages();
    }

    /** 登记角色插件的前端页面到全局导航 */
    private void registerPages() {
        GlobalRouteController.register(
                "角色扮演", "plug/character/character_index.html", "角色扮演", "与角色对话、战斗与角色库", "users");
        GlobalRouteController.register(
                "角色扮演", "plug/character/character_settings.html", "角色设置", "管理角色库、术语表与战斗配置", "user-cog");
    }
}
