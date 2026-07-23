/**
 * 连接状态指示器组件
 *
 * 负责 WebSocket 连接的建立、状态展示、自动重连。
 * 连接成功后通过 $emit('connected', ws) 将 WebSocket 实例交给父组件，
 * 父组件挂载 onmessage 处理业务数据。
 *
 * Events:
 *   connected(ws) — 连接建立时触发，携带 WebSocket 实例
 *   disconnected() — 连接关闭时触发
 */
const ConnectionIndicator = {
    name: 'ConnectionIndicator',

    template: `
    <span class="connection-status" :class="status">
        <span class="connection-dot"></span>
        <span class="connection-text">{{ statusText }}</span>
    </span>`,

    emits: ['connected', 'disconnected'],

    props: {
        wsUrl: {
            type: String,
            default: function () { return API.ws.url; }
        }
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
                this.$emit('connected', this.ws);
            };
            this.ws.onclose = () => {
                console.log('WebSocket 连接已关闭');
                this.ws = null;
                this.status = 'disconnected';
                this.$emit('disconnected');
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
    }
};
