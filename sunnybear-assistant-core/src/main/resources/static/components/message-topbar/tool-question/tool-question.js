/**
 * 结构化提问弹窗组件（自包含版）
 *
 * 后端 QuestionTool 通过 WebSocket 下发 ###TOOL_QUESTION### + ToolQuestion 对象：
 *   { id, toolName, message, timeout,
 *     questions: [ { key, q, multiple, options: [ { text, why } ] } ] }
 *
 * 本组件：
 *   - 平铺展示每道题：候选以 [✓] 勾选行展示；option.text 为主文案，option.why 为其下方小号淡色原因。
 *   - 单选/多选由题目 multiple 决定：单选再点取消，多选可勾选多个。
 *   - 勾选与自由输入并存、互不互斥：可在勾选候选之外再追加自定义输入，二者一起回传。
 *   - 每题至少勾选一项或输入非空才可提交（不可跳过）。
 *   - 支持并发：多个提问排队，逐个展示（前一个作答完成后自动展示下一个）。
 *   - 取消 / Esc / 点遮罩 → 以 cancelled=true 回传，表示用户想自然聊这个话题。
 *   - timeout 秒数 > 0 时展示倒计时，超时自动按取消回传。
 *
 * 关闭语义：点遮罩 / Esc / 右上角 X 为"收起"（不取消），待作答项保留在队列中，
 * 收起后由页面顶部的入口按钮（角标显示待作答数量）再次调 expand() 打开。
 * 收起状态下来的新提问不会自动展开，只累加角标；只有底部"取消·我想自由聊"才回传 cancelled=true。
 *
 * 作答状态统一存在组件顶层 data.answersByKey（keyed by 问题 key），
 * 形如 { selections: [], input: '' }，候选高亮/输入框都从这里读取，确保勾选与输入的视觉状态实时同步。
 *
 * Props:
 *   mainColor — String  主题色
 *
 * Injects:
 *   wsBus     — WebSocket 消息总线（app.provide('wsBus', WsBus)），用于订阅 ###TOOL_QUESTION###
 *
 * 公开方法（通过 ref 调用）：
 *   show(toolQuestion) — 入队并弹出结构化提问弹窗
 *   expand()           — 重新展开收起的弹窗（供顶部入口按钮调用）
 *
 * Emits:
 *   pending-change — 待作答数量变化时触发，参数为当前未处理数量（供父组件渲染入口角标）
 */
const ToolQuestion = {
    name: 'ToolQuestion',

    template: `
    <div v-if="visible" class="tq-overlay" :style="{'--main-color': mainColor}" @click.self="collapse">
      <div class="tq-dialog"
           tabindex="-1"
           ref="dialog"
           @keydown.esc="collapse"
           @keydown.enter.ctrl.prevent="submit">
        <!-- 头部 -->
        <div class="tq-header">
          <div class="tq-icon-wrap">
            <i data-lucide="message-circle-question"></i>
          </div>
          <div class="tq-header-text">
            <span class="tq-title">{{ title }}</span>
            <span v-if="hasTimeout" class="tq-countdown" :class="{'is-danger': countdown <= 3}">
              {{ countdown }} 秒后自动取消
            </span>
          </div>
          <button class="tq-close" title="收起（稍后回答）" @click="collapse">
            <i data-lucide="x"></i>
          </button>
        </div>

        <!-- 引导语 -->
        <div v-if="active && active.message" class="tq-message markdown-body"
             v-html="active.renderedMessage"></div>

        <!-- 题目列表（可滚动） -->
        <div class="tq-body" v-if="active">
          <div v-for="(item, idx) in active.items" :key="item.key" class="tq-question">
            <div class="tq-question-head">
              <span class="tq-question-index">{{ idx + 1 }}</span>
              <span class="tq-question-text">{{ item.q }}</span>
            </div>

            <!-- 候选列表（有则展示；[✓] 勾选，单选/多选由题目 multiple 决定） -->
            <div v-if="item.options && item.options.length" class="tq-options">
              <div v-for="(opt, oi) in item.options"
                   :key="oi"
                   class="tq-option"
                   :class="{'is-active': isChosen(item, opt)}"
                   :style="{'--main-color': mainColor}"
                   role="checkbox"
                   :aria-checked="isChosen(item, opt)"
                   tabindex="0"
                   @click="pickOption(item, opt)"
                   @keydown.space.prevent="pickOption(item, opt)"
                   @keydown.enter.prevent="pickOption(item, opt)">
                <span class="tq-option-check"></span>
                <span class="tq-option-body">
                  <span class="tq-option-text">{{ opt.text }}</span>
                  <span v-if="opt.why" class="tq-option-why">{{ opt.why }}</span>
                </span>
              </div>
            </div>

            <!-- 自由输入：与勾选并存，作为选项之外的补充 -->
            <div class="tq-custom">
              <input
                     class="tq-custom-input"
                     :class="{'has-text': isCustom(item)}"
                     type="text"
                     :value="customOf(item)"
                     :placeholder="(item.options && item.options.length) ? '可补充说明（可选）…' : '请输入你的回答…'"
                     @input="onCustomInput(item, $event.target.value)" />
            </div>
          </div>
        </div>

        <!-- 底部 -->
        <div class="tq-footer">
          <button class="tq-btn tq-btn-cancel" @click="cancel">
            <i data-lucide="message-circle" style="width:16px;height:16px"></i>
            <span>取消 · 我想自由聊</span>
          </button>
          <button class="tq-btn tq-btn-submit"
                  :disabled="!allAnswered || sending"
                  @click="submit"
                  :style="{'--main-color': mainColor}">
            <i data-lucide="send" style="width:16px;height:16px"></i>
            <span>{{ sending ? '提交中…' : '提交回答' }}</span>
          </button>
        </div>
      </div>
    </div>`,

    props: {
        mainColor: { type: String, default: 'lightsalmon' }
    },

    emits: ['pending-change'],

    inject: {
        // 可选注入：插件页未提供 wsBus 时降级为 null（仍可由父组件通过 ref 调用 show()）
        wsBus: { default: null }
    },

    data() {
        return {
            // 待展示的提问队列
            queue: [],
            // 当前正在展示的提问（含只读的题目展示数据）
            active: null,
            // 用户是否主动收起了弹窗：收起后待作答项保留，新提问不自动展开只累加角标
            collapsed: false,
            // 当前提问下每道题的作答状态（keyed by 问题 key）：{ chosen: 选中的候选, custom: 自由输入 }
            answersByKey: {},
            sending: false,
            // 倒计时状态
            countdown: 0,
            hasTimeout: false,
            _timer: null,
            titleMap: {
                'question_tool': 'AI 想先问你几个问题'
            }
        };
    },

    computed: {
        visible() {
            return this.active != null && !this.collapsed;
        },
        // 待作答数量 = 当前展示中的 1 个 + 队列中排队的
        pendingCount() {
            return (this.active ? 1 : 0) + this.queue.length;
        },
        title() {
            if (!this.active) return '';
            return this.titleMap[this.active.toolName] || '结构化提问';
        },
        // 所有题都有作答才算可提交（勾选至少一项 或 自由输入非空）
        allAnswered() {
            const a = this.active;
            if (!a) return false;
            return a.items.every(item => {
                const s = this.answersByKey[item.key];
                if (!s) return false;
                return (s.selections && s.selections.length > 0) || (s.input && s.input.trim());
            });
        }
    },

    watch: {
        // 待作答数量变化 → 通知父组件刷新顶部入口角标
        pendingCount(val) {
            this.$emit('pending-change', val);
        }
    },

    methods: {
        /* ---- 公开方法 ---- */
        show(toolQuestion) {
            // 同一 id 重复推送直接忽略
            if (this.queue.some(q => q.id === toolQuestion.id) ||
                (this.active && this.active.id === toolQuestion.id)) return;

            const tq = {
                id: toolQuestion.id,
                toolName: toolQuestion.toolName || 'question_tool',
                message: toolQuestion.message || '',
                renderedMessage: MarkdownUtils.render(toolQuestion.message || ''),
                timeout: Number(toolQuestion.timeout),
                hasTimeout: Number(toolQuestion.timeout) > 0,
                total: Number(toolQuestion.timeout) > 0 ? Math.round(Number(toolQuestion.timeout)) : 0,
                // 只读展示数据：候选勾选/自由输入的状态存 data.answersByKey
                items: (toolQuestion.questions || []).map(item => ({
                    key: item.key,
                    q: item.q || '',
                    multiple: !!item.multiple,
                    options: (item.options || []).map(opt => {
                        // 兼容旧格式：字符串候选 → { text, why:'' }
                        if (typeof opt === 'string') {
                            return { text: opt, why: '' };
                        }
                        return { text: opt.text || '', why: opt.why || '' };
                    })
                }))
            };
            this.queue.push(tq);
            // 新提问到达 → 响一声。和工具确认共用同一个音、同一套节流，
            // 两者几乎同时来时只会响一次，不会叠在一起吵。
            if (window.AlertSound) window.AlertSound.play();
            // 若当前没有展示中的提问 → 弹出第一个
            if (!this.active) {
                this.loadNext();
            }
        },

        // 重新展开收起的弹窗（顶部入口按钮调用）
        expand() {
            if (this.pendingCount === 0) return;
            this.collapsed = false;
            this.$nextTick(() => {
                this.focusDialog();
                this.refreshIcons();
            });
        },

        /* ---- 队列 ---- */
        loadNext() {
            if (this.active || this.queue.length === 0) return;
            this.active = this.queue.shift();
            this.sending = false;
            // 重置本题作答状态
            this.answersByKey = {};
            this.active.items.forEach(item => {
                this.answersByKey[item.key] = { selections: [], input: '' };
            });
            this.$nextTick(() => {
                this.focusDialog();
                this.refreshIcons();
                if (this.active.hasTimeout) {
                    this.startCountdown();
                }
            });
        },

        clearTimer() {
            if (this._timer) {
                clearInterval(this._timer);
                this._timer = null;
            }
        },

        startCountdown() {
            this.clearTimer();
            const a = this.active;
            if (!a || !a.hasTimeout) return;
            this.countdown = a.total;
            this._timer = setInterval(() => {
                this.countdown--;
                if (this.countdown <= 0) {
                    // 超时 → 按取消回传
                    this.clearTimer();
                    this.cancel();
                }
            }, 1000);
        },

        /* ---- 作答状态读取（模板统一走这里，保证视觉与数据一致） ---- */
        stateOf(item) {
            return this.answersByKey[item.key];
        },
        customOf(item) {
            const s = this.stateOf(item);
            return s ? s.input : '';
        },
        isCustom(item) {
            const s = this.stateOf(item);
            return !!(s && s.input && s.input.trim());
        },
        isChosen(item, opt) {
            const s = this.stateOf(item);
            return !!(s && s.selections && s.selections.indexOf(this.optText(opt)) >= 0);
        },
        optText(opt) {
            return typeof opt === 'string' ? opt : (opt && opt.text) || '';
        },

        /* ---- 交互 ---- */
        // 勾选候选：多选可任意增删；单选再点已选中的则取消，点其它则替换
        // 注意：勾选与自由输入互不干扰，可在勾选之外追加自输
        pickOption(item, opt) {
            const s = this.stateOf(item);
            if (!s) return;
            const text = this.optText(opt);
            if (!text) return;
            if (!s.selections) s.selections = [];
            const i = s.selections.indexOf(text);
            if (item.multiple) {
                if (i >= 0) {
                    s.selections.splice(i, 1);
                } else {
                    s.selections.push(text);
                }
            } else {
                s.selections = i >= 0 ? [] : [text];
            }
        },

        // 自由输入：与勾选并存，不再清空已勾选候选
        onCustomInput(item, value) {
            const s = this.stateOf(item);
            if (!s) return;
            s.input = value;
        },

        /* ---- 回传 ---- */
        // 收起：不取消，待作答项保留在队列中，可由顶部入口按钮重新展开
        collapse() {
            if (!this.active) return;
            this.collapsed = true;
        },

        // 取消：cancelled=true 回传（用户想自由聊），然后展示下一个
        cancel() {
            const a = this.active;
            if (!a || this.sending) return;
            this.clearTimer();
            this.postAnswer(a, true, []);
        },

        // 提交：校验每题已回答后回传答案
        submit() {
            const a = this.active;
            if (!a || this.sending) return;
            if (!this.allAnswered) {
                ElementPlus.ElMessage.warning('还有问题未回答，请完成后再提交');
                return;
            }
            this.clearTimer();
            const answers = a.items.map(item => {
                const s = this.stateOf(item) || {};
                return {
                    key: item.key,
                    selections: (s.selections || []).slice(),
                    input: (s.input || '').trim()
                };
            });
            this.postAnswer(a, false, answers);
        },

        postAnswer(active, cancelled, answers) {
            this.sending = true;
            const data = { id: active.id, cancelled };
            if (!cancelled) {
                data.answers = answers;
            }
            API.chat.question(data)
                .then(() => {
                    // 成功回传后关闭当前弹窗并展示下一个排队提问
                    this.resolveActive();
                })
                .catch(err => {
                    console.error('结构化提问回传失败:', err);
                    ElementPlus.ElMessage.error('回传失败，请重试或取消');
                    // 保持弹窗打开展示，允许用户重试 / 取消
                    this.sending = false;
                });
        },

        resolveActive() {
            this.clearTimer();
            this.active = null;
            this.answersByKey = {};
            this.sending = false;
            // 没有排队的提问了 → 复位收起态，下一次新提问可自动展开
            if (this.queue.length === 0) {
                this.collapsed = false;
            }
            this.$nextTick(() => this.loadNext());
        },

        /* ---- 其它 ---- */
        focusDialog() {
            const el = this.$refs.dialog;
            if (el) el.focus();
        },

        refreshIcons() {
            if (typeof lucide !== 'undefined') {
                this.$nextTick(() => lucide.createIcons());
            }
        }
    },

    updated() {
        this.refreshIcons();
    },

    mounted() {
        // 防御：父组件在组件挂载前入队的极端情况
        if (!this.active && this.queue.length > 0) {
            this.loadNext();
        }
        // 自行订阅 ###TOOL_QUESTION### 信号（wsBus 由 app.provide 注入）
        if (this.wsBus) {
            this._unsubToolQuestion = this.wsBus.on('TOOL_QUESTION', (payload) => {
                try {
                    this.show(JSON.parse(payload));
                } catch (e) {
                    console.error('解析 TOOL_QUESTION 失败:', e);
                }
            });
        }
    },

    beforeUnmount() {
        this.clearTimer();
        if (this._unsubToolQuestion) {
            this._unsubToolQuestion();
            this._unsubToolQuestion = null;
        }
    }
};
