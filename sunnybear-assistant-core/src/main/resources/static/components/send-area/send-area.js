/* ========== 斜杠指令注册表 ==========
 * 每项: { name, desc, usage, icon }
 * icon 使用 lucide 图标名，见 https://lucide.dev/icons/
 * 添加新指令只需在此数组中追加一项即可，框架自动生效。
 */
const SLASH_COMMANDS = [
    { name: '/look', desc: '查看指定会话的对话记录', usage: '/look', icon: 'eye', subCommand: 'sessions' },
    { name: '/preview', desc: '显示当前处理后的系统提示词', usage: '/preview', icon: 'scroll-text' },
    { name: '/fast-search', desc: '联网快速搜索并整理为易读简报', usage: '/fast-search <关键字>', icon: 'search' },
    { name: '/extensions', desc: '列出当前可用的扩展脚本', usage: '/extensions', icon: 'file-code' },
    { name: '/init', desc: '探索本地目录并生成项目结构报告到核心文件', usage: '/init <path>', icon: 'folder-search' },
];

/**
 * 发送区插件注册表（全局单例）。
 *
 * 插件按锚点插入扩展组件：
 *   'toolbar'        工具栏图标区（TTS / 上传 / 步骤清单之后），如角色页的私聊/移交按钮
 *   'toolbar-right'  工具行右侧（快捷键提示之后），如模型切换入口
 *   'overlay'        输入区上方的浮层区（发送区容器内、composer 之前），如私聊面板
 * 组件会收到 prop: { mainColor, inputText } 并 inject sendArea（本组件实例），
 * 可读写输入内容（sendArea.inputText）或调用其方法。
 */
const SendAreaPlugins = (function () {
    const slotsByAnchor = Object.create(null);

    return {
        registerSlot: function (anchor, component, order) {
            if (!anchor || !component) return;
            if (!slotsByAnchor[anchor]) slotsByAnchor[anchor] = [];
            const arr = slotsByAnchor[anchor];
            arr.push({ component: component, order: order || 0 });
            arr.sort(function (a, b) { return a.order - b.order; });
        },
        snapshot: function (anchor) {
            return (slotsByAnchor[anchor] || []).slice();
        }
    };
})();

/**
 * 发送区组件（输入框 + 上传/朗读按钮 + 发送/停止按钮 + 斜杠指令面板）
 *
 * 自包含内容：
 *   - 斜杠指令候选面板（一级指令 + 二级会话选择），键盘导航与 Esc 关闭
 *   - 文件上传（chips / 拖拽 / 粘贴），拖拽悬停状态通过事件上抛父级
 *   - TTS 语音朗读开关（localStorage 记忆）与逐句/整轮音频播放
 *
 * ⚠ 看板熊（阳阳）不在本组件里：已拆为独立组件 components/mascot/，
 *   本组件只保留一个手势入口 —— 移动端长按发送键 3s 抛 wsBus 事件 'mascot:toggle'。
 *
 * 有 sessionStore 时直接调用 store.sendMessage / store.stopStreaming；
 * 无 store（插件页）时退回 emit send / stop 交父级处理。
 *
 * 输入框内容为组件内部状态（inputText），不再对外双向绑定。
 *
 * Props:
 *   mainColor    — String   主题色
 *   commands     — Array    斜杠指令表（不传则用模块内置的 SLASH_COMMANDS；
 *                           插件页可传入自己的指令，如角色页的角色专属指令）
 *
 * Injects:
 *   sessionStore — 会话/消息仓库（可选）；isStreaming / sending / sessionId / sessions 取自仓库
 *   wsBus        — 本地事件总线（可选）；长按彩蛋与「建议提问」填入走它
 *
 * Emits:
 *   send(payload)        — { content, files, tts } 请求发送
 *   stop                 — 请求中止流式传输
 *   drag-over-change     — 拖拽悬停状态变化（透传给父级控制遮罩）
 *
 * 公开方法（通过 ref 调用）：
 *   submit()             — 触发发送/停止（供键盘 Enter 或父级入口）
 *   clear()              — 清空输入框与已上传文件（供父级在 init_user 确认后调用）
 *   isTtsEnabled()       — 读取语音朗读开关状态
 *   playMessageAudio(msg)— 重播某条消息的整轮完整音频
 *   getUploadedFiles()   — 读取当前已上传文件（供父级读取，避免父级持有状态）
 */

const SendArea = {
    name: 'SendArea',

    template: `
    <div class="send-area-container" :style="{'--main-color': mainColor}" style="position: relative;">
        <command-suggest
            :commands="filteredCommands"
            :active-index="commandActiveIndex"
            :visible="commandSuggestVisible"
            :main-color="mainColor"
            :sub-options="subOptions"
            :sub-title="'选择一个会话'"
            @select="onCommandSelect"
            @sub-select="onSubSelect"
            @back="onCommandBack"
            @update:active-index="idx => commandActiveIndex = idx"
        ></command-suggest>
        <file-upload ref="fileUpload"
            drop-zone=".message-area-wrapper"
            :files="uploadedFiles"
            :enable-paste="true"
            :main-color="mainColor"
            @update-files="files => uploadedFiles = files"
            @drag-over-change="isChange => $emit('drag-over-change', isChange)">
        </file-upload>
        <!-- 输入区上方的插件浮层（锚点 'overlay'，如私聊 / 移交面板） -->
        <component v-for="(slot, si) in overlaySlots"
                   :key="'send-overlay-' + si"
                   :is="slot.component"
                   :main-color="mainColor"></component>
        <div class="send-area-composer">
            <div class="send-area-main">
                <auto-resize-textarea
                    ref="textarea"
                    class="send-area-textarea"
                    :main-color="mainColor"
                    v-model="inputText"
                    placeholder="输入消息，Ctrl+Enter 发送，Enter 换行"
                    :max-height="180"
                    :min-height="60"
                    @submit="submit"
                    @cancel="onTextareaCancel"
                    @keydown="onTextareaKeydown"
                ></auto-resize-textarea>
                <div class="send-area-toolbar">
                    <div class="send-area-tools">
                        <button class="send-area-icon-btn tts-toggle-button" :class="{ 'tts-on': ttsEnabled }"
                                @click="toggleTts()"
                                :title="ttsEnabled ? '本条消息语音朗读已开启（点击关闭）' : '开启本条消息的语音朗读'">
                            <i v-show="ttsEnabled" data-lucide="volume-2"></i>
                            <i v-show="!ttsEnabled" data-lucide="volume-x"></i>
                        </button>
                        <button class="send-area-icon-btn" @click="$refs.fileUpload.openFilePicker()" title="上传文件">
                            <i data-lucide="paperclip"></i>
                        </button>
                        <!-- 步骤清单跟踪：紧挨文件上传按钮右侧，无步骤时不渲染 -->
                        <mark-tracker :main-color="mainColor"></mark-tracker>
                        <!-- 工具栏插件槽（锚点 'toolbar'，如私聊 / 移交按钮） -->
                        <component v-for="(slot, si) in toolbarSlots"
                                   :key="'send-toolbar-' + si"
                                   :is="slot.component"
                                   :main-color="mainColor"></component>
                    </div>
                    <div class="send-area-toolbar-right">
                        <span class="send-area-hint">Ctrl+Enter 发送</span>
                        <!-- 工具行右侧插件槽（锚点 'toolbar-right'，如模型切换入口） -->
                        <component v-for="(slot, si) in toolbarRightSlots"
                                   :key="'send-toolbar-right-' + si"
                                   :is="slot.component"
                                   :main-color="mainColor"></component>
                    </div>
                </div>
            </div>
            <!-- 发送/停止按钮：右侧整高竖块，与输入区用分隔线隔开，图标居中。
                 外包一层 .send-area-submit-wrap 承载「长按 3s 召唤/送走看板熊」的手势：
                 短按 = 照常发送/停止；按下 3s 不抬 = 抛 'mascot:toggle'（不再发送）。
                 熊自己订阅该事件，本组件不知道熊的任何细节。 -->
            <div class="send-area-submit-wrap"
                 @pointerdown="onSubmitPointerDown"
                 @pointerup="onSubmitPointerEnd"
                 @pointercancel="onSubmitPointerEnd"
                 @pointerleave="onSubmitPointerLeave">
                <button v-if="isStreaming"
                    class="send-area-submit-button"
                    @click="onStop()"
                    :disabled="isStreaming && !sessionId"
                >
                    <i data-lucide="square"></i>
                </button>
                <button v-else
                        class="send-area-submit-button"
                        @click="submit"
                        :disabled="busy || (!isStreaming && !inputText && uploadedFiles.length === 0)"
                >
                    <i data-lucide="send"></i>
                </button>
            </div>
        </div>
    </div>`,

    props: {
        mainColor:   { type: String,   default: 'lightsalmon' },
        // 斜杠指令表：插件页可传入自己的指令（如角色页的角色专属指令）；
        // 不传则用模块内置的通用指令表 SLASH_COMMANDS
        commands:    { type: Array,    default: () => SLASH_COMMANDS }
    },

    emits: ['send', 'stop', 'drag-over-change'],

    inject: {
        // 可选注入：插件页未提供 sessionStore 时降级为 null
        sessionStore: { default: null },
        // 可选：本地事件总线（新对话页「建议提问」→ 填入输入框 / 长按彩蛋 → 召唤熊）
        wsBus: { default: null }
    },

    provide: function () {
        // 向插件槽组件暴露本实例：可读写 inputText / uploadedFiles、调用 submit 等
        return { sendArea: this };
    },

    data: function () {
        return {
            // 工具栏插件槽（锚点 'toolbar'）
            toolbarSlots: SendAreaPlugins.snapshot('toolbar'),
            // 工具行右侧插件槽（锚点 'toolbar-right'）
            toolbarRightSlots: SendAreaPlugins.snapshot('toolbar-right'),
            // 浮层插件槽（锚点 'overlay'）
            overlaySlots: SendAreaPlugins.snapshot('overlay'),
            // 输入框内容（组件内部状态）
            inputText: '',
            uploadedFiles: [],   // [{ name, data }] — 对应后端 FileData
            // 语音朗读：发送框 🔊 开关（localStorage 记忆）
            ttsEnabled: localStorage.getItem('sunnybear.tts') === '1',
            ttsQueue: [],
            ttsPlaying: false,
            // 斜杠指令候选
            commandActiveIndex: 0,
            commandSubMode: null,      // null | 'sessions'  — 二级面板模式
            commandParentCmd: null     // 触发二级面板的一级指令
        };
    },

    computed: {
        // 会话/消息仓库派生的状态（未注入时降级为默认值）
        isStreaming: function () {
            return this.sessionStore ? this.sessionStore.isStreaming : false;
        },
        sending: function () {
            return this.sessionStore ? this.sessionStore.sending : false;
        },
        // 本轮不可交互（send/edit/replace 在途或流式输出中）
        busy: function () {
            return this.sessionStore ? this.sessionStore.busy : false;
        },
        sessionId: function () {
            return this.sessionStore ? this.sessionStore.currentSessionId : '';
        },
        // 斜杠指令候选面板是否可见
        commandSuggestVisible: function () {
            if (this.commandSubMode && this.subOptions.length > 0) return true;
            return this.inputText.startsWith('/') && this.filteredCommands.length > 0;
        },
        // 根据当前输入过滤匹配的指令（指令表由 commands prop 提供，默认内置表）
        filteredCommands: function () {
            if (!this.inputText || !this.inputText.startsWith('/')) return [];
            const lower = this.inputText.toLowerCase().split(' ')[0];
            return this.commands.filter(function (c) {
                return c.name.startsWith(lower) || c.name.includes(lower);
            });
        },
        // 二级选项（如会话列表，来自 SessionStore）
        subOptions: function () {
            if (this.commandSubMode === 'sessions') {
                var sessions = this.sessionStore ? this.sessionStore.sessions : [];
                return sessions.map(function (s) {
                    return { id: s.id, label: s.name || s.id, desc: '' };
                });
            }
            return [];
        }
    },

    watch: {
        // 指令候选列表变化时，重置高亮到第一项
        filteredCommands: function () {
            this.commandActiveIndex = 0;
        },
        // 输入不再以 / 开头时，退出二级模式
        inputText: function (val) {
            if (!val || !val.startsWith('/')) {
                this.commandSubMode = null;
                this.commandParentCmd = null;
            }
        }
    },

    mounted: function () {
        // 新对话页「建议提问」→ 填入输入框并聚焦
        var self = this;
        if (this.wsBus) {
            this._unsubFill = this.wsBus.on('send-area:fill', function (text) {
                self.inputText = text;
                self.$nextTick(function () {
                    var el = self.$refs.textarea && self.$refs.textarea.$el;
                    if (el) {
                        el.focus();
                        if (el.setSelectionRange) el.setSelectionRange(el.value.length, el.value.length);
                    }
                });
            });
        }
        // 文件资源栏「加载到发送栏」：把文件快照（{name, data: dataURI}）挂进附件区
        this._onAttachToSendbar = function (e) {
            var files = (e.detail && e.detail.files) || [];
            if (!files.length) return;
            self.uploadedFiles = self.uploadedFiles.concat(files);
        };
        window.addEventListener('sunnybear:attach-to-sendbar', this._onAttachToSendbar);
    },

    beforeUnmount: function () {
        if (this._unsubFill) { this._unsubFill(); this._unsubFill = null; }
        if (this._onAttachToSendbar) {
            window.removeEventListener('sunnybear:attach-to-sendbar', this._onAttachToSendbar);
            this._onAttachToSendbar = null;
        }
        clearTimeout(this._pressTimer);
        if (this._ttsAudio) {
            this._ttsAudio.pause();
            this._ttsAudio.removeAttribute('src');
        }
    },

    methods: {
        /* ========== 对外方法 ========== */

        /**
         * 短按发送/停止的统一入口（等价于发送按钮 @click + :disabled）。
         */
        submit: function () {
            if (this.isStreaming) {
                this.onStop();
                return;
            }
            if (this.sending) return;
            if (!this.inputText && this.uploadedFiles.length === 0) return;
            var payload = {
                content: this.inputText,
                files: this.uploadedFiles,
                tts: this.ttsEnabled
            };
            if (this.sessionStore) {
                this.sessionStore.sendMessage(payload);
            } else {
                this.$emit('send', payload);
            }
        },

        /** 停止流式传输（有 store 直接调 store，否则 emit 交父级） */
        onStop: function () {
            if (this.sessionStore) {
                this.sessionStore.stopStreaming();
            } else {
                this.$emit('stop');
            }
        },

        /**
         * 清空输入框与已上传文件（服务端 init_user 确认后由父级调用）。
         */
        clear: function () {
            this.inputText = '';
            this.uploadedFiles = [];
        },

        /**
         * 读取当前已上传文件（供父级需要时读取）。
         */
        getUploadedFiles: function () {
            return this.uploadedFiles;
        },

        /**
         * 语音朗读开关是否开启（供父级在处理 TTS 音频帧时判断）。
         */
        isTtsEnabled: function () {
            return this.ttsEnabled;
        },

        /* ========== 键盘 / 指令面板 ========== */

        /** Esc 键：优先关闭指令面板，否则透传 cancel（编辑模式退出等由父级兜底） */
        onTextareaCancel: function () {
            if (this.commandSubMode) {
                this.onCommandBack();
                return;
            }
            if (this.commandSuggestVisible) {
                this.inputText = '';
                return;
            }
        },

        /**
         * 指令面板键盘导航（由 auto-resize-textarea 的 @keydown 触发）。
         */
        onTextareaKeydown: function (event) {
            if (!this.commandSuggestVisible) return;

            // 二级面板模式
            if (this.commandSubMode) {
                const len = this.subOptions.length;
                if (event.key === 'ArrowDown') {
                    event.preventDefault();
                    this.commandActiveIndex = (this.commandActiveIndex + 1) % len;
                } else if (event.key === 'ArrowUp') {
                    event.preventDefault();
                    this.commandActiveIndex = (this.commandActiveIndex - 1 + len) % len;
                } else if (event.key === 'Enter' && !event.ctrlKey && !event.shiftKey) {
                    event.preventDefault();
                    const opt = this.subOptions[this.commandActiveIndex];
                    if (opt) this.onSubSelect(opt);
                } else if (event.key === 'Escape') {
                    event.preventDefault();
                    this.onCommandBack();
                }
                return;
            }

            // 一级面板模式
            const len = this.filteredCommands.length;
            if (event.key === 'ArrowDown') {
                event.preventDefault();
                this.commandActiveIndex = (this.commandActiveIndex + 1) % len;
            } else if (event.key === 'ArrowUp') {
                event.preventDefault();
                this.commandActiveIndex = (this.commandActiveIndex - 1 + len) % len;
            } else if (event.key === 'Enter' && !event.ctrlKey && !event.shiftKey) {
                event.preventDefault();
                const cmd = this.filteredCommands[this.commandActiveIndex];
                if (cmd) this.onCommandSelect(cmd);
            } else if (event.key === 'Escape') {
                event.preventDefault();
                this.inputText = '';
            }
        },

        /**
         * 选中一级指令：有 subCommand 则进入二级面板，否则直接替换输入框。
         */
        onCommandSelect: function (cmd) {
            this.inputText = cmd.name + ' ';
            if (cmd.subCommand) {
                this.commandSubMode = cmd.subCommand;
                this.commandParentCmd = cmd;
                this.commandActiveIndex = 0;
                return;
            }
            this.commandActiveIndex = 0;
            this.focusTextarea();
        },

        /** 选中二级选项（如会话） → 拼出最终指令 */
        onSubSelect: function (opt) {
            if (this.commandParentCmd && this.commandParentCmd.name === '/look') {
                this.inputText = '/look ' + opt.id + ' ';
            }
            this.commandSubMode = null;
            this.commandParentCmd = null;
            this.commandActiveIndex = 0;
            this.focusTextarea();
        },

        /** 从二级面板返回一级 */
        onCommandBack: function () {
            this.commandSubMode = null;
            this.commandParentCmd = null;
            this.commandActiveIndex = 0;
        },

        focusTextarea: function () {
            this.$nextTick(function () {
                const ta = document.querySelector('.send-area-textarea');
                if (ta) ta.focus();
            });
        },

        /* ========== TTS 语音朗读 ========== */

        /** 发送框 🔊 开关（localStorage 记忆）；关闭时同步清掉队列 */
        toggleTts: function () {
            this.ttsEnabled = !this.ttsEnabled;
            localStorage.setItem('sunnybear.tts', this.ttsEnabled ? '1' : '0');
            if (!this.ttsEnabled) {
                this.clearTtsQueue();
            }
        },

        /** 常驻单 Audio 元素（非响应式），onended 驱动队列 FIFO */
        ensureTtsAudio: function () {
            if (!this._ttsAudio) {
                this._ttsAudio = new Audio();
                this._ttsAudio.onended = function () {
                    this.ttsQueue.shift();
                    this.ttsPlaying = false;
                    this.playNextTts();
                }.bind(this);
                this._ttsRetryPending = false;
            }
            return this._ttsAudio;
        },

        /** 清空播放队列并停掉当前音频（停止/切会话/新轮/发新消息/关开关时调用） */
        clearTtsQueue: function () {
            this.ttsQueue = [];
            this.ttsPlaying = false;
            if (this._ttsAudio) {
                this._ttsAudio.pause();
                this._ttsAudio.removeAttribute('src');
            }
        },

        /** 逐句音频入队（###TTS_AUDIO### 帧到达）；未在播放时立即起播 */
        enqueueTtsAudio: function (audioBase64) {
            if (!audioBase64) {
                return;
            }
            this.ttsQueue.push(audioBase64);
            this.playNextTts();
        },

        /** 播放队头；队空 / 已在播放 / 开关已关时不动 */
        playNextTts: function () {
            if (this.ttsPlaying || this.ttsQueue.length === 0 || !this.ttsEnabled) {
                return;
            }
            this.ttsPlaying = true;
            this.playAudioData(this.ttsQueue[0], 'audio/mpeg');
        },

        /** 底层播放：base64 → data URI；被自动播放策略拦截时挂起，等下一次用户手势重试 */
        playAudioData: function (audioBase64, mime) {
            const el = this.ensureTtsAudio();
            el.src = 'data:' + mime + ';base64,' + audioBase64;
            const p = el.play();
            if (p && p.catch) {
                p.catch(function () {
                    el.removeAttribute('src');
                    this.ttsPlaying = false;
                    if (!this._ttsRetryPending) {
                        this._ttsRetryPending = true;
                        document.addEventListener('pointerdown', function () {
                            this._ttsRetryPending = false;
                            this.playNextTts();
                        }.bind(this), { once: true });
                    }
                }.bind(this));
            }
        },

        /** 消息气泡 🔊：重播整轮完整音频（extension.ttsAudio，历史/刷新后可用）。供父级通过 ref 调用 */
        playMessageAudio: function (msg) {
            const tts = msg.extension && msg.extension.ttsAudio;
            if (!tts || !tts.audio) {
                return;
            }
            this.clearTtsQueue();
            const mime = tts.format === 'wav' ? 'audio/wav' : 'audio/mpeg';
            this.playAudioData(tts.audio, mime);
        },

        /* ========== 长按发送键 3s：召唤/送走看板熊 ==========
           本组件不认识熊，只负责把手势翻译成 'mascot:toggle' 事件抛出去。 */

        /**
         * 按下发送键：3s 内不抬起判为「长按彩蛋」；抬起前取消则按普通短按处理。
         */
        onSubmitPointerDown: function (event) {
            // 只认主键按下（触屏/笔/鼠标左键）；右键、菜单键的按下不算，抬起自然也不触发
            const primary = event.pointerType === 'mouse' ? event.button === 0 : true;
            this._primaryPress = primary;
            this._longPress = false;
            if (!primary) return;
            var self = this;
            clearTimeout(this._pressTimer);
            this._pressTimer = setTimeout(function () {
                self._longPress = true;
                if (self.wsBus) self.wsBus.emit('mascot:toggle');
            }, 3000);
        },

        /**
         * 抬起：短按 → 照常发送/停止；长按触发过彩蛋 → 不再发送；右键等非主键直接忽略。
         */
        onSubmitPointerEnd: function () {
            clearTimeout(this._pressTimer);
            const primary = this._primaryPress;
            const longPressed = this._longPress;
            this._primaryPress = false;
            this._longPress = false;
            if (!primary) return;
            if (!longPressed) {
                this.submit();
            }
        },

        /**
         * 指针滑出发送键（未抬起）：取消彩蛋计时并清掉按下标记（与原生 click 需按下/抬起同元素一致）。
         */
        onSubmitPointerLeave: function () {
            clearTimeout(this._pressTimer);
            this._primaryPress = false;
            this._longPress = false;
        }
    },

    updated: function () {
        if (typeof lucide !== 'undefined') {
            this.$nextTick(function () { lucide.createIcons(); });
        }
    }
};
