/**
 * 工具确认弹窗组件（自包含版）
 *
 * 将 WebSocket 通信、图标/标题映射、markdown 渲染、倒计时全部内聚在组件内部。
 * 父组件只需：
 *   1. 传入 ws / mainColor / renderMarkdown 三个 props
 *   2. 调用 this.$refs.toolConfirm.show(toolAsk) 即可
 *
 * Props:
 *   mainColor      — String     主题色
 *
 * 公开方法（通过 ref 调用）：
 *   show(toolAsk)  — 弹出确认对话框，toolAsk 为服务端下发的 { id, toolName, message } 对象
 */
const ToolConfirm = {
    name: 'ToolConfirm',

    template: `
    <div v-if="visible" class="tool-confirm-overlay" @click.self="reject">
      <div class="tool-confirm-dialog"
           tabindex="-1"
           ref="dialog"
           @keydown.esc="reject"
           @keydown.enter.ctrl.prevent="accept">
        <div class="tool-confirm-header">
          <div class="tool-confirm-icon-wrap">
            <i :data-lucide="icon"></i>
          </div>
          <div class="tool-confirm-header-text">
            <span class="tool-confirm-title">{{ title }}</span>
            <span class="tool-confirm-subtitle">AI 请求执行以下操作，请确认是否允许</span>
          </div>
        </div>
        <div class="tool-confirm-body">
          <div class="markdown-body" v-html="renderedMessage"></div>
        </div>
        <div class="tool-confirm-progress">
          <div class="tool-confirm-progress-track">
            <div class="tool-confirm-progress-bar"
                 :style="{width: progressPercent + '%', '--main-color': mainColor}"
                 :class="{'is-warning': progressPercent <= 30, 'is-danger': progressPercent <= 10}">
            </div>
          </div>
          <span class="tool-confirm-progress-text">{{ countdown }} 秒后自动拒绝</span>
        </div>
        <div class="tool-confirm-footer">
          <button class="tool-confirm-btn tool-confirm-btn-cancel" @click="reject">
            <i data-lucide="shield-x" style="width:16px;height:16px"></i>
            <span>拒绝</span>
          </button>
          <button class="tool-confirm-btn tool-confirm-btn-accept"
                  @click="accept"
                  :style="{'--main-color': mainColor}">
            <i data-lucide="shield-check" style="width:16px;height:16px"></i>
            <span>允许执行</span>
          </button>
        </div>
      </div>
    </div>`,

    props: {
        mainColor: { type: String, default: 'lightsalmon' }
    },

    emits: [],

    data() {
        return {
            visible: false,
            confirmId: '',
            title: '',
            icon: 'wrench',
            renderedMessage: '',
            TOTAL: 30,
            countdown: 30,
            progressPercent: 100,
            _timer: null,
            // 工具名 → 图标 / 标题映射
            iconMap: {
                'command_tool': 'terminal',
                'file_write_tool': 'file-pen-line',
                'file_edit_tool': 'file-code',
                'file_delete_tool': 'trash-2',
                'task_run_tool': 'play-circle',
                'net_explore_tool': 'globe',
            },
            titleMap: {
                'command_tool': '命令执行确认',
                'file_write_tool': '文件写入确认',
                'file_edit_tool': '文件编辑确认',
                'file_delete_tool': '文件删除确认',
                'task_run_tool': '任务执行确认',
                'net_explore_tool': '网络探索确认',
            }
        };
    },

    methods: {
        /* ---- 公开方法 ---- */
        show(toolAsk) {
            this.confirmId = toolAsk.id;
            this.title = this.titleMap[toolAsk.toolName] || toolAsk.toolName || '工具执行确认';
            this.icon = this.iconMap[toolAsk.toolName] || 'wrench';
            this.renderedMessage = MarkdownUtils.render(toolAsk.message || '');
            this.visible = true;
            this.$nextTick(() => this.startCountdown());
        },

        /* ---- 内部方法 ---- */
        startCountdown() {
            this.clearTimer();
            this.countdown = this.TOTAL;
            this.progressPercent = 100;
            this._timer = setInterval(() => {
                this.countdown--;
                this.progressPercent = Math.round((this.countdown / this.TOTAL) * 100);
                if (this.countdown <= 0) {
                    this.reject();
                }
            }, 1000);
            this.$nextTick(() => {
                const el = this.$refs.dialog;
                if (el) el.focus();
                this.refreshIcons();
            });
        },

        clearTimer() {
            if (this._timer) {
                clearInterval(this._timer);
                this._timer = null;
            }
        },

        sendResult(confirm) {
            API.chat.confirm({ id: this.confirmId, confirm: confirm })
                .catch(err => console.error('确认请求发送失败:', err));
            this.visible = false;
        },

        accept() {
            this.sendResult(true);
        },

        reject() {
            this.sendResult(false);
        },

        refreshIcons() {
            if (typeof lucide !== 'undefined') {
                this.$nextTick(() => lucide.createIcons());
            }
        }
    },

    watch: {
        visible(val) {
            if (!val) this.clearTimer();
        }
    },

    mounted() {
        if (this.visible) this.startCountdown();
    },

    updated() {
        this.refreshIcons();
    },

    beforeUnmount() {
        this.clearTimer();
    }
};
