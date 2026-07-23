/**
 * v-auto-follow 指令 — 自动跟随容器底部滚动，用户上滑时暂停跟随
 *
 * 用法：
 *   <div class="message-area-list" v-auto-follow></div>
 *
 * 配置（可选，通过指令值传入）：
 *   v-auto-follow                              → threshold=50, cooldown=150
 *   v-auto-follow="{threshold:80}"             → 离开底部 80px 暂停
 *   v-auto-follow="{cooldown:300}"             → 上滑后 300ms 暂停
 *
 * 效果：
 *   指令会在元素上写入 el._autoFollowPaused（boolean）。
 *   父组件在 scrollToBottom 中检查此标记即可。
 */

var AUTO_FOLLOW_DEFAULTS = {
    threshold: 50,   // 离开底部多少 px 判定为"离开"
    cooldown: 150,   // 上滑后多少 ms 内不恢复跟随
};

var AutoFollow = {
    /* ---- 指令注册入口 ---- */
    install: function (app) {
        app.directive('auto-follow', {
            mounted: function (el, binding) {
                var opts = typeof binding.value === 'object'
                    ? Object.assign({}, AUTO_FOLLOW_DEFAULTS, binding.value)
                    : AUTO_FOLLOW_DEFAULTS;

                var lastScrollTop = 0;
                var lastScrollUpTime = 0;

                function onScroll() {
                    var distanceToBottom = el.scrollHeight - el.scrollTop - el.clientHeight;

                    // 记录最近一次上滑的时间戳
                    if (el.scrollTop < lastScrollTop) {
                        lastScrollUpTime = Date.now();
                    }
                    lastScrollTop = el.scrollTop;

                    var recentScrollUp = (Date.now() - lastScrollUpTime) < opts.cooldown;
                    el._autoFollowPaused = distanceToBottom > opts.threshold || recentScrollUp;
                }

                el.addEventListener('scroll', onScroll, { passive: true });

                // 清理引用
                el._autoFollowCleanup = function () {
                    el.removeEventListener('scroll', onScroll);
                    delete el._autoFollowPaused;
                    delete el._autoFollowCleanup;
                };
            },

            unmounted: function (el) {
                if (el._autoFollowCleanup) {
                    el._autoFollowCleanup();
                }
            }
        });
    }
};
