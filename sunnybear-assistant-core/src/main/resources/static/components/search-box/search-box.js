/**
 * 通用搜索输入框组件
 *
 * Props:
 *   modelValue  — String    搜索关键字（v-model 双向绑定）
 *   placeholder — String    占位提示文字，默认 '搜索'
 *   debounce    — Number    输入防抖延迟（毫秒），默认 300
 *   loading     — Boolean   是否正在搜索，为 true 时右侧显示加载 spinner
 *   maxLength   — Number    输入最大长度，默认 200
 *
 * Emits:
 *   update:modelValue — v-model 双向同步
 *   search            — 防抖结束后触发，携带当前关键字；清空时立即触发一次（携带空串）
 *
 * 用法示例：
 *   <search-box
 *       v-model="keyword"
 *       :loading="searching"
 *       placeholder="搜索..."
 *       @search="doSearch"
 *   ></search-box>
 *
 * 说明：lucide 会把 <i data-lucide> 替换为 <svg> 并重算 class，
 * 因此图标定位一律用内联 style；可点击图标外包一层普通 span 以保留事件。
 */
const SearchBox = {
    name: 'SearchBox',

    template: `
        <div class="search-box">
            <i data-lucide="search"
                style="position:absolute;left:10px;top:50%;transform:translateY(-50%);width:14px;height:14px;color:var(--text-muted,#909399);pointer-events:none"></i>
            <input ref="input" class="search-box-input"
                :value="modelValue"
                :placeholder="placeholder"
                :maxlength="maxLength"
                @input="onInput"
                @keyup.enter="onEnter" />
            <span v-if="loading" class="search-box-side">
                <i data-lucide="loader-circle"
                    style="width:14px;height:14px;animation:search-box-spin 1s linear infinite"></i>
            </span>
            <span v-else-if="modelValue" class="search-box-side is-clear" @click="clear">
                <i data-lucide="x" style="width:14px;height:14px"></i>
            </span>
        </div>
    `,

    props: {
        modelValue: { type: String, default: '' },
        placeholder: { type: String, default: '搜索' },
        debounce: { type: Number, default: 300 },
        loading: { type: Boolean, default: false },
        maxLength: { type: Number, default: 200 },
    },

    emits: ['update:modelValue', 'search'],

    methods: {
        onInput(event) {
            const value = event.target.value;
            this.$emit('update:modelValue', value);
            clearTimeout(this._debounceTimer);
            this._debounceTimer = setTimeout(() => {
                this.$emit('search', value);
            }, this.debounce);
        },

        /** 回车立即搜索，跳过剩余防抖 */
        onEnter(event) {
            clearTimeout(this._debounceTimer);
            this.$emit('search', event.target.value);
        },

        /** 清空并立即触发一次空搜索（用于恢复全量列表） */
        clear() {
            clearTimeout(this._debounceTimer);
            this.$emit('update:modelValue', '');
            this.$emit('search', '');
            this.$nextTick(() => {
                const input = this.$refs.input;
                if (input) input.focus();
            });
        }
    },

    beforeUnmount() {
        clearTimeout(this._debounceTimer);
    }
};
