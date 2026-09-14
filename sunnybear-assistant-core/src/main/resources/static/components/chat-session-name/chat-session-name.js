/**
 * 对话页会话名称组件（主应用，显示 + 编辑）
 *
 * 由 session-name 复制而来，但去掉兼容层：只依赖注入的 SessionStore，
 * 不再接受 currentSession prop，也不再 emit update-session-name。
 * 插件页仍使用通用的 session-name（prop / emit 模式）。
 *
 * Props:
 *   mainColor — String 主题色
 *
 * Injects:
 *   sessionStore — 会话/消息仓库（主应用必定提供）
 *
 * 交互：
 *   - 双击名称文本 → 进入编辑模式
 *   - Enter / 失焦   → 保存（调用 API.session.update，成功后写回 store）
 *   - Esc            → 取消编辑
 */
const ChatSessionName = {
    name: 'ChatSessionName',

    template: `
    <span v-if="!editing"
          :style="{'--main-color': mainColor}"
          :class="{'session-name-editable': session.id}"
          @dblclick="startEdit">
      {{ session.name === undefined ? '新对话' : session.name }}
    </span>
    <input v-else
           v-model="draft"
           :style="{'--main-color': mainColor}"
           class="session-name-input"
           ref="inputEl"
           maxlength="30"
           @input="autoResize"
           @blur="save"
           @keydown.enter.prevent="save"
           @keydown.esc.prevent="cancel"
    />`,

    props: {
        mainColor: { type: String, default: 'lightsalmon' }
    },

    inject: ['sessionStore'],

    computed: {
        session: function () {
            return this.sessionStore.state.currentSession;
        }
    },

    data: function () {
        return {
            editing: false,
            draft: ''
        };
    },

    methods: {
        /* ---- 进入编辑 ---- */
        startEdit: function () {
            if (!this.session.id) return;
            this.draft = this.session.name || '新对话';
            this.editing = true;
            var self = this;
            this.$nextTick(function () {
                var input = self.$refs.inputEl;
                if (input) {
                    input.focus();
                    input.select();
                    self.autoResize();
                }
            });
        },

        /* ---- 自动调整输入框宽度 ---- */
        autoResize: function () {
            var self = this;
            this.$nextTick(function () {
                var input = self.$refs.inputEl;
                if (!input) return;
                var text = input.value || ' ';
                var style = window.getComputedStyle(input);
                var measurer = document.createElement('span');
                measurer.style.cssText =
                    'position:absolute;visibility:hidden;white-space:pre;' +
                    'font-size:' + style.fontSize + ';' +
                    'font-family:' + style.fontFamily + ';' +
                    'padding:' + style.padding + ';';
                measurer.textContent = text;
                document.body.appendChild(measurer);
                var newWidth = Math.max(80, Math.min(350, measurer.offsetWidth + 8));
                input.style.width = newWidth + 'px';
                document.body.removeChild(measurer);
            });
        },

        /* ---- 保存 ---- */
        save: async function () {
            if (!this.editing) return;
            var newName = this.draft.trim();
            if (!newName) {
                ElementPlus.ElMessage.warning('会话名称不能为空');
                this.editing = false;
                return;
            }
            if (newName === this.session.name) {
                this.editing = false;
                return;
            }
            try {
                var result = await API.session.update({ id: this.session.id, name: newName });
                if (result.status === 200) {
                    // 直接写入共享 store（主应用）
                    this.sessionStore.state.currentSession.name = newName;
                    ElementPlus.ElMessage.success('会话名称已更新');
                } else {
                    ElementPlus.ElMessage.error(result.message || '更新会话名称失败');
                }
            } catch (error) {
                ElementPlus.ElMessage.error('网络请求失败，请检查网络连接');
                console.error('更新会话名称失败:', error);
            } finally {
                this.editing = false;
            }
        },

        /* ---- 取消 ---- */
        cancel: function () {
            this.editing = false;
            this.draft = '';
        }
    },

    updated: function () {
        if (typeof lucide !== 'undefined') {
            this.$nextTick(function () { lucide.createIcons(); });
        }
    }
};
