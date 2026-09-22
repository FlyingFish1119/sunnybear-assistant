/* ============================================================
 * 看板熊（阳阳）—— 独立吉祥物组件
 * ------------------------------------------------------------
 * 从 send-area 里彻底拆出来：台词、显隐、气泡、彩蛋触发、事件反应全在这里，
 * send-area 不再知道熊的存在（只留一个锚点类名给挂载用）。
 *
 * 挂载：Teleport 到 anchor 命中的锚点元素里（默认 .send-area-composer），
 *       所以视觉位置与拆之前完全一致（趴在输入卡右上沿，宽度 = 卡宽 1/4）。
 *       ⚠ 页面必须把本组件放在锚点元素（send-area）之后，Teleport 才找得到目标；
 *         找不到就整只熊不渲染，而不是报一堆 Vue 警告。
 *
 * 彩蛋：桌面端连按 b×10 / 移动端长按发送键 3s（后者由 send-area 走 wsBus
 *       抛 'mascot:toggle'，本组件只认这一种外部指令，不反向依赖 send-area）。
 *
 * Props:
 *   mainColor  — String  主题色
 *   anchor     — String  挂载锚点选择器（默认 '.send-area-composer'）
 *
 * Injects:
 *   wsBus        — 本地事件总线（可选）：熊对用户动作的反应 + 接收召唤指令
 *   sessionStore — 会话仓库（可选）：只用来比对"是不是当前会话"
 * ============================================================ */

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

/* ========== 新建对话台词 ==========
 * 每次开新对话都冒一句——不抖：开新对话是平和的开始，不是"被惊到"。
 */
const MASCOT_NEW_LINES = [
    '哟，又开一个新的咯——来嘛。',
    '新本子翻开咯，这次写点啥子？',
    '好咯，前头那些一笔勾销，从头来。',
    '空白的哦，你可莫浪费咯。',
    '来咯来咯，我精神得很！',
    '新对话，新气象——你想聊啥子？',
    '翻篇咯，这次聊点正经的……或者不正经的。',
    '哦哟，又一张白纸，压力给到你咯。',
    '要得，坐好咯，我听到起的。',
    '新开一局，来嘛，莫客气。'
];

const Mascot = {
    name: 'Mascot',

    template: `
    <teleport v-if="anchorReady" :to="anchor">
        <div class="mascot-root" :class="{ 'mascot-visible': visible }" :style="{ '--main-color': mainColor }">
            <div class="mascot-holder">
                <!-- title 跟着显隐走：送走之后元素还在 DOM 里，静态 title 会照弹不误 -->
                <div class="mascot-stage" ref="stage" role="button"
                     :title="visible ? '点阳阳一下' : null"
                     @click="onClick"></div>
                <!-- 点一下熊冒出来的问候语气泡：文案取 GET greeting/random，几秒后自动收起 -->
                <transition name="mascot-bubble">
                    <div v-if="bubble" class="mascot-bubble">{{ bubble }}</div>
                </transition>
            </div>
        </div>
    </teleport>`,

    props: {
        mainColor: { type: String, default: 'lightsalmon' },
        // 挂载锚点：熊要趴在哪个元素里（默认输入卡，由页面上的 send-area 提供）。
        // 锚点不存在时整只熊不渲染 —— 与其挂到 body 上飘在屏幕外，不如干脆不出现。
        anchor: { type: String, default: '.send-area-composer' }
    },

    inject: {
        wsBus: { default: null },
        sessionStore: { default: null }
    },

    data: function () {
        return {
            // 锚点要等父级（send-area）渲染完才存在，挂载前先探一次、拿不到就跳过渲染
            anchorReady: false,
            visible: localStorage.getItem('assistant-mascot-visible') === '1',
            bubble: ''
        };
    },

    computed: {
        sessionId: function () {
            return this.sessionStore ? this.sessionStore.currentSessionId : '';
        }
    },

    watch: {
        // 显隐切换时启停动效循环：藏起来就别空转，省 CPU
        visible: function (val) {
            if (!this._live) return;
            if (val) {
                this._live.start();
            } else {
                this._live.stop();
            }
        }
    },

    mounted: function () {
        var self = this;

        // 锚点由页面上的 send-area 渲染出来，本组件挂载时它可能还没进 DOM，
        // 所以先探一次：拿到就开传送，拿不到就整只熊不渲染（$nextTick 后 stage 才有）
        this.anchorReady = !!document.querySelector(this.anchor);
        if (!this.anchorReady) return;

        // 分层与动效：由 mascot-live.js 按几何数据搭出（素材 icon/signboard_bear/live/）
        this._live = (window.SunnyBearMascot && this.$refs.stage)
            ? window.SunnyBearMascot.create(this.$refs.stage)
            : null;
        if (this._live && this.visible) {
            this._live.start();
        }

        // 彩蛋一：连按 10 次 b 切换（中间按了别的键就打断重计；长按连发不计）
        this._bKeyCount = 0;
        this._onKeydown = function (e) {
            if (e.repeat || e.isComposing || e.keyCode === 229) return;
            if (e.key.toLowerCase() === 'b') {
                this._bKeyCount++;
                if (this._bKeyCount >= 10) {
                    this._bKeyCount = 0;
                    this.toggle();
                }
            } else {
                this._bKeyCount = 0;
            }
        }.bind(this);
        window.addEventListener('keydown', this._onKeydown);

        if (!this.wsBus) return;

        // 彩蛋二：send-area 的移动端长按发送键 3s → 抛 'mascot:toggle' 过来召唤/送走
        this._unsub = [
            this.wsBus.on('mascot:toggle', function () {
                self.toggle();
            }),
            // 熊的事件反应：一律只认"用户主动动作"（store 广播），不认状态值变化 ——
            // 换会话/加载数据同样会改这些值，听状态就会在进页面时乱叫
            this.wsBus.on('session:unreviewed-toggled', function (payload) {
                if (!payload || payload.sessionId !== self.sessionId) return;   // 拨的不是当前会话，不吭声
                self.speak(
                    payload.enabling ? MASCOT_UNREVIEWED_LINES : MASCOT_REVIEWED_LINES,
                    payload.enabling
                );
            }),
            this.wsBus.on('session:pro-toggled', function (payload) {
                if (!payload || payload.sessionId !== self.sessionId) return;
                self.speak(
                    payload.enabling ? MASCOT_PRO_ON_LINES : MASCOT_PRO_OFF_LINES,
                    payload.enabling
                );
            }),
            // 删会话：删哪一个都吭声（就爱凑这个热闹）
            this.wsBus.on('session:deleted', function () {
                self.speak(MASCOT_DELETED_LINES, true);
            }),
            // 新开一个对话：不抖（平和的开始，不是"被惊到"），只搭句话
            this.wsBus.on('session:created', function () {
                self.speak(MASCOT_NEW_LINES, false);
            })
        ];
    },

    beforeUnmount: function () {
        window.removeEventListener('keydown', this._onKeydown);
        if (this._unsub) {
            this._unsub.forEach(function (off) { if (off) off(); });
            this._unsub = null;
        }
        if (this._live) { this._live.destroy(); this._live = null; }
        clearTimeout(this._bubbleTimer);
        clearTimeout(this._toggleTimer);
    },

    methods: {
        /** 切换显隐并持久化（连按 b×10 / 长按发送键 3s 共用） */
        toggle: function () {
            if (this._toggleTimer) return;   // 进出场过渡中，忽略连点，避免状态打架
            var self = this;
            if (this.visible) {
                // 送走：先冒告别语，停顿一下再沉降收起
                this.show(this.pick(MASCOT_EXIT_LINES), 1500);
                this._toggleTimer = setTimeout(function () {
                    self._toggleTimer = null;
                    self.visible = false;
                    localStorage.setItem('assistant-mascot-visible', '0');
                    self.bubble = '';
                }, 1400);
            } else {
                // 召唤：先落下来，落稳后再冒欢迎语
                this.visible = true;
                localStorage.setItem('assistant-mascot-visible', '1');
                this._toggleTimer = setTimeout(function () {
                    self._toggleTimer = null;
                    self.show(self.pick(MASCOT_ENTER_LINES), 3400);
                }, 420);
            }
        },

        /** 从台词表里随机抽一条 */
        pick: function (lines) {
            if (!lines || !lines.length) return '';
            return lines[Math.floor(Math.random() * lines.length)];
        },

        /** 冒一句（可带一次哆嗦） */
        speak: function (lines, withPulse) {
            if (withPulse && this._live) this._live.pulse();
            if (!this.visible) return;
            this.show(this.pick(lines), 5200);
        },

        /** 冒气泡，几秒后自动收起 */
        show: function (text, durationMs) {
            var self = this;
            this.bubble = text || '';
            clearTimeout(this._bubbleTimer);
            this._bubbleTimer = setTimeout(function () {
                self.bubble = '';
            }, durationMs || 5000);
        },

        /**
         * 点一下熊：随机取一条问候语冒气泡，几秒后自动收起。
         * 文案直接复用新对话页那套接口（GET greeting/random），不另造一套话术；
         * 接口失败或没数据时退回一句兜底，绝不弹空气泡。
         */
        onClick: function () {
            var self = this;
            var BUBBLE_MS = 5000;
            var FALLBACK = '老爸，阳阳在这儿哈～';

            if (typeof API !== 'undefined' && API.greeting && API.greeting.random) {
                API.greeting.random().then(function (result) {
                    var text = (result && result.status === 200 && result.data) ? result.data.text : '';
                    self.show(text || FALLBACK, BUBBLE_MS);
                }).catch(function () {
                    self.show(FALLBACK, BUBBLE_MS);
                });
            } else {
                self.show(FALLBACK, BUBBLE_MS);
            }
        }
    }
};
