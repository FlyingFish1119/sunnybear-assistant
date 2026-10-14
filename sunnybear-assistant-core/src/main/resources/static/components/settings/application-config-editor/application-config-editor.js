/**
 * 应用配置文件编辑器
 *
 * settings 页 topbar 的「配置文件」按钮打开，整块替换设置区，
 * 直接编辑启动目录下的 application.yml：内容先交给后端干跑校验，
 * 通过后落盘并软重启服务，所以这里只管编辑与保存，生效与否由后端负责。
 */
const ApplicationConfigEditor = {
    template: `
        <div class="ace-config-editor">
            <div class="ace-config-bar">
                <span class="ace-config-path" v-if="configPath" :title="configPath">{{ configPath }}</span>
                <span class="ace-config-tip">保存后服务会按新配置重启，正在进行的对话会断开</span>
            </div>

            <div class="ace-config-problems" v-if="problems.length">
                <div class="ace-config-problems-title">配置校验未通过，已阻止保存：</div>
                <ul>
                    <li v-for="(item, index) in problems" :key="index">{{ item }}</li>
                </ul>
            </div>

            <div class="ace-config-host" ref="editorHost"></div>

            <div class="ace-config-footer">
                <el-button :disabled="restarting" @click="$emit('close')">取消</el-button>
                <el-button type="primary" :loading="saving" :disabled="restarting" @click="save">确定并重启</el-button>
            </div>

            <!-- 重启等待：整页遮罩，服务回来后自动刷新 -->
            <div class="ace-config-mask" v-if="restarting">
                <div class="ace-config-mask-spinner"></div>
                <div class="ace-config-mask-text">服务正在按新配置重启…</div>
                <div class="ace-config-mask-sub">完成后页面会自动刷新，请稍候</div>
            </div>
        </div>
    `,

    data() {
        return {
            /** 配置文件绝对路径，显示在顶部条上 */
            configPath: '',
            /** 校验未通过的问题清单 */
            problems: [],
            saving: false,
            restarting: false,
            editor: null
        };
    },

    emits: ['close'],

    async mounted() {
        await this.load();
    },

    beforeUnmount() {
        this.destroyEditor();
    },

    methods: {
        /** 读取当前生效的配置内容 */
        async load() {
            try {
                const res = await API.settings.applicationConfig.get();
                if (res.status !== 200) {
                    ElementPlus.ElMessage.error(res.message || '读取配置文件失败');
                    this.$emit('close');
                    return;
                }
                this.configPath = (res.data && res.data.path) || '';
                const content = (res.data && res.data.content) || '';
                await this.$nextTick();
                this.mountEditor(content);
            } catch (e) {
                ElementPlus.ElMessage.error('读取配置文件失败：' + e.message);
                this.$emit('close');
            }
        },

        mountEditor(content) {
            const host = this.$refs.editorHost;
            if (!host || !window.ace) return;
            // mode / theme 都在本地 lib/ace 下，basePath 必须指过去，
            // 否则 ace 会跑去默认 CDN 找 mode 文件（断网就白屏）
            window.ace.config.set('basePath', API.BASE_PATH + 'lib/ace/');
            const editor = window.ace.edit(host);
            editor.setTheme('ace/theme/textmate');
            editor.session.setMode('ace/mode/yaml');
            editor.session.setUseWorker(false);      // 没放 worker 文件，关掉语法检查线程
            editor.session.setUseWrapMode(true);     // 配置里长行不少，别逼人横向拖
            editor.setOptions({
                fontSize: '13px',
                showPrintMargin: false,
                tabSize: 2,
                useSoftTabs: true
            });
            editor.setValue(content, -1);
            editor.clearSelection();
            editor.moveCursorTo(0, 0);
            editor.commands.addCommand({
                name: 'saveApplicationConfig',
                bindKey: { win: 'Ctrl-S', mac: 'Command-S' },
                exec: () => this.save()
            });
            this.editor = editor;
        },

        destroyEditor() {
            if (this.editor) {
                this.editor.destroy();
                this.editor = null;
            }
        },

        /** 保存：校验不过就把问题列在编辑区上方，通过则落盘并软重启 */
        async save() {
            if (!this.editor || this.saving || this.restarting) return;
            const content = this.editor.getValue();
            this.saving = true;
            try {
                const res = await API.settings.applicationConfig.save(content);
                if (res.status !== 200) {
                    this.problems = Array.isArray(res.data) && res.data.length
                        ? res.data
                        : [res.message || '保存失败'];
                    ElementPlus.ElMessage.error(res.message || '配置校验未通过');
                    return;
                }
                this.problems = [];
                ElementPlus.ElMessage.success(res.message || '配置已保存，服务正在重启');
                this.waitForRestart();
            } catch (e) {
                this.problems = ['保存失败：' + e.message];
                ElementPlus.ElMessage.error('保存失败：' + e.message);
            } finally {
                this.saving = false;
            }
        },

        /** 重启窗口内服务不可达属正常，轮询到恢复为止 */
        waitForRestart() {
            this.restarting = true;
            let attempts = 0;
            const maxAttempts = 60;      // 约 60 秒
            const timer = setInterval(async () => {
                attempts++;
                try {
                    const res = await API.settings.applicationConfig.get();
                    if (res && res.status === 200) {
                        clearInterval(timer);
                        ElementPlus.ElMessage.success('服务已按新配置重启完成');
                        window.location.reload();
                        return;
                    }
                } catch (e) {
                    // 连不上就等下一轮
                }
                if (attempts >= maxAttempts) {
                    clearInterval(timer);
                    this.restarting = false;
                    ElementPlus.ElMessage.error('等待服务恢复超时，请检查配置文件或手动重启');
                }
            }, 1000);
        }
    }
};
