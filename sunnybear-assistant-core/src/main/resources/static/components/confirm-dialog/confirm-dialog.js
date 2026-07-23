/**
 * 通用确认弹窗组件
 *
 * 提供与项目设计风格一致的确认对话框，替代 ElementPlus.ElMessageBox.confirm。
 *
 * 用法：放在父组件模板中，通过 $refs 调用 show()，返回 Promise：
 *   this.$refs.confirmDialog.show({ title, message, confirmText, cancelText, type })
 *     .then(() => { 用户点击确定 })
*     .catch(() => { 用户点击取消或关闭 })
*
* Props:
*   mainColor — String  主题色（默认 'lightsalmon'），用于 info 类型的确认按钮
*
* options:
*   title       — String  弹窗标题（必填）
*   message     — String  提示内容（必填）
*   confirmText — String  确认按钮文字（默认 "确定"）
*   cancelText  — String  取消按钮文字（默认 "取消"）
*   type        — String  类型：'warning' | 'danger' | 'info'（默认 'info'）
*/



const ConfirmDialog = {
    name: 'ConfirmDialog',

    template: `
    <div v-if="visible" class="confirm-overlay" @click.self="cancel">
      <div class="confirm-dialog"
           tabindex="-1"
           ref="dialog"
           @keydown.esc="cancel"
           @keydown.enter.prevent="confirm">
        <div class="confirm-header">
          <div class="confirm-icon-wrap" :class="'confirm-icon--' + type">
            <i :data-lucide="icon"></i>
          </div>
          <div class="confirm-header-text">
            <span class="confirm-title">{{ title }}</span>
            <span class="confirm-subtitle">{{ message }}</span>
          </div>
        </div>
        <div class="confirm-footer">
          <button class="confirm-btn confirm-btn-cancel" @click="cancel">
            <span>{{ cancelText }}</span>
          </button>
          <button class="confirm-btn confirm-btn-accept"
                  @click="confirm"
                  :class="'confirm-btn--' + type"
                  :style="type === 'info' ? {'--main-color': mainColor} : {}">
            <span>{{ confirmText }}</span>
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
            title: '',
            message: '',
            confirmText: '确定',
            cancelText: '取消',
            type: 'info',
            icon: 'info',
            _resolve: null,
            _reject: null,
            // 类型 → 图标 / 颜色映射
            typeIconMap: {
                'warning': 'alert-triangle',
                'danger': 'trash-2',
                'info': 'info'
            }
        };
    },

    methods: {
        /* ---- 公开方法：返回 Promise ---- */
        show(options) {
            return new Promise((resolve, reject) => {
                this._resolve = resolve;
                this._reject = reject;
                this.title = options.title || '确认';
                this.message = options.message || '';
                this.confirmText = options.confirmText || '确定';
                this.cancelText = options.cancelText || '取消';
                this.type = options.type || 'info';
                this.icon = this.typeIconMap[this.type] || 'info';
                this.visible = true;
                this.$nextTick(() => {
                    const el = this.$refs.dialog;
                    if (el) el.focus();
                    this.refreshIcons();
                });
            });
        },

        confirm() {
            this.visible = false;
            if (this._resolve) {
                this._resolve();
                this._resolve = null;
                this._reject = null;
            }
        },

        cancel() {
            this.visible = false;
            if (this._reject) {
                this._reject();
                this._resolve = null;
                this._reject = null;
            }
        },

        refreshIcons() {
            if (typeof lucide !== 'undefined') {
                this.$nextTick(() => lucide.createIcons());
            }
        }
    },

    updated() {
        this.refreshIcons();
    }
};
