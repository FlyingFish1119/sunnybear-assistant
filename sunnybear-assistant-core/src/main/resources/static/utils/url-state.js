/**
 * UrlState — 把「当前页面状态」同步到地址栏查询参数，供刷新 / 前进后退 / 跨页跳转恢复。
 *
 * 统一用 history.replaceState 原地更新 URL：不新增历史记录、不触发页面重载，
 * 因此切换会话/角色时不会污染浏览器的返回栈，也不会让 SPA 状态被重置。
 *
 * 用法：
 *   UrlState.read('sessionId')        // 读取，无则返回 null
 *   UrlState.write('sessionId', id)   // 写入（值为空则删除该参数）
 *   UrlState.remove('sessionId')      // 删除
 */
const UrlState = (function () {
    function read(name) {
        try {
            return new URLSearchParams(window.location.search).get(name);
        } catch (e) {
            return null;
        }
    }

    function write(name, value) {
        if (!name) return;
        try {
            var url = new URL(window.location.href);
            if (value == null || value === '') {
                url.searchParams.delete(name);
            } else {
                url.searchParams.set(name, value);
            }
            history.replaceState(history.state, '', url.pathname + url.search + url.hash);
        } catch (e) {
            console.error('更新 URL 参数失败:', e);
        }
    }

    function remove(name) {
        write(name, null);
    }

    return { read: read, write: write, remove: remove };
})();
