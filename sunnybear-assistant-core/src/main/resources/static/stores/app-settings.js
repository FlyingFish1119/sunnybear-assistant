/**
 * AppSettings — 应用级共享设置（单例 reactive store）
 *
 * 与 SessionStore 同套路：页面 createApp 后
 *   app.provide('appSettings', AppSettings);
 * 任意组件 inject 后直接读取，避免 mainColor / userSettings / assistantSettings
 * 的层层 prop 传递。通用拉取逻辑也内聚在这里，页面只调用 + 做页面级覆盖。
 *
 * 字段（子对象可整体替换，AppSettings 本身保持同一引用即可）：
 *   mainColor          — 当前生效主题色（页面按主题缓存 / 角色覆盖写入）
 *   userSettings       — 用户设置（背景、透明度、上下文阈值等，来自后端）
 *   assistantSettings  — 助手设置（头像、名称）
 *
 * 方法：
 *   fetchUserSettings()      — 拉取 /settings/user/get，写入 userSettings 并应用主题色
 *                              （含 ThemeColorCache 与 localStorage 回退）
 *   fetchAssistantSettings() — 拉取 /settings/assistant/get，写入 assistantSettings
 *
 * 组件用法：
 *   inject: { appSettings: { default: null } }
 *   computed: { mainColor() { return this.appSettings ? this.appSettings.mainColor : 'lightsalmon'; } }
 */
const AppSettings = Vue.reactive({
    mainColor: (window.ThemeColorCache && window.ThemeColorCache.get()) || 'lightsalmon',
    userSettings: { background: '', opacity: 0.3 },
    assistantSettings: { avatar: '', assistantName: '' }
});

/**
 * 拉取用户设置并落库到 store。成功后应用主题色（缓存 + localStorage 回退）。
 * 页面可在 await 之后按优先级覆盖 mainColor（如角色/世界专属主题色）。
 * @returns {Promise<void>}
 */
AppSettings.fetchUserSettings = function () {
    return API.settings.user.get().then(function (result) {
        if (result.status === 200) {
            AppSettings.userSettings = result.data || { background: '', opacity: 0.3 };
            // 从服务端获取主题色，并刷新本地缓存（下次加载先用缓存，避免闪默认色）
            if (AppSettings.userSettings.mainColor) {
                AppSettings.mainColor = AppSettings.userSettings.mainColor;
                if (window.ThemeColorCache) window.ThemeColorCache.set(AppSettings.userSettings.mainColor);
            }
        }
    }).catch(function (error) {
        console.error('获取用户设置失败:', error);
    }).then(function () {
        // 服务端没有则回退到 localStorage
        if (!AppSettings.userSettings.mainColor) {
            try {
                const saved = localStorage.getItem('assistant-mainColor');
                if (saved) AppSettings.mainColor = saved;
            } catch (e) {}
        }
    });
};

/**
 * 拉取助手设置并写入 store。
 * @returns {Promise<void>}
 */
AppSettings.fetchAssistantSettings = function () {
    return API.settings.assistant.get().then(function (result) {
        if (result.status === 200) {
            AppSettings.assistantSettings = result.data || { avatar: '', assistantName: '' };
        }
    }).catch(function (error) {
        console.error('获取助手设置失败:', error);
    });
};
