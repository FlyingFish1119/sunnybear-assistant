/**
 * 工具消息气泡组件（msg.role === 'tool'）。
 *
 * 负责工具消息折叠头（工具名 · 时间 + 聚合执行状态）与折叠后的结果内容
 * （结果 JSON 成功/失败徽标、追加展示文本块、附件）。
 * 状态与动作全部来自注入的共享上下文，组件只管渲染。
 *
 * Injects:
 *   sessionStore       — 会话/消息仓库
 *   messageAreaContext — 消息区共享上下文
 *
 * 依赖全局：$md、$fileUrl。
 */

const MessageAreaTool = {
    name: 'MessageAreaTool',

    inject: {
        sessionStore: { required: true },
        messageAreaContext: { required: true }
    },

    props: {
        msg: { type: Object, required: true }
    },

    template: `
    <div class="message-area-bubble tool-bubble">
        <div class="message-area-bubble-meta tool-meta-header" @click="ctx.actions.toggleCollapse(msg.id, 'tool')">
            <i :class="['tool-chevron', { collapsed: ctx.actions.isCollapsed(msg.id, 'tool') }]" style="width: 14px; height: 14px" data-lucide="chevron-right"></i>
            <span>{{ msg.name }} · {{ msg.createTime }}</span>
            <span class="tool-status-inline" :style="{color: toolStatus.color}">
                <i style="width: 12px; height: 12px" :data-lucide="toolStatus.icon"></i>
                <span style="padding-left: 2px">{{ toolStatus.text }}</span>
            </span>
        </div>
        <div :class="['collapsible-content', { collapsed: ctx.actions.isCollapsed(msg.id, 'tool') }]" :data-collapse-key="msg.id + '_tool'">
            <div v-for="(content, idx) in msg.contents" :key="idx" class="message-area-content-block">
                <div v-if="content.type === 'text'">
                    <template v-if="msg.extension && msg.extension.status === 'executing'">
                        <div class="message-area-bubble-tools-meta">
                            <i style="width: 12px; height: 12px; color: #faad14" data-lucide="circle-dot"></i>
                            <div style="padding: 0 0 1px 4px; color: #faad14">[{{msg.name}}]执行中…</div>
                        </div>
                    </template>
                    <!-- 工具结果块：contents[0] 的结果 JSON，只有它能判成功/失败 -->
                    <template v-else-if="toolParsed(content)">
                        <span v-if="toolParsed(content).succeed" class="message-area-bubble-tools-meta">
                            <i style="width: 12px; height: 12px; color: #52c41a" data-lucide="circle-check"></i>
                            <span style="padding: 0 0 1px 4px; color: #52c41a">[{{msg.name}}]执行成功</span>
                        </span>
                        <div v-else class="message-area-bubble-tools-meta">
                            <i style="width: 12px; height: 12px; color: #ff4d4f" data-lucide="circle-x"></i>
                            <div style="padding: 0 0 1px 4px; color: #ff4d4f">[{{msg.name}}]执行失败</div>
                        </div>
                        <div class="markdown-body" v-html="renderToolResult(content)"></div>
                    </template>
                    <!-- 追加的展示文本块（后台任务通知等）：不是工具结果 JSON，只按纯文本渲染 -->
                    <div v-else class="markdown-body" v-html="renderContent(content)"></div>
                </div>
                <div v-else-if="content.type === 'image'" class="message-attachment-image">
                    <img :src="$fileUrl.proxy(content.url)" @click.stop="$fileUrl.previewImage(content.url)" />
                </div>
                <div v-else-if="content.type === 'audio'" class="message-attachment-audio">
                    <audio :src="$fileUrl.proxy(content.url)" controls preload="metadata"></audio>
                </div>
                <div v-else-if="content.type === 'video'" class="message-attachment-video">
                    <video :src="$fileUrl.proxy(content.url)" controls preload="metadata"></video>
                </div>
                <div v-else-if="content.type === 'file'" class="message-attachment-file">
                    <a :href="$fileUrl.proxy(content.url)" target="_blank">
                        <i data-lucide="file" style="width:18px;height:18px"></i>
                        <span>{{ $fileUrl.fileName(content.url) }}</span>
                    </a>
                </div>
            </div>
        </div>
        <!-- 气泡末尾插件槽（锚点 'message-bubble'，插件自行按 msg 判断是否渲染） -->
        <div v-if="bubbleSlots.length" class="message-area-plugin-slots">
            <component v-for="(slot, si) in bubbleSlots"
                       :key="'plugin-bubble-' + si"
                       :is="slot.component"
                       :msg="msg"></component>
        </div>
    </div>`,

    computed: {
        ctx: function () {
            return this.messageAreaContext;
        },
        /** 气泡末尾插件槽（锚点 'message-bubble'） */
        bubbleSlots: function () {
            return this.ctx.actions.slotsFor('message-bubble');
        },
        /** 工具消息的聚合执行状态（折叠头显示成功/失败图标和颜色） */
        toolStatus: function () {
            const msg = this.msg;
            if (msg.extension && msg.extension.status === 'executing') {
                return { succeed: true, icon: 'circle-dot', color: '#faad14', text: '执行中…' };
            }
            if (!msg.contents || msg.contents.length === 0) {
                return { succeed: true, icon: 'circle-check', color: '#52c41a', text: '执行成功' };
            }
            let allSucceed = true;
            for (let content of msg.contents) {
                if (content.type !== 'text') continue;
                let parsed = this.toolParsed(content);
                if (!parsed) continue;
                if (!parsed.succeed) {
                    allSucceed = false;
                    break;
                }
            }
            return allSucceed
                ? { succeed: true, icon: 'circle-check', color: '#52c41a', text: '执行成功' }
                : { succeed: false, icon: 'circle-x', color: '#ff4d4f', text: '执行失败' };
        }
    },

    methods: {
        /**
         * 带缓存的工具结果 JSON 解析（模板中使用）。
         * 避免每次更新都对同一 content 重复 JSON.parse。
         *
         * 工具消息的 contents[0] 是工具结果 JSON；其后的文本块是追加的展示内容
         * （后台任务完成通知等），不是 JSON。解析不出来一律返回 null，交给模板按
         * 纯文本渲染——这里绝不能抛：渲染期异常会让 Vue 把整个消息区替换成空节点。
         * @param {object} content - 消息的 content 对象
         * @returns {object|null} 解析后的工具结果；不是工具结果 JSON 时返回 null
         */
        toolParsed(content) {
            let raw = content.content;
            if (content._rawJson !== raw) {
                content._rawJson = raw;
                try {
                    content._parsed = raw ? JSON.parse(raw) : null;
                } catch (e) {
                    content._parsed = null;
                }
            }
            return content._parsed || null;
        },

        /** 追加展示文本块 Markdown（缓存到 content 对象的 text 槽） */
        renderContent(content) {
            void this.ctx.mermaidNonce;
            return memoMsgHtml(content, 'text', content.content, this.$md.render);
        },

        /** 工具结果 Markdown（缓存到 content 对象的 tool 槽） */
        renderToolResult(content) {
            void this.ctx.mermaidNonce;
            const parsed = this.toolParsed(content);
            return memoMsgHtml(content, 'tool', parsed ? parsed.result : '', this.$md.render);
        },

        /** 合并同一帧内的多次图标刷新 */
        scheduleIconRefresh() {
            if (this._iconRefreshPending) return;
            this._iconRefreshPending = true;
            requestAnimationFrame(() => {
                this._iconRefreshPending = false;
                if (typeof lucide !== 'undefined' && this.$el) {
                    lucide.createIcons({ root: this.$el });
                }
            });
        }
    },

    updated: function () {
        this.scheduleIconRefresh();
    }
};
