/**
 * v-infinite-scroll 指令 — 滚动到底部时触发加载回调（无限滚动）
 *
 * 用法（指令值二选一）：
 *   <div class="xxx-list" v-infinite-scroll="loadMore"></div>
 *   <div class="xxx-list" v-infinite-scroll="{ handler: loadMore, distance: 80 }"></div>
 *
 * 配置（对象形式可选字段）：
 *   handler    Function  滚动触底时调用的加载函数（必需）
 *   distance   Number    距离底部多少 px 内判定为"触底"，默认 80
 *
 * 约定：
 *   - 只负责"触底 → 调 handler"，数据分页/追加由组件自己完成；
 *   - handler 返回 Promise 时，指令会锁住直到其 resolve，避免请求进行中重复触发；
 *   - 列表不足一屏（容器无滚动条）不会产生 scroll 事件，需组件配合
 *     （如在加载后检查容器是否可滚动，不足则继续调 loadMore）。
 *   - 卸载时自动移除监听。
 */

var InfiniteScrollDirective = {
    install: function (app) {
        app.directive('infinite-scroll', {
            mounted: function (el, binding) {
                var state = resolveState(binding);
                var ticking = false;

                function trigger() {
                    ticking = false;
                    if (el._infLocked) return;
                    if (!state.handler) return;
                    if (el.scrollHeight - el.scrollTop - el.clientHeight < state.distance) {
                        var result = state.handler();
                        // handler 返回 Promise 时，锁定直到请求结束，防止触底反复触发叠加请求
                        if (result && typeof result.then === 'function') {
                            el._infLocked = true;
                            result.then(function () { el._infLocked = false; }, function () { el._infLocked = false; });
                        }
                    }
                }

                function onScroll() {
                    if (ticking) return;
                    ticking = true;
                    requestAnimationFrame(trigger);
                }

                el.addEventListener('scroll', onScroll, { passive: true });
                el._infState = state;
                el._infCleanup = function () {
                    el.removeEventListener('scroll', onScroll);
                    delete el._infState;
                    delete el._infLocked;
                    delete el._infCleanup;
                };
            },

            updated: function (el, binding) {
                // 指令值随组件重渲染变化时，刷新 handler / distance
                if (el._infState) {
                    el._infState = resolveState(binding);
                }
            },

            unmounted: function (el) {
                if (el._infCleanup) {
                    el._infCleanup();
                }
            }
        });
    }
};

/**
 * 解析指令值：函数形式 → { handler, distance }；对象形式 → 按字段合并默认值
 */
function resolveState(binding) {
    var value = binding.value;
    if (typeof value === 'function') {
        return { handler: value, distance: 80 };
    }
    var opts = (value && typeof value === 'object') ? value : {};
    return { handler: opts.handler, distance: opts.distance != null ? opts.distance : 80 };
}
