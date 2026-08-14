/**
 * 助手设置组件
 *
 * 展示：助手信息条目（名称/头像摘要）
 * 修改：对话框内编辑助手名称、上传/清除头像
 * 保存成功后 emit('saved')，由父组件刷新全部设置
 */
const AssistantSettings = {
    name: 'AssistantSettings',

    mixins: [SettingsCommon],

    props: {
        settings: { type: Object, default: () => ({}) }
    },

    emits: ['saved'],

    template: `
    <div>
        <div class="settings-item" @click="openDialog">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="sparkles" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">助手信息</span>
                    <span class="settings-item-desc">助手名称、头像</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ settings.assistantName || '未设置' }}</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>

        <el-dialog v-model="dialogs.assistant" title="" width="720px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close>
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="bot" style="width:20px;height:20px"></i>
                    <span>助手设置</span>
                </div>
            </template>
            <el-form :model="assistantForm" label-width="80px" label-position="left">
                <el-form-item label="助手名称">
                    <input class="settings-input" v-model="assistantForm.assistantName" placeholder="输入助手名称" maxlength="30">
                </el-form-item>
                <el-form-item label="助手头像">
                    <div class="char-avatar-row">
                        <div class="char-avatar-preview" @click="triggerAssistantAvatarUpload" title="点击上传头像">
                            <img v-show="assistantForm.avatar" :src="assistantAvatarPreviewUrl" />
                            <div v-show="!assistantForm.avatar" style="display:flex;align-items:center;justify-content:center;width:100%;height:100%">
                                <i data-lucide="camera" style="width:24px;height:24px;color:#c0c4cc"></i>
                            </div>
                        </div>
                        <div class="char-avatar-actions">
                            <button type="button" class="char-avatar-btn" @click="triggerAssistantAvatarUpload">
                                {{ assistantForm.avatar ? '更换图片' : '上传图片' }}
                            </button>
                            <button type="button" v-show="assistantForm.avatar" class="char-avatar-btn danger"
                                @click="clearAssistantAvatar">清除</button>
                        </div>
                    </div>
                    <input ref="assistantAvatarInput" type="file" accept="image/*" style="display:none"
                        @change="onAssistantAvatarFileChange">
                </el-form-item>
            </el-form>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.assistant = false">取消</button>
                    <button type="button" class="dialog-btn dialog-btn-save" @click="saveAssistant" :disabled="saving.assistant">
                        <span v-if="saving.assistant" class="btn-spinner"></span>
                        <span>{{ saving.assistant ? '保存中...' : '保存' }}</span>
                    </button>
                </div>
            </template>
        </el-dialog>
    </div>`,

    data() {
        return {
            dialogs: { assistant: false },
            assistantForm: { assistantName: '', avatar: '' }
        };
    },

    computed: {
        /** 助手头像预览 URL */
        assistantAvatarPreviewUrl() {
            const avatar = this.assistantForm.avatar;
            if (!avatar) return '';
            if (/^https?:\/\//i.test(avatar) || avatar.startsWith('data:')) return avatar;
            return API.fileProxyUrl(avatar);
        }
    },

    methods: {
        openDialog() {
            this.assistantForm = {
                assistantName: this.settings.assistantName || '',
                avatar: this.settings.avatar || ''
            };
            this.dialogs.assistant = true;
            this.$nextTick(() => lucide.createIcons());
        },

        /* ---------- 助手头像上传 ---------- */
        triggerAssistantAvatarUpload() { this.$refs.assistantAvatarInput.click(); },
        async onAssistantAvatarFileChange(e) {
            const file = e.target.files[0];
            if (!file) return;
            e.target.value = '';
            try {
                const r = await API.settings.assistant.uploadAvatar(file);
                if (r.status === 200 && r.data) {
                    this.assistantForm.avatar = r.data + '&t=' + Date.now();
                    ElementPlus.ElMessage.success('头像已上传');
                } else {
                    ElementPlus.ElMessage.error(r.message || '上传失败');
                }
            } catch (err) {
                ElementPlus.ElMessage.error('网络请求失败');
                console.error(err);
            }
        },
        async clearAssistantAvatar() {
            try {
                const r = await API.settings.assistant.deleteAvatar();
                if (r.status === 200) {
                    this.assistantForm.avatar = '';
                    ElementPlus.ElMessage.success('头像已清除');
                } else {
                    ElementPlus.ElMessage.error(r.message || '清除失败');
                }
            } catch (e) {
                ElementPlus.ElMessage.error('网络请求失败');
                console.error(e);
            }
        },

        /* ---------- 保存 ---------- */
        saveAssistant() {
            if (!this.assistantForm.assistantName.trim()) {
                ElementPlus.ElMessage.warning('助手名称不能为空');
                return;
            }
            this.postSave('settings/assistant/save', {
                assistantName: this.assistantForm.assistantName.trim()
            }, 'assistant');
        }
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
