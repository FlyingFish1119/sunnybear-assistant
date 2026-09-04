/**
 * 定时任务管理组件
 *
 * 展示：管理定时任务（任务数量摘要，列表自行加载）
 * 修改：管理对话框内查看全部任务，支持添加、编辑、删除（删除经确认弹窗）
 * 添加 / 编辑 / 删除后自行刷新列表
 */
const CronJobSettings = {
    name: 'CronJobSettings',

    mixins: [SettingsCommon],

    props: {
        mainColor: { type: String, default: 'lightsalmon' }
    },

    template: `
    <div>
        <div class="settings-item" @click="openManage">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="list" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">管理定时任务</span>
                    <span class="settings-item-desc">共 {{ cronJobList.length }} 个任务 · 添加、编辑、删除</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ cronJobList.length }} 个</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>

        <el-dialog v-model="dialogs.cronjobmanage" title="" width="900px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close @open="fetchCronJobList">
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="clock" style="width:20px;height:20px"></i>
                    <span>定时任务管理</span>
                </div>
            </template>
            <div style="margin-bottom:16px;display:flex;justify-content:flex-end">
                <button type="button" class="dialog-btn dialog-btn-save" @click="openCronJobEdit(null)" style="padding:6px 16px;font-size:13px">
                    <i data-lucide="plus" style="width:14px;height:14px"></i> 添加任务
                </button>
            </div>
            <div v-if="cronJobLoading" style="text-align:center;padding:40px;color:#909399">加载中...</div>
            <div v-else-if="cronJobList.length === 0" style="text-align:center;padding:40px;color:#909399">暂无定时任务，点击上方按钮添加</div>
            <div v-else class="entry-list">
                <div v-for="item in cronJobList" :key="item.id" class="entry-item">
                    <div class="entry-item-body">
                        <div class="entry-item-title">
                            <span>{{ item.title }}</span>
                            <code style="background:#f0f0f0;padding:1px 6px;border-radius:3px;font-size:12px;margin-left:8px">{{ item.cron }}</code>
                            <span v-if="item.enablePro" style="margin-left:6px;color:var(--main-color);font-size:12px;font-weight:700">高级</span>
                            <span v-if="item.unreviewed" style="margin-left:6px;color:#e6a23c;font-size:12px;font-weight:700">无审查</span>
                        </div>
                        <div class="entry-item-content" style="max-height:40px;overflow:hidden">{{ item.message }}</div>
                        <div class="entry-item-time">{{ formatTime(item.createTime) }}</div>
                    </div>
                    <div class="entry-item-actions">
                        <button type="button" class="entry-action-btn edit" @click="openCronJobEdit(item)" title="编辑">
                            <i data-lucide="pencil" style="width:15px;height:15px"></i>
                        </button>
                        <button type="button" class="entry-action-btn delete" @click="confirmDeleteCronJob(item)" title="删除">
                            <i data-lucide="trash-2" style="width:15px;height:15px"></i>
                        </button>
                    </div>
                </div>
            </div>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.cronjobmanage = false">关闭</button>
                </div>
            </template>
        </el-dialog>

        <el-dialog v-model="dialogs.cronjobedit" title="" width="720px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close>
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="clock" style="width:20px;height:20px"></i>
                    <span>{{ cronJobEditForm.id ? '编辑定时任务' : '添加定时任务' }}</span>
                </div>
            </template>
            <el-form :model="cronJobEditForm" label-width="90px" label-position="left">
                <el-form-item label="标题">
                    <input class="settings-input" v-model="cronJobEditForm.title" placeholder="任务标题，如'每日早报'" maxlength="100">
                </el-form-item>
                <el-form-item label="描述">
                    <input class="settings-input" v-model="cronJobEditForm.description" placeholder="任务描述（可选）" maxlength="200">
                </el-form-item>
                <el-form-item label="cron 表达式">
                    <input class="settings-input" v-model="cronJobEditForm.cron" placeholder="如 0 0 9 * * * (每天 9:00)" maxlength="50">
                </el-form-item>
                <el-form-item label="触发消息">
                    <textarea class="settings-textarea" v-model="cronJobEditForm.message" rows="4"
                        placeholder="定时触发时发送给 AI 的消息内容"></textarea>
                </el-form-item>
                <el-form-item label="高级模型">
                    <el-switch v-model="cronJobEditForm.enablePro" active-text="开启" inactive-text="关闭"></el-switch>
                    <span style="margin-left:12px;color:#909399;font-size:13px">开启后触发时使用 chat_pro 模型</span>
                </el-form-item>
                <el-form-item label="无审查">
                    <el-switch v-model="cronJobEditForm.unreviewed" active-text="开启" inactive-text="关闭"></el-switch>
                    <span style="margin-left:12px;color:#909399;font-size:13px">开启后触发时跳过工具确认与 AI 危险审查</span>
                </el-form-item>
            </el-form>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.cronjobedit = false">取消</button>
                    <button type="button" class="dialog-btn dialog-btn-save" @click="saveCronJob" :disabled="saving.cronjob">
                        <span v-if="saving.cronjob" class="btn-spinner"></span>
                        <span>{{ saving.cronjob ? '保存中...' : '保存' }}</span>
                    </button>
                </div>
            </template>
        </el-dialog>

        <confirm-dialog ref="confirmDialog" :main-color="mainColor"></confirm-dialog>
    </div>`,

    data() {
        return {
            dialogs: { cronjobmanage: false, cronjobedit: false },
            cronJobList: [],
            cronJobLoading: false,
            cronJobEditForm: { id: null, title: '', description: '', cron: '', message: '', enablePro: false, unreviewed: false }
        };
    },

    methods: {
        openManage() {
            this.dialogs.cronjobmanage = true;
            this.$nextTick(() => lucide.createIcons());
        },

        /* ---------- 定时任务管理 ---------- */
        async fetchCronJobList(silent) {
            this.cronJobLoading = true;
            try {
                const r = await API.cronJob.list();
                if (r.status === 200) {
                    this.cronJobList = r.data || [];
                }
            } catch (e) {
                if (!silent) ElementPlus.ElMessage.error('网络请求失败');
                console.error(e);
            } finally {
                this.cronJobLoading = false;
            }
        },

        openCronJobEdit(item) {
            if (item) {
                this.cronJobEditForm = {
                    id: item.id, title: item.title, description: item.description || '',
                    cron: item.cron, message: item.message,
                    enablePro: item.enablePro || false,
                    unreviewed: item.unreviewed || false
                };
            } else {
                this.cronJobEditForm = { id: null, title: '', description: '', cron: '', message: '', enablePro: false, unreviewed: false };
            }
            this.dialogs.cronjobedit = true;
            this.$nextTick(() => lucide.createIcons());
        },

        async saveCronJob() {
            if (!this.cronJobEditForm.title.trim()) {
                ElementPlus.ElMessage.warning('标题不能为空');
                return;
            }
            if (!this.cronJobEditForm.cron.trim()) {
                ElementPlus.ElMessage.warning('cron 表达式不能为空');
                return;
            }
            if (!this.cronJobEditForm.message.trim()) {
                ElementPlus.ElMessage.warning('触发消息不能为空');
                return;
            }
            this.saving.cronjob = true;
            try {
                const body = {
                    title: this.cronJobEditForm.title.trim(),
                    description: this.cronJobEditForm.description.trim(),
                    cron: this.cronJobEditForm.cron.trim(),
                    message: this.cronJobEditForm.message,
                    enablePro: this.cronJobEditForm.enablePro,
                    unreviewed: this.cronJobEditForm.unreviewed
                };
                if (this.cronJobEditForm.id) {
                    body.id = this.cronJobEditForm.id;
                }
                const r = await API.cronJob.save(body);
                if (r.status === 200) {
                    ElementPlus.ElMessage.success('保存成功');
                    this.dialogs.cronjobedit = false;
                    await this.fetchCronJobList();
                } else {
                    ElementPlus.ElMessage.error(r.message || '保存失败');
                }
            } catch (e) {
                ElementPlus.ElMessage.error('网络请求失败');
                console.error(e);
            } finally {
                this.saving.cronjob = false;
            }
        },

        async confirmDeleteCronJob(item) {
            try {
                await this.$refs.confirmDialog.show({
                    title: '确认删除',
                    message: '确定要删除定时任务「' + item.title + '」吗？此操作不可恢复。',
                    confirmText: '确认删除',
                    cancelText: '取消',
                    type: 'warning'
                });
                const r = await API.cronJob.delete(item.id);
                if (r.status === 200) {
                    ElementPlus.ElMessage.success('删除成功');
                    await this.fetchCronJobList();
                } else {
                    ElementPlus.ElMessage.error(r.message || '删除失败');
                }
            } catch (e) {
                if (e !== undefined && e !== 'cancel' && e !== 'close') {
                    ElementPlus.ElMessage.error('网络请求失败');
                    console.error(e);
                }
            }
        }
    },

    mounted() {
        // 静默加载列表（用于显示条目数量）
        this.fetchCronJobList(true);
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
