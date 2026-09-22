/**
 * 新对话落地页组件（Hero）
 *
 * 从 message-area 拆出：空会话时的「头像 + 问候 + 建议提问」整块。
 * 问候语与建议提问由本组件自行向 API.greeting.random() 请求，父级只需
 * 提供主题色、助手头像 URL 与助手名。
 *
 * 父级用 v-if 控制挂载，因此「每次进入新对话刷新问候语」= 组件重新挂载，
 * 不需要额外的 watch。
 *
 * Props:
 *   mainColor      — String  主题色
 *   avatar         — String  助手头像 URL（父级已解析好，本地文件已走代理）
 *   assistantName  — String  助手名（用于副标题；为空时用通用引导语）
 *
 * Injects:
 *   wsBus          — 可选本地事件总线（点击建议提问时发 'send-area:fill'）
 *
 * 依赖全局：API、lucide。
 */

/** 建议提问的图标：后端只回文本，前端按顺序轮换配图标 */
const HERO_SUGGESTION_ICONS = ['pen-line', 'file-text', 'lightbulb', 'compass'];

const MessageAreaHero = {
    name: 'MessageAreaHero',

    template: `
    <div class="msg-hero" :style="{'--main-color': mainColor}">
        <div class="msg-hero-avatar">
            <img v-if="showAvatar" :src="avatar" alt="" @error="avatarError = true">
            <i v-else :data-lucide="greetingIcon" class="msg-hero-avatar-icon"></i>
        </div>
        <p class="msg-hero-greeting">{{ greetingText || '你好，我能帮你做点什么？' }}</p>
        <p class="msg-hero-subtitle">{{ subtitle }}</p>
        <div class="msg-hero-suggestions">
            <button v-for="(item, index) in suggestions"
                    :key="index"
                    class="msg-hero-suggestion"
                    @click="useSuggestion(item.text)">
                <i :data-lucide="item.icon" class="msg-hero-suggestion-icon"></i>
                <span>{{ item.text }}</span>
            </button>
        </div>
    </div>
    `,

    props: {
        mainColor:     { type: String, default: 'lightsalmon' },
        avatar:        { type: String, default: '' },
        assistantName: { type: String, default: '' }
    },

    inject: {
        // 可选：本地事件总线（用于把建议提问填进发送框）
        wsBus: { default: null }
    },

    data: function () {
        return {
            // 空会话问候语（组件自行请求）
            greetingText: '',
            // 「建议提问」：点击填入发送框
            suggestions: [
                { icon: 'pen-line',     text: '帮我写一封得体的邮件' },
                { icon: 'file-text',    text: '总结这份文档的核心要点' },
                { icon: 'lightbulb',    text: '用简单的话解释一个概念' },
                { icon: 'calendar-check', text: '帮我制定一周的作息计划' }
            ],
            // 头像加载失败兜底：失败后回退到时段图标
            avatarError: false
        };
    },

    computed: {
        showAvatar: function () {
            return !!this.avatar && !this.avatarError;
        },
        // 按当前时段挑一个图标作为头像兜底
        greetingIcon: function () {
            const hour = new Date().getHours();
            if (hour >= 6 && hour < 12) return 'sunrise';
            if (hour >= 12 && hour < 18) return 'sun';
            if (hour >= 18 && hour < 22) return 'sunset';
            return 'moon';
        },
        // 副标题：带上助理名，无则用通用引导
        subtitle: function () {
            return this.assistantName
                ? ('和 ' + this.assistantName + ' 聊点什么，或试试下面的问题')
                : '试试下面的问题，或直接输入你的想法';
        }
    },

    methods: {
        /** 问候语 + 建议提问：挂载时自行请求，失败时保留默认文案 */
        async fetchGreeting() {
            try {
                const result = await API.greeting.random();
                if (result.status === 200 && result.data) {
                    this.greetingText = result.data.text;
                    const list = result.data.suggestions;
                    if (Array.isArray(list) && list.length > 0) {
                        // 去重：AI 可能生成重复建议，重复的 v-for key 会导致 DOM 插入异常
                        const unique = [];
                        const seen = {};
                        list.forEach(function (text) {
                            const key = String(text);
                            if (key && !seen[key]) {
                                seen[key] = true;
                                unique.push(text);
                            }
                        });
                        this.suggestions = unique.map(function (text, i) {
                            return { icon: HERO_SUGGESTION_ICONS[i % HERO_SUGGESTION_ICONS.length], text: text };
                        });
                    }
                } else {
                    this.greetingText = '你好！今天有什么可以帮你的吗？';
                }
            } catch (error) {
                console.error('获取问候语失败:', error);
            }
        },

        /** 点击建议提问：通过本地事件把文案填进发送框（由 send-area 接收并聚焦） */
        useSuggestion(text) {
            if (this.wsBus) {
                this.wsBus.emit('send-area:fill', text);
            }
        }
    },

    mounted: function () {
        this.fetchGreeting();
        if (typeof lucide !== 'undefined') lucide.createIcons({ root: this.$el });
    },

    updated: function () {
        // 建议提问是异步回来的，父级不会因此重渲染，图标得自己补刷
        if (typeof lucide !== 'undefined') lucide.createIcons({ root: this.$el });
    }
};
