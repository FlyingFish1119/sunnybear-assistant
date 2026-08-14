/**
 * 用户设置组件
 *
 * 展示：个人信息条目（用户名/头像/背景图摘要）
 * 修改：对话框内编辑用户名、上传头像/背景图、透明度、主题色、自动切换模型
 * 保存成功后 emit('saved')，由父组件刷新全部设置
 */
const UserSettings = {
    name: 'UserSettings',

    mixins: [SettingsCommon],

    props: {
        settings: { type: Object, default: () => ({}) }
    },

    emits: ['saved', 'main-color-change'],

    template: `
    <div>
        <div class="settings-item" @click="openDialog">
            <div class="settings-item-left">
                <div class="settings-item-icon"><i data-lucide="user" style="width:16px;height:16px"></i></div>
                <div class="settings-item-info">
                    <span class="settings-item-label">个人信息</span>
                    <span class="settings-item-desc">用户名、头像、背景图</span>
                </div>
            </div>
            <div class="settings-item-right">
                <span class="settings-item-value">{{ settings.username || '未设置' }}</span>
                <i data-lucide="chevron-right" class="settings-item-arrow" style="width:16px;height:16px"></i>
            </div>
        </div>

        <el-dialog v-model="dialogs.user" title="" width="720px" class="settings-dialog" :close-on-click-modal="false" destroy-on-close>
            <template #header>
                <div class="dialog-header-wrap">
                    <i data-lucide="user-circle" style="width:20px;height:20px"></i>
                    <span>用户设置</span>
                </div>
            </template>
            <el-form :model="userForm" label-width="80px" label-position="left">
                <el-form-item label="用户名">
                    <input class="settings-input" v-model="userForm.username" placeholder="输入用户名" maxlength="30">
                </el-form-item>
                <el-form-item label="头像">
                    <div class="char-avatar-row">
                        <div class="char-avatar-preview" @click="triggerUserAvatarUpload" title="点击上传头像">
                            <img v-show="userForm.avatar" :src="userAvatarPreviewUrl" />
                            <div v-show="!userForm.avatar" style="display:flex;align-items:center;justify-content:center;width:100%;height:100%">
                                <i data-lucide="camera" style="width:24px;height:24px;color:#c0c4cc"></i>
                            </div>
                        </div>
                        <div class="char-avatar-actions">
                            <button type="button" class="char-avatar-btn" @click="triggerUserAvatarUpload">
                                {{ userForm.avatar ? '更换图片' : '上传图片' }}
                            </button>
                            <button type="button" v-show="userForm.avatar" class="char-avatar-btn danger"
                                @click="clearUserAvatar">清除</button>
                        </div>
                    </div>
                    <input ref="userAvatarInput" type="file" accept="image/*" style="display:none"
                        @change="onUserAvatarFileChange">
                </el-form-item>
                <el-form-item label="背景图">
                    <div class="background-preview" @click="triggerUserBackgroundUpload" title="点击上传背景图">
                        <img v-show="userForm.background" :src="userBackgroundPreviewUrl" />
                        <div v-show="!userForm.background" style="display:flex;align-items:center;justify-content:center;width:100%;height:100%">
                            <i data-lucide="image" style="width:28px;height:28px;color:#c0c4cc"></i>
                        </div>
                    </div>
                    <div class="char-avatar-row">
                        <button type="button" class="char-avatar-btn" @click="triggerUserBackgroundUpload">
                            {{ userForm.background ? '更换图片' : '上传图片' }}
                        </button>
                        <button type="button" v-show="userForm.background" class="char-avatar-btn danger"
                            @click="clearUserBackground">清除</button>
                    </div>
                    <input ref="userBgInput" type="file" accept="image/*" style="display:none"
                        @change="onUserBackgroundFileChange">
                </el-form-item>
                <el-form-item label="透明度">
                    <el-slider v-model="userForm.opacity" :min="0" :max="1" :step="0.05" show-input :format-tooltip="v => v.toFixed(2)" style="width: calc(100% - 80px)"></el-slider>
                </el-form-item>
                <el-form-item label="主题色">
                    <el-color-picker v-model="userForm.mainColor" show-alpha :predefine="predefineColors"></el-color-picker>
                    <span style="margin-left: 12px; color: #909399; font-size: 13px;">选择应用的主题颜色</span>
                </el-form-item>
                <el-form-item label="自动切换模型">
                    <el-switch v-model="userForm.enableAutoSwitchModel" active-text="开启" inactive-text="关闭"></el-switch>
                    <span style="margin-left: 12px; color: #909399; font-size: 13px;">自动判断问题复杂度并切换高级模型</span>
                </el-form-item>
            </el-form>
            <template #footer>
                <div class="dialog-footer">
                    <button type="button" class="dialog-btn dialog-btn-cancel" @click="dialogs.user = false">取消</button>
                    <button type="button" class="dialog-btn dialog-btn-save" @click="saveUser" :disabled="saving.user">
                        <span v-if="saving.user" class="btn-spinner"></span>
                        <span>{{ saving.user ? '保存中...' : '保存' }}</span>
                    </button>
                </div>
            </template>
        </el-dialog>
    </div>`,

    data() {
        return {
            dialogs: { user: false },
            userForm: { username: '', avatar: '', background: '', opacity: 0.3, mainColor: 'lightsalmon', enableAutoSwitchModel: false },
            predefineColors: [
                'lightsalmon', '#409eff', '#67c23a', '#e6a23c', '#f56c6c',
                '#e83e8c', '#6f42c1', '#20c997', '#17a2b8', '#6610f2',
                '#fd7e14', '#28a745', '#dc3545', '#ffc107', '#007bff'
            ]
        };
    },

    computed: {
        /** 用户头像预览 URL */
        userAvatarPreviewUrl() {
            const avatar = this.userForm.avatar;
            if (!avatar) return '';
            if (/^https?:\/\//i.test(avatar) || avatar.startsWith('data:')) return avatar;
            return API.fileProxyUrl(avatar);
        },
        /** 用户背景图预览 URL */
        userBackgroundPreviewUrl() {
            const bg = this.userForm.background;
            if (!bg) return '';
            if (bg.startsWith('data:') || /^https?:\/\//i.test(bg)) return bg;
            return API.fileProxyUrl(bg);
        }
    },

    methods: {
        openDialog() {
            this.userForm = {
                username: this.settings.username || '',
                avatar: this.settings.avatar || '',
                background: this.settings.background || '',
                opacity: this.settings.opacity != null ? this.settings.opacity : 0.3,
                mainColor: this.settings.mainColor || 'lightsalmon',
                enableAutoSwitchModel: this.settings.enableAutoSwitchModel || false
            };
            this.dialogs.user = true;
            this.$nextTick(() => lucide.createIcons());
        },

        /* ---------- 用户头像上传 ---------- */
        triggerUserAvatarUpload() { this.$refs.userAvatarInput.click(); },
        async onUserAvatarFileChange(e) {
            const file = e.target.files[0];
            if (!file) return;
            e.target.value = '';
            try {
                const r = await API.settings.user.uploadAvatar(file);
                if (r.status === 200 && r.data) {
                    this.userForm.avatar = r.data + '&t=' + Date.now();
                    ElementPlus.ElMessage.success('头像已上传');
                } else {
                    ElementPlus.ElMessage.error(r.message || '上传失败');
                }
            } catch (err) {
                ElementPlus.ElMessage.error('网络请求失败');
                console.error(err);
            }
        },
        async clearUserAvatar() {
            try {
                const r = await API.settings.user.deleteAvatar();
                if (r.status === 200) {
                    this.userForm.avatar = '';
                    ElementPlus.ElMessage.success('头像已清除');
                } else {
                    ElementPlus.ElMessage.error(r.message || '清除失败');
                }
            } catch (e) {
                ElementPlus.ElMessage.error('网络请求失败');
                console.error(e);
            }
        },

        /* ---------- 用户背景上传 ---------- */
        triggerUserBackgroundUpload() { this.$refs.userBgInput.click(); },
        async onUserBackgroundFileChange(e) {
            const file = e.target.files[0];
            if (!file) return;
            if (file.size > 10 * 1024 * 1024) {
                ElementPlus.ElMessage.warning('背景图不能超过 10MB');
                e.target.value = '';
                return;
            }
            e.target.value = '';
            try {
                const r = await API.settings.user.uploadBackground(file);
                if (r.status === 200 && r.data) {
                    this.userForm.background = r.data + '&t=' + Date.now();
                    ElementPlus.ElMessage.success('背景图已上传');
                } else {
                    ElementPlus.ElMessage.error(r.message || '上传失败');
                }
            } catch (err) {
                ElementPlus.ElMessage.error('网络请求失败');
                console.error(err);
            }
        },
        async clearUserBackground() {
            try {
                const r = await API.settings.user.deleteBackground();
                if (r.status === 200) {
                    this.userForm.background = '';
                    ElementPlus.ElMessage.success('背景图已清除');
                } else {
                    ElementPlus.ElMessage.error(r.message || '清除失败');
                }
            } catch (e) {
                ElementPlus.ElMessage.error('网络请求失败');
                console.error(e);
            }
        },

        /* ---------- 保存 ---------- */
        saveUser() {
            if (!this.userForm.username.trim()) {
                ElementPlus.ElMessage.warning('用户名不能为空');
                return;
            }
            if (this.userForm.opacity == null || this.userForm.opacity < 0 || this.userForm.opacity > 1) {
                ElementPlus.ElMessage.warning('透明度必须在 0 到 1 之间');
                return;
            }
            const mainColor = this.userForm.mainColor || 'lightsalmon';
            this.postSave('settings/user/save', {
                username: this.userForm.username.trim(),
                opacity: this.userForm.opacity,
                mainColor: mainColor,
                enableAutoSwitchModel: this.userForm.enableAutoSwitchModel
            }, 'user').then(() => {
                // 保存成功后立即同步主题色（父组件刷新时会再同步一次服务端值）
                this.$emit('main-color-change', mainColor);
            });
        }
    },

    updated() {
        this.$nextTick(() => lucide.createIcons());
    }
};
