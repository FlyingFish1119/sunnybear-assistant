/**
 * 会话名称组件（显示 + 编辑）
 *
 * Props:
 *   currentSession — Object  { id, name, ... } 当前会话对象
 *   mainColor      — String  主题色
 *
 * Emits:
 *   update-session-name(newName) — 保存成功后通知父组件更新名称
 *
 * 交互：
 *   - 双击名称文本 → 进入编辑模式
 *   - Enter / 失焦   → 保存（调用 API.session.update）
 *   - Esc            → 取消编辑
 *
 * 组件不直接修改 prop，而是通过 emit 通知父组件更新。
 * 父组件（index.html）负责修改 currentSession.name。
 */
const SessionName = {
    name: 'SessionName',

    template: `
    <span v-if="!editing"
          :style="{'--main-color': mainColor}"
          :class="{'session-name-editable': currentSession.id}"
          @dblclick="startEdit">
      {{ currentSession.name === undefined ? '新对话' : currentSession.name }}
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
        currentSession: { type: Object, default: function () { return {}; } },
        mainColor: { type: String, default: 'lightsalmon' }
    },

    emits: ['update-session-name'],

    data: function () {
        return {
            editing: false,
            draft: ''
        };
    },

    methods: {
        /* ---- 进入编辑 ---- */
        startEdit: function () {
            if (!this.currentSession.id) return;
            this.draft = this.currentSession.name || '新对话';
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
            if (newName === this.currentSession.name) {
                this.editing = false;
                return;
            }
            try {
                var result = await API.session.update({ id: this.currentSession.id, name: newName });
                if (result.status === 200) {
                    // 通过 emit 通知父组件更新名称，不直接修改 prop
                    this.$emit('update-session-name', newName);
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
