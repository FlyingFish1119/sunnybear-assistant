/**
 * 私聊 / 独白浮条（发送区浮层插件，锚点 'overlay'）。
 *
 * 选择私聊对象或独白，把内容包装成 <private ...> 标签拼进发送栏（可混合发送）。
 * 状态读写 worldPage.privateChat；发送内容读写 sendArea.inputText。
 *
 * Props:
 *   mainColor — String
 *
 * Injects:
 *   sendArea  — 发送区组件实例（读写 inputText）
 *   worldPage — 页面级共享状态（角色列表 / possessName）
 */
const WorldPrivateChatPanel = {
    name: 'WorldPrivateChatPanel',

    template: `
    <div v-show="visible" class="private-chat-panel">
        <div class="private-chat-panel-row">
            <span class="private-chat-panel-label">类型</span>
            <button class="private-chat-mode-btn" :class="{ active: mode === 'whisper' }" @click="mode = 'whisper'">传纸条</button>
            <button class="private-chat-mode-btn" :class="{ active: mode === 'monologue' }" @click="mode = 'monologue'">独白</button>
            <button class="private-chat-panel-btn private-chat-panel-close" style="margin-left:auto" @click="close">收起</button>
        </div>
        <div class="private-chat-panel-row private-chat-panel-body">
            <template v-if="mode === 'whisper'">
                <span class="private-chat-panel-label">私聊对象</span>
                <el-select v-model="form.targets" multiple filterable clearable
                           placeholder="选择角色（可多选）" size="small" style="flex:1;min-width:160px">
                    <el-option v-for="name in options" :key="name" :label="name" :value="name"></el-option>
                </el-select>
            </template>
            <span v-else class="private-chat-panel-label">独白</span>
            <input class="private-chat-panel-input" v-model="form.content"
                   :placeholder="mode === 'whisper' ? '输入私聊内容，回车添加' : '输入独白内容，回车添加'"
                   @keyup.enter="add" @keyup.esc="close" />
            <button class="private-chat-panel-btn private-chat-panel-btn-primary" @click="add">添加</button>
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
        state: function () { return this.worldPage.privateChat; },
        visible: function () { return this.state.visible; },
        mode: {
            get: function () { return this.state.mode; },
            set: function (v) { this.state.mode = v; }
        },
        form: function () { return this.state.form; },
        /** 可选角色：世界内角色（排除玩家夺舍的自身），旁白启用时可选 */
        options: function () {
            const possess = this.worldPage.possessName;
            const options = (this.worldPage.worldCharacterList || [])
                .map(c => c.name)
                .filter(n => n && n !== possess);
            if (this.worldPage.worldInfo && this.worldPage.worldInfo.narrationEnable !== false) {
                options.push('旁白');
            }
            return options;
        }
    },

    methods: {
        close: function () {
            this.state.visible = false;
            this.state.form = { targets: [], content: '' };
        },

        /** 添加私聊/独白：包装成 <private> 标签拼进发送栏 */
        add: function () {
            const content = (this.form.content || '').trim();
            if (!content) { ElementPlus.ElMessage.warning('请输入内容'); return; }
            let tagged;
            if (this.mode === 'monologue') {
                tagged = '<private from="' + this.playerName() + '">' + content + '</private>';
            } else {
                const targets = (this.form.targets || []).filter(Boolean);
                if (targets.length === 0) { ElementPlus.ElMessage.warning('请选择私聊对象'); return; }
                tagged = '<private from="' + this.playerName() + '" to="' + targets.join(',') + '">' + content + '</private>';
            }
            const cur = this.sendArea.inputText || '';
            this.sendArea.inputText = cur.trim() ? cur.trimEnd() + '\n\n' + tagged : tagged;
            this.close();
            this.$nextTick(function () {
                const ta = document.querySelector('.send-area-textarea');
                if (ta) ta.focus();
            });
        },

        /** 玩家身份：夺舍角色名，否则用户名 */
        playerName: function () {
            const wi = this.worldPage.worldInfo;
            if (wi && wi.possessName) return wi.possessName;
            return this.worldPage.username || '玩家';
        }
    }
};
