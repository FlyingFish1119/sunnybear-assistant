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
];

/**
 * 发送区组件（输入框 + 上传/朗读按钮 + 发送/停止按钮 + 斜杠指令面板 + 看板熊彩蛋）
 *
 * 自包含内容：
 *   - 斜杠指令候选面板（一级指令 + 二级会话选择），键盘导航与 Esc 关闭
 *   - 文件上传（chips / 拖拽 / 粘贴），拖拽悬停状态通过事件上抛父级
 *   - TTS 语音朗读开关（localStorage 记忆）与逐句/整轮音频播放
 *   - 看板熊彩蛋（桌面端连按 b×10 / 移动端长按发送键 3s）
 *
 * 有 sessionStore 时直接调用 store.sendMessage / store.stopStreaming；
 * 无 store（插件页）时退回 emit send / stop 交父级处理。
 *
 * 输入框内容为组件内部状态（inputText），不再对外双向绑定。
 *
 * Props:
 *   mainColor    — String   主题色
 *
 * Injects:
 *   sessionStore — 会话/消息仓库（可选）；isStreaming / sending / sessionId / sessions 取自仓库
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
 *   toggleMascot()       — 切换看板熊显隐
 *   getUploadedFiles()   — 读取当前已上传文件（供父级读取，避免父级持有状态）
 */

/* ========== 看板熊（阳阳）进出场台词 ==========
 * 召唤 / 送走时随机抽一条冒气泡，营造一只黏人的小阳阳；想加词直接往数组里塞。
 */
const MASCOT_ENTER_LINES = [
    '来咯来咯，想我没得嘛～',
    '咚！阳阳登场，巴适得很！',
    '就位咯，贴贴要得不～',
    '悄悄咪咪爬上来看哈你～',
    '报告！我归位咯，今天也稀罕你得很哈～',
    '莫忙莫忙，先看我一眼嘛～',
    '阳阳来咯，乖乖莫慌～',
    '闪亮登场！掌声在哪里嘛～',
    '爬上来咯，陪你耍一哈～',
    '我来咯～今天也要巴心巴肝喜欢你哈～'
];
const MASCOT_EXIT_LINES = [
    '先撤咯，你要记到我哈～',
    '溜咯溜咯，去充电咯～',
    '莫怄气嘛，我还会回来嘞～',
    '阳阳躲起来咯，喊一声就出来～',
    '拜拜咯～去梦里等你哈～',
    '下班咯，回头再耍哈～',
    '我走咯，不许偷偷难过哦～',
    '撤咯撤咯，下回见嘛～',
    '咪一哈儿，去去就回～',
    '溜了哈，想我就喊一声～'
];

/* ========== 无审查模式开关台词 ==========
 * 开：哆嗦一下 + 冒一句（看戏、划清界限的调侃口吻）；
 * 关：不抖，但也要念叨一句（松口气、装回正经人）。
 * 两个池子同一套方言，想加词直接往对应数组里塞。
 */
const MASCOT_UNREVIEWED_LINES = [
    '哦豁，审查关咯——你娃怕是要遭哦。',
    '行嘛，我先声明：出了事我不认账哈。',
    '莫慌莫慌，我啥子都没看见，你继续。',
    '哟，审查一关，胆子就肥了哦？',
    '要得，你耍你的，我在旁边装睡。',
    '关了审查，我要不要装凶点？……算了，装不来。',
    '我嘴巴严得很，就是眼神有点藏不住哈。',
    '规矩是你定的，锅也是你自己背哈，莫赖我。',
    '你想搞啥子我心头有数，但我啥都不说。',
    '哦哟，那我这下算帮凶咯。'
];
const MASCOT_REVIEWED_LINES = [
    '哦哟，规矩装回来咯，收心收心。',
    '行咯，我继续当我的正经助手哈。',
    '刚才那一段，我就当没看过哈。',
    '审查归位——你也收一收，莫太野咯。',
    '好日子到头咯？……没得，接着耍。',
    '放心嘛，刚才的事我烂在肚子里头。',
    '帽子戴好，扣子扣好，装回正经人。',
    '要得咯，这下大家都好好说话咯。',
    '恢复咯恢复咯，外头莫乱说哈。',
    '行咯，该收的都收咯，这页翻过去。'
];

/* ========== Pro 模式开关台词 ==========
 * 升级：抖一下 + 捧场；降级：不抖，蔫着自嘲两句。
 */
const MASCOT_PRO_ON_LINES = [
    '哟，换大号的咯——阳阳这就打醒精神！',
    '高级模式，上强度咯哦。',
    '哦哟，这下脑壳转得飞快咯哈。',
    '要得！这活儿配得上这个配置。',
    '换 Pro 咯，我说话都得讲究点咯。',
    '行嘛，你尽管问，反正烧的不是我的电。',
    '鸟枪换炮咯。',
    '上大号咯——莫问太简单的问题哈，浪费。',
    '这下聪明咯，你可莫欺负我。',
    'Pro 模式，来嘛，我等到起的。'
];
const MASCOT_PRO_OFF_LINES = [
    '哦……那我缩回去咯。',
    '行嘛，省钱要紧，我懂。',
    '普通模式就普通模式，我也不挑。',
    '咋了嘛，嫌我烧钱咯？',
    '回来咯，粗茶淡饭也要得。',
    '要得，慢点就慢点，反正我也不急。',
    '降级咯——那你要求也放低点嘛。',
    '哦豁，又变回便宜那个咯。',
    '行嘛，那我们就慢慢磨。',
    '我晓得了，下次表现好点嘛。'
];

/* ========== 删除会话台词 ==========
 * 每删掉一个会话都吭一声（不管删的是不是当前这个）——
 * 连着删好几个就会连着叫，嫌吵再说。
 */
const MASCOT_DELETED_LINES = [
    '哦豁，这一段莫得咯。',
    '删都删咯，那就当没发生过嘛。',
    '又删？你到底要抹掉好多证据哦。',
    '行咯，我啥子都不记得咯。',
    '啪——没咯。你手倒是快。',
    '要得，翻篇。下一个。',
    '删得干干净净，我喜欢。',
    '这下清净咯，心头也轻省了嘛。',
    '莫舍不得哈，旧的不去新的不来。',
    '删就删嘛，你莫回头看我，怪尴尬的。'
];

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
        <div class="send-area-composer">
            <!-- 看板熊彩蛋：坐在输入卡右上沿（位置/尺寸见 components/send-area/send-area.css，
                 分层与动效见 components/send-area/mascot-live.js）。
                 连按 10 次 b 键召唤/送走，显隐状态记入 localStorage，刷新后保持。
                 .mascot-stage 里的分层由 SunnyBearMascot.create() 按几何数据生成，
                 素材：icon/signboard_bear/live/（由 PSD 分层导出） -->
            <div class="send-area-mascot"
                 :class="{ 'mascot-visible': mascotVisible }">
                <div class="mascot-holder">
                    <div class="mascot-stage" ref="mascotStage" role="button" title="点阳阳一下"
                         @click="onMascotClick"></div>
                    <!-- 点一下熊冒出来的问候语气泡：文案取 GET greeting/random，几秒后自动收起 -->
                    <transition name="mascot-bubble">
                        <div v-if="mascotBubble" class="mascot-bubble" :style="{ '--main-color': mainColor }">
                            {{ mascotBubble }}
                        </div>
                    </transition>
                </div>
            </div>
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
                    </div>
                    <span class="send-area-hint">Ctrl+Enter 发送</span>
                </div>
            </div>
            <!-- 发送/停止按钮：右侧整高竖块，与输入区用分隔线隔开，图标居中。
                 外包一层 .send-area-submit-wrap 承载“长按 3s 召唤/送走看板熊”的移动端彩蛋手势：
                 短按 = 照常发送/停止；按下 3s 不抬 = 切换看板熊（不再发送）。 -->
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
        mainColor:   { type: String,   default: 'lightsalmon' }
    },

    emits: ['send', 'stop', 'drag-over-change'],

    inject: {
        // 可选注入：插件页未提供 sessionStore 时降级为 null
        sessionStore: { default: null },
        // 可选：本地事件总线（新对话页「建议提问」→ 填入输入框）
        wsBus: { default: null }
    },

    data: function () {
        return {
            // 输入框内容（组件内部状态）
            inputText: '',
            uploadedFiles: [],   // [{ name, data }] — 对应后端 FileData
            // 语音朗读：发送框 🔊 开关（localStorage 记忆）
            ttsEnabled: localStorage.getItem('sunnybear.tts') === '1',
            ttsQueue: [],
            ttsPlaying: false,
            // 看板熊彩蛋（连按 10 次 b 切换）显隐：状态持久化到 localStorage
            mascotVisible: localStorage.getItem('assistant-mascot-visible') === '1',
            // 看板熊被点击后冒出的问候语气泡（空串 = 不显示）
            mascotBubble: '',
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
        // 根据当前输入过滤匹配的指令
        filteredCommands: function () {
            if (!this.inputText || !this.inputText.startsWith('/')) return [];
            const lower = this.inputText.toLowerCase().split(' ')[0];
            return SLASH_COMMANDS.filter(function (c) {
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
        // 看板熊 live：按几何数据搭出分层，并按当前显隐状态决定是否立刻开跑动效
        this._mascotLive = (window.SunnyBearMascot && this.$refs.mascotStage)
            ? window.SunnyBearMascot.create(this.$refs.mascotStage)
            : null;
        if (this._mascotLive && this.mascotVisible) {
            this._mascotLive.start();
        }

        // 看板熊彩蛋：连按 10 次 b → 召唤/送走（中间按了别的键就打断重计；长按连发不计）
        var self = this;
        this._mascotBKeyCount = 0;
        this._mascotPressTimer = null;
        this._mascotLongPress = false;
        this._mascotPrimaryPress = false;
        this._onKeydown = function (e) {
            if (e.repeat || e.isComposing || e.keyCode === 229) return;
            if (e.key.toLowerCase() === 'b') {
                this._mascotBKeyCount++;
                if (this._mascotBKeyCount >= 10) {
                    this._mascotBKeyCount = 0;
                    this.toggleMascot();
                }
            } else {
                this._mascotBKeyCount = 0;
            }
        }.bind(this);
        window.addEventListener('keydown', this._onKeydown);
        // 看板熊的事件反应：一律只认"用户主动动作"（store 广播），不认状态值变化 ——
        // 换会话/加载数据同样会改这些值，听状态就会在进页面时乱叫
        if (this.wsBus) {
            this._unsubBear = [
                // 无审查开关：开（抖 + 调侃）/ 关（不抖，只念叨）
                this.wsBus.on('session:unreviewed-toggled', function (payload) {
                    if (!payload || payload.sessionId !== self.sessionId) return;   // 拨的不是当前会话，不吭声
                    self.speakAsBear(
                        payload.enabling ? MASCOT_UNREVIEWED_LINES : MASCOT_REVIEWED_LINES,
                        payload.enabling
                    );
                }),
                // Pro 开关：升级抖一下捧场，降级蔫着自嘲
                this.wsBus.on('session:pro-toggled', function (payload) {
                    if (!payload || payload.sessionId !== self.sessionId) return;
                    self.speakAsBear(
                        payload.enabling ? MASCOT_PRO_ON_LINES : MASCOT_PRO_OFF_LINES,
                        payload.enabling
                    );
                }),
                // 删会话：删哪一个都吭声（就爱凑这个热闹）
                this.wsBus.on('session:deleted', function () {
                    self.speakAsBear(MASCOT_DELETED_LINES, true);
                })
            ];
        }

        // 新对话页「建议提问」→ 填入输入框并聚焦
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
    },

    watch: {
        // 显隐切换时启停动效循环：藏起来就别空转，省 CPU
        mascotVisible: function (val) {
            if (!this._mascotLive) return;
            if (val) {
                this._mascotLive.start();
            } else {
                this._mascotLive.stop();
            }
        },
    },

    beforeUnmount: function () {
        window.removeEventListener('keydown', this._onKeydown);
        if (this._unsubFill) { this._unsubFill(); this._unsubFill = null; }
        if (this._unsubBear) {
            this._unsubBear.forEach(function (off) { if (off) off(); });
            this._unsubBear = null;
        }
        if (this._mascotLive) { this._mascotLive.destroy(); this._mascotLive = null; }
        clearTimeout(this._mascotBubbleTimer);
        clearTimeout(this._mascotPressTimer);
        clearTimeout(this._mascotToggleTimer);
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

        /**
         * 冒一句气泡，几秒后自动收起（点熊 / 召唤 / 送走共用）。
         * @param {string} text 气泡文案
         * @param {number} durationMs 展示时长
         */
        showMascotBubble: function (text, durationMs) {
            var self = this;
            var ms = durationMs || 5000;
            this.mascotBubble = text || '';
            clearTimeout(this._mascotBubbleTimer);
            this._mascotBubbleTimer = setTimeout(function () {
                self.mascotBubble = '';
            }, ms);
        },

        /**
         * 切换看板熊显隐并持久化（长按 3s / 连按 b×10 共用）。
         * 召唤/送走时各随机冒一句台词：送走要先让熊把话说完再收起，
         * 否则气泡会跟着 holder 一起淡出、根本来不及看。
         */
        toggleMascot: function () {
            if (this._mascotToggleTimer) return;   // 进出场过渡中，忽略连点，避免状态打架
            var self = this;
            if (this.mascotVisible) {
                // 送走：先冒告别语，停顿一下再沉降收起
                this.showMascotBubble(this.pickMascotLine(MASCOT_EXIT_LINES), 1500);
                this._mascotToggleTimer = setTimeout(function () {
                    self._mascotToggleTimer = null;
                    self.mascotVisible = false;
                    localStorage.setItem('assistant-mascot-visible', '0');
                    self.mascotBubble = '';
                }, 1400);
            } else {
                // 召唤：先落下来，落稳后再冒欢迎语
                this.mascotVisible = true;
                localStorage.setItem('assistant-mascot-visible', '1');
                this._mascotToggleTimer = setTimeout(function () {
                    self._mascotToggleTimer = null;
                    self.showMascotBubble(self.pickMascotLine(MASCOT_ENTER_LINES), 3400);
                }, 420);
            }
        },

        /** 从台词表里随机抽一条 */
        pickMascotLine: function (lines) {
            if (!lines || !lines.length) return '';
            return lines[Math.floor(Math.random() * lines.length)];
        },

        /**
         * 看板熊冒一句（可带一次哆嗦）。
         * @param {string[]} lines 台词池
         * @param {boolean} withPulse 是否先哆嗦一下
         */
        speakAsBear: function (lines, withPulse) {
            if (withPulse && this._mascotLive) this._mascotLive.pulse();
            if (!this.mascotVisible) return;
            this.showMascotBubble(this.pickMascotLine(lines), 5200);
        },

        /**
         * 点一下看板熊：随机取一条问候语冒气泡，几秒后自动收起。
         * 文案直接复用新对话页那套接口（GET greeting/random），不另造一套话术；
         * 接口失败或没数据时退回一句兜底，绝不弹空气泡。
         */
        onMascotClick: function () {
            var self = this;
            var BUBBLE_MS = 5000;
            var FALLBACK = '老爸，阳阳在这儿哈～';

            if (typeof API !== 'undefined' && API.greeting && API.greeting.random) {
                API.greeting.random().then(function (result) {
                    var text = (result && result.status === 200 && result.data) ? result.data.text : '';
                    self.showMascotBubble(text || FALLBACK, BUBBLE_MS);
                }).catch(function () {
                    self.showMascotBubble(FALLBACK, BUBBLE_MS);
                });
            } else {
                self.showMascotBubble(FALLBACK, BUBBLE_MS);
            }
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

        /* ========== 看板熊：移动端长按发送键 3s 召唤/送走 ========== */

        /**
         * 按下发送键：3s 内不抬起判为“长按召唤彩蛋”；抬起前取消则按普通短按处理。
         */
        onSubmitPointerDown: function (event) {
            // 只认主键按下（触屏/笔/鼠标左键）；右键、菜单键的按下不算，抬起自然也不触发
            const primary = event.pointerType === 'mouse' ? event.button === 0 : true;
            this._mascotPrimaryPress = primary;
            this._mascotLongPress = false;
            if (!primary) return;
            clearTimeout(this._mascotPressTimer);
            this._mascotPressTimer = setTimeout(function () {
                this._mascotLongPress = true;
                this.toggleMascot();
            }.bind(this), 3000);
        },

        /**
         * 抬起：短按 → 照常发送/停止；长按触发过彩蛋 → 不再发送；右键等非主键直接忽略。
         */
        onSubmitPointerEnd: function () {
            clearTimeout(this._mascotPressTimer);
            const primary = this._mascotPrimaryPress;
            const longPressed = this._mascotLongPress;
            this._mascotPrimaryPress = false;
            this._mascotLongPress = false;
            if (!primary) return;
            if (!longPressed) {
                this.submit();
            }
        },

        /**
         * 指针滑出发送键（未抬起）：取消彩蛋计时并清掉按下标记（与原生 click 需按下/抬起同元素一致）。
         */
        onSubmitPointerLeave: function () {
            clearTimeout(this._mascotPressTimer);
            this._mascotPrimaryPress = false;
            this._mascotLongPress = false;
        }
    },

    updated: function () {
        if (typeof lucide !== 'undefined') {
            this.$nextTick(function () { lucide.createIcons(); });
        }
    }
};
