/**
 * 通用确认弹窗组件
 *
 * 提供与项目设计风格一致的确认对话框，替代 ElementPlus.ElMessageBox.confirm。
 * 也可以当输入弹窗用（传 inputValue 就多一个输入框，替代 ElMessageBox.prompt）。
 *
 * 用法：放在父组件模板中，通过 $refs 调用 show()，返回 Promise：
 *   this.$refs.confirmDialog.show({ title, message, confirmText, cancelText, type })
 *     .then(() => { 用户点击确定 })
 *     .catch(() => { 用户点击取消或关闭 })
 *
 *   带输入框（resolve 的参数就是输入内容）：
 *   this.$refs.confirmDialog.show({ title: '重命名', message: '...', inputValue: 旧名 })
 *     .then((value) => { value 是用户输入的内容 })
 *     .catch(() => { 取消 })
 *
 * Props:
 *   mainColor — String  主题色（默认 'lightsalmon'），用于 info 类型的确认按钮
 *
 * options:
 *   title            — String  弹窗标题（必填）
 *   message          — String  提示内容（必填）
 *   confirmText      — String  确认按钮文字（默认 "确定"）
 *   cancelText       — String  取消按钮文字（默认 "取消"）
 *   type             — String  类型：'warning' | 'danger' | 'info'（默认 'info'）
 *   inputValue       — String  可选。传了（含空串）就显示输入框，并作为初始值；
 *                               不传则完全没有输入框，行为与改造前一致
 *   inputPlaceholder — String  可选。输入框占位文字
 *
 * 注意：输入内容的非空校验由调用方负责（比如文件名不允许空），
 *       组件本身不拦，免得把各自的业务规则塞进公共件。
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
        <!-- 可选输入框：show() 传了 inputValue 才出现，不传则完全不影响原有弹窗
             回车不在这里绑，交给外层 dialog 的 keydown 冒泡处理，免得同一个回车触发两次 -->
        <div v-if="hasInput" class="confirm-input-wrap">
          <input ref="input"
                 class="confirm-input"
                 v-model="inputText"
                 :placeholder="inputPlaceholder">
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
            // 可选输入框
            hasInput: false,
            inputText: '',
            inputPlaceholder: '',
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
                // inputValue 传了（含空串）才算输入弹窗；没传就跟以前完全一样
                this.hasInput = options.inputValue != null;
                this.inputText = options.inputValue != null ? String(options.inputValue) : '';
                this.inputPlaceholder = options.inputPlaceholder || '';
                this.visible = true;
                this.$nextTick(() => {
                    // 有输入框就聚焦并全选，方便直接覆盖重打
                    if (this.hasInput && this.$refs.input) {
                        this.$refs.input.focus();
                        this.$refs.input.select();
                    } else if (this.$refs.dialog) {
                        this.$refs.dialog.focus();
                    }
                    this.refreshIcons();
                });
            });
        },

        confirm() {
            // 有输入框时把内容带给调用方；没有则维持原来的无参 resolve
            const value = this.hasInput ? this.inputText : undefined;
            this.visible = false;
            if (this._resolve) {
                this._resolve(value);
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
