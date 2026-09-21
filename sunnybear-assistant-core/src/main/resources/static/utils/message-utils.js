/**
 * 消息内容块工具（聊天页与群聊页共用）。
 *
 * 内容块语义契约（与 message-area 渲染一致）：
 *   contents[0] 恒为正文（可编辑），其后的块是附件 / 追加内容，只展示不编辑。
 *
 * 为什么需要「同步服务端内容块」：
 *   后台任务完成通知这类后到的内容，后端是在消息落库前追加成一个新文本块挂到末尾的，
 *   落库后随 init_tool / init_assistant 帧下发。前端本地那条消息是流式累积出来的，
 *   只有正文首块；不同步就把服务端的追加块丢了，界面一直不动，只能靠重拉历史才看得见。
 *
 * 同步策略：服务端落库消息就是权威快照（正文来自模型这一轮的完整返回，不是增量拼接），
 *   因此整体覆盖本地 contents —— 不做增量追加、也不需要判重；本地陈旧的多余块会被一并对齐清掉。
 *
 * 依赖（全局）：无。
 */
const MessageUtils = (function () {

    /**
     * 取消息的正文块（contents[0]）并保证其存在。
     * 首块不是 text 时（历史脏数据）在首位补一个空正文块，绝不往附件块里写正文。
     *
     * @param {object} message 消息对象（就地修改）
     * @returns {object} 正文块
     */
    function ensureBodyContent(message) {
        if (!Array.isArray(message.contents)) {
            message.contents = [];
        }
        const first = message.contents[0];
        if (first && first.type === 'text') {
            return first;
        }
        const body = { type: 'text', content: '' };
        message.contents.unshift(body);
        return body;
    }

    /**
     * 用服务端消息的 contents 覆盖本地消息的内容块（服务端为准）。
     *
     * 浅拷贝块对象再做替换：本地会就地续写正文首块（流式累积），
     * 直接引用 WS 帧里的块对象会把帧对象改脏。
     *
     * @param {object} localMsg  本地消息（就地修改 contents，消息对象本身保留）
     * @param {object} serverMsg 服务端下发的那条消息（落库后的完整消息）
     */
    function applyServerContents(localMsg, serverMsg) {
        if (!localMsg || !serverMsg || !Array.isArray(serverMsg.contents)) {
            return;
        }
        localMsg.contents = serverMsg.contents.map(function (content) {
            return Object.assign({}, content);
        });
    }

    return {
        ensureBodyContent: ensureBodyContent,
        applyServerContents: applyServerContents
    };
})();
