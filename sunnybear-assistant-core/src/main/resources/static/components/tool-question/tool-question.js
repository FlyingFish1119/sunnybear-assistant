/**
 * 结构化提问弹窗组件（自包含版）
 *
 * 后端 QuestionTool 通过 WebSocket 下发 ###TOOL_QUESTION### + ToolQuestion 对象：
 *   { id, toolName, message, timeout, questions: [ { key, q, options[] } ] }
 *
 * 本组件：
 *   - 平铺展示每道题：有候选则以"胶囊按钮"单选点选；每道题同时提供自由输入框。
 *   - 单选与自输互斥、自输优先：点候选 → 选中并清空自输框；输入内容 → 取消候选高亮。
 *   - 所有题都有回答后才能提交（单选或自输，不可跳过）。
 *   - 支持并发：多个提问排队，逐个展示（前一个作答完成后自动展示下一个）。
 *   - 取消 / Esc / 点遮罩 → 以 cancelled=true 回传，表示用户想自然聊这个话题。
 *   - timeout 秒数 > 0 时展示倒计时，超时自动按取消回传。
 *
 * 作答状态统一存在组件顶层 data.answersByKey（keyed by 问题 key），
 * 候选高亮/输入框都从这里读取，确保点选与输入的视觉状态实时同步。
 *
 * Props:
 *   mainColor — String  主题色
 *
 * 公开方法（通过 ref 调用）：
 *   show(toolQuestion) — 入队并弹出结构化提问弹窗
 */
const ToolQuestion = {
    name: 'ToolQuestion',

    template: `
    <div v-if="visible" class="tq-overlay" :style="{'--main-color': mainColor}" @click.self="cancel">
      <div class="tq-dialog"
           tabindex="-1"
           ref="dialog"
           @keydown.esc="cancel"
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
          <button class="tq-close" title="取消提问（自由交谈）" @click="cancel">
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

            <!-- 候选胶囊（有则展示，单选点选） -->
            <div v-if="item.options && item.options.length" class="tq-options">
              <button v-for="opt in item.options"
                      class="tq-option"
                      :class="{'is-active': isChosen(item, opt)}"
                      :style="isChosen(item, opt) ? {'--main-color': mainColor} : {}"
                      type="button"
                      @click="pickOption(item, opt)">
                <span>{{ opt }}</span>
              </button>
            </div>

            <!-- 自由输入框 -->
            <div class="tq-custom">
              <input
                     class="tq-custom-input"
                     :class="{'has-text': isCustom(item)}"
                     type="text"
                     :value="customOf(item)"
                     :placeholder="(item.options && item.options.length) ? '或输入自定义回答…' : '请输入你的回答…'"
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

    emits: [],

    data() {
        return {
            // 待展示的提问队列
            queue: [],
            // 当前正在展示的提问（含只读的题目展示数据）
            active: null,
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
            return this.active != null;
        },
        title() {
            if (!this.active) return '';
            return this.titleMap[this.active.toolName] || '结构化提问';
        },
        // 所有题都有回答才算可提交（点选候选 或 自定义输入非空）
        allAnswered() {
            const a = this.active;
            if (!a) return false;
            return a.items.every(item => {
                const s = this.answersByKey[item.key];
                if (!s) return false;
                return (s.chosen && s.chosen.trim()) || (s.custom && s.custom.trim());
            });
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
                // 只读展示数据：候选点选/自由输入的状态存 data.answersByKey
                items: (toolQuestion.questions || []).map(item => ({
                    key: item.key,
                    q: item.q || '',
                    options: item.options || []
                }))
            };
            this.queue.push(tq);
            // 若当前没有展示中的提问 → 弹出第一个
            if (!this.active) {
                this.loadNext();
            }
        },

        /* ---- 队列 ---- */
        loadNext() {
            if (this.active || this.queue.length === 0) return;
            this.active = this.queue.shift();
            this.sending = false;
            // 重置本题作答状态
            this.answersByKey = {};
            this.active.items.forEach(item => {
                this.answersByKey[item.key] = { chosen: '', custom: '' };
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
            return s ? s.custom : '';
        },
        isCustom(item) {
            const s = this.stateOf(item);
            return !!(s && s.custom && s.custom.trim());
        },
        isChosen(item, opt) {
            const s = this.stateOf(item);
            return !!(s && s.chosen && s.chosen === opt);
        },

        /* ---- 交互 ---- */
        // 点选候选：再次点已选中的候选 → 取消选中；点其它候选 → 选中并清空自输框
        pickOption(item, opt) {
            const s = this.stateOf(item);
            if (!s) return;
            if (s.chosen === opt) {
                s.chosen = '';
            } else {
                s.chosen = opt;
                s.custom = '';
            }
        },

        // 自由输入：有内容则视为自定义回答（取消候选高亮）
        onCustomInput(item, value) {
            const s = this.stateOf(item);
            if (!s) return;
            s.custom = value;
            if (value && value.trim()) {
                s.chosen = '';
            }
        },

        // 该题的有效作答：自输优先于点选
        answerOf(item) {
            const s = this.stateOf(item);
            if (!s) return '';
            const custom = s.custom && s.custom.trim();
            return custom ? custom : (s.chosen || '');
        },

        /* ---- 回传 ---- */
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
            const answers = a.items.map(item => ({
                key: item.key,
                answer: this.answerOf(item)
            }));
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
    },

    beforeUnmount() {
        this.clearTimer();
    }
};
