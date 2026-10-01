/**
 * 定时任务面板 —— 单层抽屉（任务列表 + 就地展开执行会话）
 *
 * 与知识库 / 记忆 / 终端同一套门户：导航轨「定时任务」按钮 → 贴左滑出的抽屉。
 * 数据流（两级）：
 *   1) 打开时 GET /cron-job/list 列出所有定时任务；
 *   2) 点某个任务的展开箭头，按需懒加载 GET /cron-job/sessions?cronId= 拉该任务的历史执行会话；
 *   3) 点某条执行会话 → sessionStore.selectSession(session) 在消息区打开，并收起面板。
 *
 * 任务的增删改仍走设置页（定时任务设置），本面板只负责「看 + 进入会话」。
 *
 * Props:
 *   mainColor — String  主题色
 *
 * 公开方法（与其它导轨面板同一约定）：
 *   toggle() — 开 / 关整个面板
 *   open()   — 打开并刷新任务列表
 *   close()  — 关闭面板
 */
const CronPanel = {
    name: 'CronPanel',

    template: `
    <div v-if="visible" class="cp-overlay" @click.self="close">
        <aside class="cp-drawer">
            <div class="cp-head">
                <div class="cp-title">
                    <i data-lucide="clock"></i>
                    <span class="cp-title-text">定时任务</span>
                    <span v-if="jobs.length" class="cp-count">{{ jobs.length }}</span>
                </div>
                <button class="cp-btn" title="刷新" @click="refreshJobs()">
                    <i data-lucide="refresh-cw"></i>
                </button>
                <button class="cp-btn" title="关闭" @click="close">
                    <i data-lucide="x"></i>
                </button>
            </div>

            <div class="cp-body">
                <div v-if="jobsLoading" class="cp-hint">加载中…</div>
                <div v-else-if="jobs.length === 0" class="cp-empty">
                    还没有定时任务<br>去「设置 → 定时任务」添加
                </div>
                <template v-else>
                    <div v-for="job in jobs"
                         :key="job.id"
                         class="cp-job"
                         :class="{ 'is-open': expandedJobId === job.id }">
                        <!-- 任务行：点整行展开 / 收起 -->
                        <div class="cp-job-head" @click="toggleExpand(job)">
                            <span class="cp-job-caret">
                                <i :data-lucide="expandedJobId === job.id ? 'chevron-down' : 'chevron-right'"></i>
                            </span>
                            <span class="cp-job-main">
                                <span class="cp-job-title-row">
                                    <span class="cp-job-title">{{ job.title }}</span>
                                    <span v-if="job.enablePro" class="cp-badge is-pro">Pro</span>
                                    <span v-if="job.unreviewed" class="cp-badge is-unreviewed">无审查</span>
                                </span>
                                <span class="cp-job-sub">
                                    <code class="cp-cron">{{ job.cron }}</code>
                                    <span v-if="job.description" class="cp-job-desc">{{ job.description }}</span>
                                </span>
                            </span>
                        </div>

                        <!-- 展开区：该任务的执行会话列表 -->
                        <div v-if="expandedJobId === job.id" class="cp-runs">
                            <div v-if="pageOf(job.id).loading" class="cp-hint">加载执行记录…</div>
                            <div v-else-if="pageOf(job.id).list.length === 0" class="cp-hint">该任务还没有执行记录</div>
                            <template v-else>
                                <div v-for="s in pageOf(job.id).list"
                                     :key="s.id"
                                     class="cp-run"
                                     @click="openSession(s)">
                                    <span class="cp-run-icon"><i data-lucide="message-square"></i></span>
                                    <span class="cp-run-body">
                                        <span class="cp-run-time">{{ formatTime(s.updateTime) }}</span>
                                        <span v-if="sessionTokenTotal(s) != null" class="cp-run-token">
                                            {{ formatTokens(sessionTokenTotal(s)) }} tokens
                                        </span>
                                    </span>
                                    <span class="cp-run-actions" @click.stop>
                                        <button class="cp-run-btn is-danger"
                                                title="删除这次记录"
                                                @click="deleteRun(job.id, s)">
                                            <i data-lucide="trash-2"></i>
                                        </button>
                                    </span>
                                    <span class="cp-run-go"><i data-lucide="arrow-right"></i></span>
                                </div>
                                <button v-if="pageOf(job.id).hasMore"
                                        class="cp-more"
                                        :disabled="pageOf(job.id).loadingMore"
                                        @click.stop="loadSessions(job.id, true)">
                                    {{ pageOf(job.id).loadingMore ? '加载中…' : '加载更多' }}
                                </button>
                            </template>
                        </div>
                    </div>
                </template>
            </div>
        </aside>
    </div>

    <confirm-dialog ref="confirmDialog" :main-color="mainColor"></confirm-dialog>
    `,

    props: {
        mainColor: { type: String, default: '' }
    },

    emits: ['visible-change'],

    inject: {
        // 可选注入：插件页无 store 时降级为只读列表
        sessionStore: { default: null }
    },

    data() {
        return {
            visible: false,
            jobs: [],
            jobsLoading: false,
            /** 当前展开的任务 id（同一时刻只展开一个） */
            expandedJobId: null,
            /** cronId → { list, hasMore, loading, loadingMore } */
            sessionPages: {}
        };
    },

    watch: {
        visible(val) {
            this.$emit('visible-change', val);
        }
    },

    methods: {
        /* ==================== 开合 ==================== */

        toggle() {
            if (this.visible) {
                this.close();
            } else {
                this.open();
            }
        },

        async open() {
            this.visible = true;
            await this.refreshJobs();
        },

        close() {
            this.visible = false;
        },

        /* ==================== 任务列表 ==================== */

        async refreshJobs(silent) {
            this.jobsLoading = true;
            try {
                const res = await API.cronJob.list();
                if (res.status === 200) {
                    this.jobs = res.data || [];
                    // 已展开的任务若在别处被删了，顺手收起
                    if (this.expandedJobId != null
                        && !this.jobs.some(j => j.id === this.expandedJobId)) {
                        this.expandedJobId = null;
                    }
                    // 刷新任务列表时丢弃执行会话缓存，让展开区重新拉取（能看到新触发的执行）
                    this.sessionPages = {};
                    if (this.expandedJobId != null) {
                        const id = this.expandedJobId;
                        this.sessionPages[id] = { list: [], hasMore: false, loading: false, loadingMore: false };
                        this.loadSessions(id);
                    }
                } else if (!silent && window.SbToast) {
                    window.SbToast.error(res.message || '获取定时任务失败');
                }
            } catch (e) {
                if (!silent && window.SbToast) window.SbToast.error('获取定时任务失败: ' + e.message);
            } finally {
                this.jobsLoading = false;
            }
        },

        /* ==================== 展开 / 执行会话 ==================== */

        toggleExpand(job) {
            if (this.expandedJobId === job.id) {
                this.expandedJobId = null;
                return;
            }
            this.expandedJobId = job.id;
            // 首次展开才拉取；已缓存则不重复请求，刷新任务列表也不清缓存
            if (!this.sessionPages[job.id]) {
                this.sessionPages[job.id] = { list: [], hasMore: false, loading: false, loadingMore: false };
                this.loadSessions(job.id);
            }
        },

        /**
         * 拉取任务的执行会话。more=true 时以最后一条 (updateTime, id) 作游标续拉。
         */
        async loadSessions(cronId, more) {
            const page = this.sessionPages[cronId];
            if (!page || page.loading || page.loadingMore) return;
            if (more && !page.hasMore) return;
            const last = more && page.list.length ? page.list[page.list.length - 1] : null;
            if (more) {
                page.loadingMore = true;
            } else {
                page.loading = true;
            }
            try {
                const res = await API.cronJob.sessions(
                    cronId, 50, last && last.updateTime, last && last.id);
                if (res.status === 200 && res.data) {
                    const list = res.data.list || [];
                    page.list = more ? page.list.concat(list) : list;
                    page.hasMore = !!res.data.hasMore;
                } else if (window.SbToast) {
                    window.SbToast.error(res.message || '获取执行记录失败');
                }
            } catch (e) {
                if (window.SbToast) window.SbToast.error('获取执行记录失败: ' + e.message);
            } finally {
                page.loading = false;
                page.loadingMore = false;
            }
        },

        /** 取某任务的执行会话分页状态（未加载时给一个空壳，避免模板里判空） */
        pageOf(cronId) {
            return this.sessionPages[cronId]
                || { list: [], hasMore: false, loading: false, loadingMore: false };
        },

        /** 点执行会话：在消息区打开该会话，并收起面板 */
        openSession(session) {
            if (!session || !this.sessionStore) return;
            if (this.sessionStore.sessionSelectLoading) return;
            this.sessionStore.selectSession(session);
            this.close();
        },

        /**
         * 删除某条执行会话（会话及其消息）。先弹确认，再委托 sessionStore 删除，
         * 成功后从展开列表移除；若删的正是当前正在看的会话，store 会顺带清空消息区。
         */
        async deleteRun(cronId, session) {
            const dialog = this.$refs.confirmDialog;
            if (!dialog) return;
            try {
                await dialog.show({
                    title: '删除执行记录',
                    message: '确定要删除这次执行记录吗？该会话及其消息将不可恢复。',
                    confirmText: '删除',
                    cancelText: '取消',
                    type: 'danger'
                });
            } catch (e) {
                return; // 用户取消
            }
            const page = this.sessionPages[cronId];
            try {
                if (this.sessionStore) {
                    const ok = await this.sessionStore.deleteSession(session);
                    if (!ok) return;
                } else {
                    const res = await API.session.delete(session.id);
                    if (res.status !== 200) {
                        if (window.SbToast) window.SbToast.error(res.message || '删除失败');
                        return;
                    }
                    if (window.SbToast) window.SbToast.success('会话已删除');
                }
                if (page) {
                    page.list = page.list.filter(x => x.id !== session.id);
                }
            } catch (e) {
                if (window.SbToast) window.SbToast.error('删除失败: ' + e.message);
            }
        },

        /* ==================== 辅助 ==================== */

        formatTime(time) {
            if (!time) return '';
            return String(time).replace('T', ' ').substring(0, 19);
        },

        /** 会话累计 token（来自 extension.chat_total_tokens，无则返回 null） */
        sessionTokenTotal(session) {
            const extension = session && session.extension;
            if (!extension || typeof extension !== 'object') return null;
            const total = extension.chat_total_tokens;
            return total == null ? null : total;
        },

        /** token 数字压缩：1234 -> 1.2k，1048576 -> 1.0M */
        formatTokens(n) {
            if (n == null || isNaN(n)) return '-';
            n = Number(n);
            if (n < 1000) return String(n);
            if (n < 1000000) return (n / 1000).toFixed(n < 10000 ? 1 : 0) + 'k';
            return (n / 1000000).toFixed(1) + 'M';
        },

        onKeydown(e) {
            if (!this.visible || e.key !== 'Escape') return;
            this.close();
        },

        scheduleIcons() {
            if (this._iconScheduled) return;
            this._iconScheduled = true;
            requestAnimationFrame(() => {
                this._iconScheduled = false;
                if (window.lucide) window.lucide.createIcons();
            });
        }
    },

    mounted() {
        document.addEventListener('keydown', this.onKeydown);
    },

    beforeUnmount() {
        document.removeEventListener('keydown', this.onKeydown);
    },

    updated() {
        this.scheduleIcons();
    }
};
