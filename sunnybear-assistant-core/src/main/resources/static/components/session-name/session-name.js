/**
 * 会话名称组件（显示 + 编辑）
 *
 * Props:
 *   currentSession — Object  { id, name, ... }（可选，插件页回退用；主应用由 store 提供）
 *   mainColor      — String  主题色
 *
 * Injects:
 *   sessionStore   — 会话/消息仓库（可选）；优先读取其 currentSession
 *
 * Emits:
 *   update-session-name(newName) — 保存成功后通知父组件更新名称（仅无 store 的插件页触发）
 *
 * 交互：
 *   - 双击名称文本 → 进入编辑模式
 *   - Enter / 失焦   → 保存（调用 API.session.update）
 *   - Esc            → 取消编辑
 *
 * 有 sessionStore（主应用）时直接写入 store.currentSession.name；
 * 无 store（插件页）时通过 emit 通知父组件修改传入的 currentSession。
 */
const SessionName = {
    name: 'SessionName',

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
        // 插件页无 store 时作为回退数据源传入；主应用通过注入的 sessionStore 提供
        currentSession: { type: Object, default: null },
        mainColor: { type: String, default: 'lightsalmon' }
    },

    emits: ['update-session-name'],

    inject: {
        // 可选注入：插件页未提供 sessionStore 时降级为 null
        sessionStore: { default: null }
    },

    computed: {
        // 优先读取注入的 store（主应用），否则回退到传入的 currentSession（插件页）
        session: function () {
            if (this.sessionStore) {
                return this.sessionStore.state.currentSession;
            }
            return this.currentSession || {};
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
                    // 主应用：直接写入共享 store；插件页（无 store）：emit 通知父组件
                    if (this.sessionStore) {
                        this.sessionStore.state.currentSession.name = newName;
                    } else {
                        this.$emit('update-session-name', newName);
                    }
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
