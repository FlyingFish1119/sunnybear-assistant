/**
 * 发言权移交浮条（发送区浮层插件，锚点 'overlay'）。
 *
 * 选择移交对象，把 <switch to:"角色名"> 标签拼进发送栏。
 *
 * Props:
 *   mainColor — String
 *
 * Injects:
 *   sendArea  — 发送区组件实例（读写 inputText）
 *   worldPage — 页面级共享状态
 */
const WorldSwitchChatPanel = {
    name: 'WorldSwitchChatPanel',

    template: `
    <div v-show="visible" class="private-chat-panel">
        <div class="private-chat-panel-row">
            <span class="private-chat-panel-label">移交给</span>
            <el-select v-model="form.target" filterable clearable
                       placeholder="选择角色" size="small" style="flex:1;min-width:160px">
                <el-option v-for="name in options" :key="name" :label="name" :value="name"></el-option>
            </el-select>
            <button class="private-chat-panel-btn private-chat-panel-btn-primary" @click="add">添加</button>
            <button class="private-chat-panel-btn private-chat-panel-close" @click="close">收起</button>
        </div>
    </div>`,

    props: {
        mainColor: { type: String, default: 'lightsalmon' }
    },

    inject: {
        sendArea: { required: true },
        worldPage: { required: true }
    },

    computed: {
        state: function () { return this.worldPage.switchChat; },
        visible: function () { return this.state.visible; },
        form: function () { return this.state.form; },
        options: function () {
            const possess = this.worldPage.possessName;
            return (this.worldPage.worldCharacterList || [])
                .map(c => c.name)
                .filter(n => n && n !== possess);
        }
    },

    methods: {
        close: function () {
            this.state.visible = false;
            this.state.form = { target: '' };
        },

        /** 添加移交标签：<switch to:"角色名">，拼进发送栏 */
        add: function () {
            const target = (this.form.target || '').trim();
            if (!target) { ElementPlus.ElMessage.warning('请选择移交对象'); return; }
            const tagged = '<switch to:"' + target + '">';
            const cur = this.sendArea.inputText || '';
            this.sendArea.inputText = cur.trim() ? cur.trimEnd() + '\n\n' + tagged : tagged;
            this.close();
            this.$nextTick(function () {
                const ta = document.querySelector('.send-area-textarea');
                if (ta) ta.focus();
            });
        }
    }
};
