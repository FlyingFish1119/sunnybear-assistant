/**
 * WsBus — WebSocket 消息总线
 *
 * 统一接管 WebSocket 的 onmessage：把服务端下发的 `###SIGNAL###payload` 帧
 * 按信号名路由给订阅者，JSON 帧（如 { status: 'chunk' } 流式消息）走 '*' 兜底。
 *
 * 设计目的：让各组件（如 tool-confirm / tool-question）自己订阅并处理自己关心的信号，
 * 不必再由页面主组件持有 ws 并手动分发。
 *
 * 用法：
 *   // 主组件连接成功后移交 socket（总线自行挂 onmessage）
 *   WsBus.setSocket(ws);
 *
 *   // 组件订阅自己关心的信号（返回取消订阅函数，便于 beforeUnmount 调用）
 *   const off = WsBus.on('TOOL_ASK', payload => this.show(payload));
 *   // 组件卸载
 *   off();
 *
 *   // 主组件兜底处理所有未被精确订阅的帧（原 handleStreamChunk 的剩余逻辑）
 *   WsBus.on('*', rawData => this.handleStreamChunk(rawData));
 *
 *   // 组件间本地事件（不走 socket，用于兄弟组件解耦通信）
 *   WsBus.on('agent-log:toggle', () => this.toggle());
 *   WsBus.emit('agent-log:toggle');
 *
 * 路由规则：
 *   - 帧格式 `###SIGNAL###payload`：先找 SIGNAL 的精确订阅者；有则只交给它们；
 *     没有则交给 '*' 订阅者。
 *   - 其它帧（JSON、':' 心跳等）：直接交给 '*' 订阅者。
 *   - 精确订阅者可接收多个订阅者，按注册顺序依次调用。
 *   - '*' 兜底订阅者：仅在没有对应 SIGNAL 精确订阅者时调用。
 *   - emit(type, payload)：仅调用该 type 的精确订阅者（不走 '*' 兜底），
 *     用于组件间本地事件，与 WS 信号共用同一订阅表。
 *
 * 注意：本对象是单例（模块级唯一实例），通过 app.provide('wsBus', WsBus) 注入后代组件。
 */
const WsBus = (function () {
    /** 当前 WebSocket 实例 */
    let socket = null;

    /** 信号名 → 订阅者数组，键为信号名（如 'TOOL_ASK'）；'*' 为兜底订阅者数组 */
    const handlers = Object.create(null);
    handlers['*'] = [];

    /** 从原始帧解析信号名；非 `###` 帧返回 null */
    function parseSignal(raw) {
        if (typeof raw !== 'string' || !raw.startsWith('###')) {
            return null;
        }
        const end = raw.indexOf('###', 3);
        if (end < 0) {
            return null;
        }
        return raw.substring(3, end);
    }

    /** 取出信号对应的负载字符串（信号名之后的部分） */
    function signalPayload(raw, signal) {
        return raw.substring(('###' + signal + '###').length);
    }

    /** 统一的分发入口 */
    function dispatch(raw) {
        const signal = parseSignal(raw);
        if (signal && handlers[signal] && handlers[signal].length > 0) {
            const payload = signalPayload(raw, signal);
            for (const fn of handlers[signal].slice()) {
                try {
                    fn(payload, raw);
                } catch (e) {
                    console.error('WsBus 订阅者处理出错 [' + signal + ']:', e);
                }
            }
            return;
        }
        // 无精确订阅者（或非 ### 帧）→ 交给 '*' 兜底
        for (const fn of handlers['*'].slice()) {
            try {
                fn(raw, signal);
            } catch (e) {
                console.error('WsBus 兜底订阅者处理出错:', e);
            }
        }
    }

    /** 当前 socket 上挂载的 onmessage 包装（用于断开/替换时精确摘除） */
    let boundOnMessage = null;

    /** 触发本地事件：仅调用该 type 的精确订阅者（不走 '*' 兜底） */
    function emitLocal(type, payload) {
        const arr = handlers[type];
        if (!arr || arr.length === 0) {
            return;
        }
        for (const fn of arr.slice()) {
            try {
                fn(payload);
            } catch (e) {
                console.error('WsBus.emit 订阅者处理出错 [' + type + ']:', e);
            }
        }
    }

    return {
        /**
         * 移交 WebSocket：总线接管 onmessage，并广播 'ws:connected'。
         * @param {WebSocket} ws
         */
        setSocket(ws) {
            if (socket && boundOnMessage && socket.onmessage === boundOnMessage) {
                socket.onmessage = null;
            }
            socket = ws;
            if (socket) {
                boundOnMessage = function (event) {
                    dispatch(event.data);
                };
                socket.onmessage = boundOnMessage;
            } else {
                boundOnMessage = null;
            }
            emitLocal('ws:connected', ws);
        },

        /** 清空当前 socket 引用（断开时调用），并广播 'ws:disconnected' */
        clearSocket() {
            if (socket && boundOnMessage && socket.onmessage === boundOnMessage) {
                socket.onmessage = null;
            }
            socket = null;
            boundOnMessage = null;
            emitLocal('ws:disconnected');
        },

        /** 当前 socket 实例（只读） */
        getSocket() {
            return socket;
        },

        /**
         * 订阅信号。
         * @param {string} type — 信号名（如 'TOOL_ASK'），或 '*' 表示兜底
         * @param {Function} fn — 回调 (payload, raw)
         * @returns {Function} 取消订阅函数
         */
        on(type, fn) {
            if (!handlers[type]) {
                handlers[type] = [];
            }
            handlers[type].push(fn);
            return function off() {
                const arr = handlers[type];
                const idx = arr.indexOf(fn);
                if (idx !== -1) {
                    arr.splice(idx, 1);
                }
            };
        },

        /**
         * 触发本地事件（不经过 socket）：仅调用该 type 的精确订阅者。
         * @param {string} type — 事件名
         * @param {*} payload — 任意负载
         */
        emit(type, payload) {
            emitLocal(type, payload);
        }
    };
})();
