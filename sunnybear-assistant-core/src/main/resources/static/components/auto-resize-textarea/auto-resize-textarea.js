/**
 * 自动调整高度的文本域组件
 *
 * Props:
 *   modelValue  — String    v-model 绑定的文本内容
 *   placeholder — String    占位提示文字
 *   mainColor   — String    hover/focus 时左边框高亮色，注入为 --main-color
 *   maxHeight   — Number    最大高度（px），默认 180
 *   minHeight   — Number    最小高度（px），默认 85
 *
 * Emits:
 *   update:modelValue — v-model 双向同步
 *   submit            — Ctrl+Enter 时触发
 *   cancel            — Esc 时触发
 *   keydown           — 透传原始 keydown 事件
 *
 * 用法示例：
 *   <auto-resize-textarea
 *       class="send-area-textarea"
 *       :main-color="mainColor"
 *       v-model="inputText"
 *       placeholder="输入消息"
 *       :max-height="180"
 *       :min-height="85"
 *       @submit="sendMessage"
 *   ></auto-resize-textarea>
 */
const AutoResizeTextarea = {
    name: 'AutoResizeTextarea',

    template: `
        <textarea
            class="auto-resize-textarea"
            :style="{ '--main-color': mainColor }"
            :value="modelValue"
            :placeholder="placeholder"
            @input="onInput"
            @keydown="onKeydown"
        ></textarea>
    `,

    props: {
        modelValue: { type: String, default: '' },
        placeholder: { type: String, default: '' },
        mainColor: { type: String, default: 'lightsalmon' },
        maxHeight: { type: Number, default: 180 },
        minHeight: { type: Number, default: 85 },
    },

    emits: ['update:modelValue', 'submit', 'cancel', 'keydown'],

    methods: {
        onInput: function (event) {
            this.$emit('update:modelValue', event.target.value);
            this.resize(event.target);
        },

        onKeydown: function (event) {
            // Ctrl+Enter（不含 Shift/Alt/Meta）→ 提交
            if (event.key === 'Enter' && event.ctrlKey && !event.shiftKey && !event.altKey && !event.metaKey) {
                event.preventDefault();
                this.$emit('submit', event);
                return;
            }
            // Esc（不含任何修饰键）→ 取消
            if (event.key === 'Escape' && !event.ctrlKey && !event.shiftKey && !event.altKey && !event.metaKey) {
                event.preventDefault();
                this.$emit('cancel', event);
                return;
            }
            // 透传其他按键事件
            this.$emit('keydown', event);
        },

        /**
         * 根据内容自适应高度，限制在 [minHeight, maxHeight] 区间
         */
        resize: function (el) {
            el.style.height = '0px';
            var scrollH = el.scrollHeight;
            el.style.height = Math.min(Math.max(scrollH, this.minHeight), this.maxHeight) + 'px';
        }
    },

    watch: {
        modelValue: function () {
            var self = this;
            this.$nextTick(function () {
                self.resize(self.$el);
            });
        }
    },

    mounted: function () {
        var self = this;
        this.$nextTick(function () {
            self.resize(self.$el);
        });
    }
};
