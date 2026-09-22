/**
 * 世界观正文渲染器（正文渲染 hook，覆盖核心的 Markdown 整段渲染）。
 *
 * 群聊消息正文里可能含 <private ...> 私聊/独白标签与 <switch to:".."> 移交标签，
 * 需要拆成段落分别渲染（普通文本走 Markdown，私聊块折叠显示、可点击展开）。
 * 本组件通过 MessageAreaPlugins.registerTextRenderer(WorldPrivateText) 注册，
 * 注册后核心消息区的文本块全部改由本组件渲染。
 *
 * Props:
 *   content   — Object   content 对象
 *   msg       — Object   所属消息
 *   streaming — Boolean  是否流式（流式仅按普通文本渲染，避免分段抖动）
 *
 * Injects:
 *   messageAreaContext — 消息区共享上下文
 *   worldPage          — 页面级共享状态（possessName / 私聊展开态）
 *
 * 依赖全局：$md。
 */
const WorldPrivateText = {
    name: 'WorldPrivateText',

    template: `
    <template v-for="(seg, segIdx) in segments" :key="segIdx">
        <div v-if="seg.type === 'text'" class="markdown-body" v-html="renderSegment(seg, segIdx)"></div>
        <div v-else-if="seg.type === 'switch'" class="switch-chat-hint">↪ 将话头交给 {{ seg.to }}</div>
        <div v-else class="private-chat-block" :class="{ monologue: seg.monologue }"
             @click.stop="togglePrivate(segIdx, seg)">
            <div class="private-chat-block-header">
                <span class="private-chat-lock">🔒</span>
                <span class="private-chat-meta">{{ seg.monologue ? ('独白 · ' + seg.from) : ('私聊 · ' + seg.from + ' → ' + seg.to) }}</span>
                <span class="private-chat-chevron" :class="{ expanded: isExpanded(segIdx, seg) }">▾</span>
            </div>
            <div v-if="isExpanded(segIdx, seg)" class="private-chat-block-content markdown-body"
                 v-html="renderSegment(seg, segIdx)"></div>
        </div>
    </template>`,

    props: {
        content: { type: Object, required: true },
        msg: { type: Object, default: null },
        streaming: { type: Boolean, default: false }
    },

    inject: {
        messageAreaContext: { required: true },
        worldPage: { required: true }
    },

    computed: {
        /** 拆分后的段落数组（流式期间不分段，整体按文本渲染，避免每帧重排） */
        segments: function () {
            if (this.streaming) {
                return [{ type: 'text', text: this.content.content || '' }];
            }
            return this.privateSegments(this.content.content || '');
        }
    },

    methods: {
        /**
         * 拆分消息文本中的私聊标签段，返回 [{type:'text'|'private'|'switch', text, from?, to?, monologue?}]
         * 私聊标签 <private from="发送者" to="接收者">内容</private>：属性顺序任意、to 可省略（省略=内心独白）。
         * 未闭合/无标签时整体按普通文本处理。
         */
        privateSegments(content) {
            if (!content || (!content.includes('<private') && !content.includes('<switch'))) {
                return [{ type: 'text', text: content || '' }];
            }
            const segs = [];
            const re = /<private\b([^>]*)>([\s\S]*?)<\/private>|<switch\s+to\s*[:=]\s*["']([^"']*)["']\s*\/?>/g;
            let last = 0;
            let m;
            while ((m = re.exec(content)) !== null) {
                if (m.index > last) {
                    segs.push({ type: 'text', text: content.slice(last, m.index) });
                }
                if (m[1] !== undefined) {
                    const attrs = m[1];
                    const from = ((attrs.match(/\bfrom\s*[:=]\s*["']([^"']*)["']/i)) || [])[1] || '';
                    const to = ((attrs.match(/\bto\s*[:=]\s*["']([^"']*)["']/i)) || [])[1] || '';
                    segs.push({ type: 'private', from: from.trim(), to: to.trim(), text: m[2], monologue: !to.trim() });
                } else {
                    segs.push({ type: 'switch', to: (m[3] || '').trim() });
                }
                last = m.index + m[0].length;
            }
            if (last < content.length) {
                segs.push({ type: 'text', text: content.slice(last) });
            }
            return segs;
        },

        /** 私聊块是否展开（用户手动 > 默认规则） */
        isExpanded(segIdx, seg) {
            const key = (this.msg ? this.msg.id : '') + '_' + segIdx;
            const st = this.worldPage.expandedPrivate;
            if (st && st[key] !== undefined) return st[key];
            return this.defaultExpanded(seg);
        },

        /** 默认展开规则：玩家未夺舍则全部展开；夺舍时仅当夺舍角色参与其中才展开 */
        defaultExpanded(seg) {
            if (!seg || seg.type !== 'private') return false;
            const possess = this.worldPage.possessName;
            if (!possess) return true;
            const participants = ((seg.from || '') + ',' + (seg.to || ''))
                .split(/[,\s，、;；]+/)
                .filter(Boolean);
            return participants.includes(possess);
        },

        /** 切换私聊块展开/收起 */
        togglePrivate(segIdx, seg) {
            const key = (this.msg ? this.msg.id : '') + '_' + segIdx;
            const st = this.worldPage.expandedPrivate;
            st[key] = !this.isExpanded(segIdx, seg);
        },

        /** 段文本 Markdown 渲染（含 mermaid 时让 ctx.mermaidNonce 触发重算） */
        renderSegment(seg, segIdx) {
            void this.messageAreaContext.mermaidNonce;
            if (seg.type === 'private' || seg.type === 'text') {
                return this.$md.render(seg.text || '');
            }
            return '';
        }
    },

    updated: function () {
        if (typeof lucide !== 'undefined' && this.$el) lucide.createIcons({ root: this.$el });
    }
};
