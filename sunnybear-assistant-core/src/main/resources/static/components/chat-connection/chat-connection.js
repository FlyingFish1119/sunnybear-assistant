/**
 * 对话页专用连接组件（主应用）
 *
 * 由 connection-indicator 复制而来，区别在于：
 *   - 连接建立/关闭时直接把 WebSocket 交接给 WsBus（setSocket / clearSocket），
 *     由 WsBus 广播本地事件 'ws:connected' / 'ws:disconnected' 通知 store；
 *   - 不再向上 $emit connected / disconnected，子组件自包含。
 *
 * 插件页（character / world）仍使用通用的 connection-indicator，本组件仅主应用使用。
 *
 * Props:
 *   wsUrl — String  WebSocket 地址
 *
 * Injects:
 *   wsBus — WebSocket 消息总线（负责接管 onmessage 与连接生命周期广播）
 */
const ChatConnection = {
    name: 'ChatConnection',

    template: `
    <span class="connection-status" :class="status">
        <span class="connection-dot"></span>
        <span class="connection-text">{{ statusText }}</span>
    </span>`,

    props: {
        wsUrl: {
            type: String,
            default: function () { return API.ws.url; }
        }
    },

    inject: {
        // 可选注入：未提供时退化为纯指示器（不接管 socket）
        wsBus: { default: null }
    },

    data() {
        return {
            status: 'connecting',
            ws: null,
            reconnectTimer: null,
            reconnectAttempts: 0,
        };
    },

    computed: {
        statusText: function () {
            const map = {
                connected: '已连接',
                connecting: '连接中...',
                disconnected: '已断开'
            };
            return map[this.status] || '未知';
        }
    },

    methods: {
        /**
         * 建立 WebSocket 连接
         */
        connectWebSocket() {
            if (this.ws != null && this.ws.readyState === WebSocket.OPEN) {
                return;
            }
            this.status = 'connecting';
            this.ws = new WebSocket(this.wsUrl);
            this.ws.onopen = () => {
                console.log('WebSocket 连接已打开');
                this.status = 'connected';
                this.stopReconnect();
                // 交接给消息总线：总线接管 onmessage 并广播 'ws:connected'
                if (this.wsBus) this.wsBus.setSocket(this.ws);
            };
            this.ws.onclose = () => {
                console.log('WebSocket 连接已关闭');
                this.ws = null;
                this.status = 'disconnected';
                if (this.wsBus) this.wsBus.clearSocket();
                this.startReconnect();
            };
            this.ws.onerror = () => {
                console.log('WebSocket 连接错误');
                this.status = 'disconnected';
            };
        },

        /**
         * 开始自动重连（指数退避，最大间隔 30 秒）
         */
        startReconnect() {
            this.stopReconnect();
            const baseDelay = 1000;
            const maxDelay = 30000;
            const delay = Math.min(baseDelay * Math.pow(2, this.reconnectAttempts), maxDelay);
            console.log('将在 ' + (delay / 1000) + ' 秒后尝试重连...');
            this.reconnectTimer = setTimeout(() => {
                this.reconnectAttempts++;
                this.connectWebSocket();
            }, delay);
        },

        /**
         * 停止自动重连
         */
        stopReconnect() {
            if (this.reconnectTimer) {
                clearTimeout(this.reconnectTimer);
                this.reconnectTimer = null;
            }
            this.reconnectAttempts = 0;
        }
    },

    mounted() {
        this.connectWebSocket();
    },

    beforeUnmount() {
        this.stopReconnect();
        if (this.ws) {
            this.ws.onopen = null;
            this.ws.onclose = null;
            this.ws.onerror = null;
            this.ws.close();
            this.ws = null;
        }
        if (this.wsBus) this.wsBus.clearSocket();
    }
};
