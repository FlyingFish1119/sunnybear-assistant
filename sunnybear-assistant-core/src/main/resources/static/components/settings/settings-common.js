/**
 * 设置页组件共享工具
 *
 * 提供：
 *  - MODE_LABELS / AI_TYPE_NAMES：执行模式与 AI 类型的中文名映射
 *  - SettingsCommon mixin：通用保存（postSave）与格式化方法（getModeLabel / formatSize / formatTime）
 *
 * 各设置组件 mixins: [SettingsCommon]，并在模板中使用
 * `<x-settings :settings="xxxSettings" @saved="fetchAllSettings">` 接入。
 */

/** 执行模式 → 中文名 */
const MODE_LABELS = {
    auto: '自动',
    alwaysAsked: '始终询问',
    neverAsked: '从不询问',
    alwaysRejectDanger: '始终拒绝危险'
};

/** AI 类型 → 中文名 */
const AI_TYPE_NAMES = {
    chat: '对话模型',
    chat_pro: '高级对话模型',
    ocr: 'OCR 模型',
    mission: '任务模型',
    task: 'TaskAI 模型',
    cub: '小熊崽模型'
};

const SettingsCommon = {
    data() {
        return {
            // 保存状态（key 与对话框 key 保持一致，用于禁用保存按钮与显示"保存中"）
            saving: {}
        };
    },

    methods: {
        /**
         * 通用保存：POST → 成功后关闭对话框并通知父组件刷新全部设置
         * @param {string} path - API 路径（不含 BASE_PATH）
         * @param {object} body - 请求体
         * @param {string} key - 对话框 / saving 状态 key
         */
        async postSave(path, body, key) {
            this.saving[key] = true;
            try {
                const result = await API.post(path, body);
                if (result.status === 200) {
                    ElementPlus.ElMessage.success('保存成功');
                    this.dialogs[key] = false;
                    this.$emit('saved');
                } else {
                    ElementPlus.ElMessage.error(result.message || '保存失败');
                }
            } catch (e) {
                ElementPlus.ElMessage.error('网络请求失败');
                console.error(e);
            } finally {
                this.saving[key] = false;
            }
        },

        getModeLabel(mode) {
            return MODE_LABELS[mode] || mode || '自动';
        },

        formatSize(bytes) {
            if (bytes == null || bytes < 0) return '-';
            if (bytes < 1024) return bytes + ' B';
            if (bytes < 1048576) return (bytes / 1024).toFixed(2) + ' KB';
            if (bytes < 1073741824) return (bytes / 1048576).toFixed(2) + ' MB';
            return (bytes / 1073741824).toFixed(2) + ' GB';
        },

        formatTime(timeStr) {
            if (!timeStr) return '-';
            try {
                const d = new Date(timeStr);
                const pad = n => String(n).padStart(2, '0');
                return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
                    + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
            } catch (e) {
                return timeStr;
            }
        }
    }
};
