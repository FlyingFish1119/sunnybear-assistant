/**
 * 会话 token 用量展示 —— 主页侧边栏与角色页侧边栏共用同一套口径。
 *
 * 数据来源：会话 extension 里 chat_ 前缀的累计值，由后端 ChatProcessor
 * 在每轮落库后合并写入（ChatUsage.SESSION_TOTAL_TOKENS = 'chat_total_tokens'）。
 * 聊天与角色走的是同一条处理器链路，所以角色会话同样有这个字段。
 *
 * ⚠ components/chat-sidebar/chat-sidebar.js 里还留着逻辑等价的两个内部方法
 *   （sessionTokenTotal / formatTokens），后续迁移到本文件即可 —— 两边不要各改一份。
 */
const TokenFormat = {
    /**
     * 取会话累计 token。
     * @param {object} session 会话对象
     * @returns {number|null} 无该字段时返回 null（调用方据此决定要不要渲染徽章）
     */
    total: function (session) {
        var extension = session && session.extension;
        if (!extension || typeof extension !== 'object') return null;
        var total = extension.chat_total_tokens;
        return total == null ? null : total;
    },

    /**
     * token 数字压缩：1234 → 1.2k，1048576 → 1.0M
     * @param {number} n
     * @returns {string}
     */
    short: function (n) {
        if (n == null || isNaN(n)) return '-';
        n = Number(n);
        if (n < 1000) return String(n);
        if (n < 1000000) return (n / 1000).toFixed(n < 10000 ? 1 : 0) + 'k';
        return (n / 1000000).toFixed(1) + 'M';
    }
};
