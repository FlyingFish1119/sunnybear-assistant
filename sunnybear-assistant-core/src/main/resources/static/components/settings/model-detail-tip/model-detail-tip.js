/**
 * 模型详情悬浮提示组件
 *
 * 把后端 /models 返回的原始字段（key/value 原样保留）渲染成中文可读列表：
 *  - 已知字段名翻成中文标签（未知字段保留原名兜底）
 *  - 布尔值显示为「支持 / 不支持」徽章
 *  - 时间戳、上下文长度、推理强度等做人性化格式化
 *  - 复杂 JSON 值折叠成小号等宽块展示，避免整段原始 JSON 糊在提示里
 *
 * 用法：<el-tooltip><template #content><model-detail-tip :details="m.details" /></template>...</el-tooltip>
 *
 * 样式见同目录 model-detail-tip.css（提示被 teleport 到 body，样式需为全局）。
 */

/** 字段名 → 中文标签 */
const MODEL_DETAIL_LABELS = {
    created: '创建时间',
    created_at: '创建时间',
    updated: '更新时间',
    object: '对象类型',
    owned_by: '归属',
    owner: '归属',
    organization: '组织',
    root: '基座模型',
    parent: '父模型',
    permission: '权限',
    permissions: '权限',
    supports_image_in: '图像输入',
    supports_image_out: '图像输出',
    supports_video_in: '视频输入',
    supports_video_out: '视频输出',
    supports_audio_in: '音频输入',
    supports_vision: '视觉',
    supports_reasoning: '推理能力',
    supports_dynamic_tools: '动态工具',
    supports_function_calling: '函数调用',
    supports_tool_use: '工具调用',
    supports_tools: '工具调用',
    supports_thinking: '思考模式',
    supports_thinking_type: '思考类型',
    think_efforts: '思考强度',
    reasoning_effort: '推理强度',
    reasoning_efforts: '推理强度',
    context_length: '上下文长度',
    context_window: '上下文长度',
    max_context_length: '最大上下文',
    max_context_tokens: '最大上下文',
    max_tokens: '最大输出',
    max_output_tokens: '最大输出',
    max_completion_tokens: '最大输出',
    tokenizer: '分词器',
    modality: '模态',
    modalities: '模态',
    version: '版本',
    description: '描述',
    deprecated: '已弃用'
};

/** 推理 / 思考强度值 → 中文 */
const MODEL_EFFORT_LABELS = {
    none: '无',
    minimal: '最小',
    low: '低',
    medium: '中',
    high: '高',
    max: '最高',
    default: '默认',
    auto: '自动'
};

/** 思考类型值 → 中文 */
const MODEL_THINKING_TYPE_LABELS = {
    only: '仅思考',
    optional: '可选',
    none: '无',
    default: '默认'
};

const ModelDetailUtils = (function () {

    function parseJson(raw) {
        if (raw && typeof raw === 'object') return raw;
        if (typeof raw !== 'string') return null;
        var text = raw.trim();
        if (!text || (text.charAt(0) !== '{' && text.charAt(0) !== '[')) return null;
        try {
            return JSON.parse(text);
        } catch (e) {
            return null;
        }
    }

    function isBoolLike(raw) {
        return raw === true || raw === false || raw === 'true' || raw === 'false';
    }

    function toBool(raw) {
        return raw === true || raw === 'true';
    }

    function isNumeric(raw) {
        return raw !== '' && raw != null && !isNaN(Number(raw));
    }

    function formatTimestamp(raw) {
        var n = Number(raw);
        if (!isFinite(n)) return null;
        // 秒级时间戳 < 1e12，毫秒级直接使用
        if (n < 1e12) n = n * 1000;
        var d = new Date(n);
        if (isNaN(d.getTime()) || d.getFullYear() < 2000) return null;
        var pad = function (v) { return String(v).padStart(2, '0'); };
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
            + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
    }

    function humanize(n) {
        if (n >= 1e9) return (n / 1e9).toFixed(1) + 'B';
        if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M';
        if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K';
        return '';
    }

    function formatBigNumber(raw) {
        var n = Number(raw);
        if (!isFinite(n)) return null;
        var text = n.toLocaleString('en-US');
        var human = humanize(n);
        return human ? text + '（约 ' + human + '）' : text;
    }

    function effortLabel(effort) {
        return MODEL_EFFORT_LABELS[effort] || effort;
    }

    function formatEfforts(raw) {
        var obj = parseJson(raw);
        if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return null;
        var parts = [];
        if (typeof obj.support === 'boolean') {
            parts.push(obj.support ? '支持' : '不支持');
        }
        var valid = obj.valid_efforts || obj.validEfforts;
        if (Array.isArray(valid) && valid.length) {
            parts.push('可选：' + valid.map(effortLabel).join(' / '));
        }
        var def = obj.default_effort || obj.defaultEffort;
        if (def) {
            parts.push('默认：' + effortLabel(def));
        }
        return parts.length ? parts.join(' · ') : null;
    }

    function permissionCount(raw) {
        var arr = parseJson(raw);
        return Array.isArray(arr) ? arr.length : null;
    }

    function prettyJson(raw) {
        var obj = parseJson(raw);
        if (obj == null) return null;
        try {
            return JSON.stringify(obj, null, 2);
        } catch (e) {
            return null;
        }
    }

    /** 单字段格式化：返回 { text, kind }，kind 用于模板选样式（bool / json / text） */
    function formatValue(key, raw) {
        var lower = key.toLowerCase();
        // 布尔能力项：值本身是布尔才按「支持/不支持」渲染（如 supports_reasoning: true）
        if (typeof raw === 'boolean' || isBoolLike(raw)) {
            var flag = toBool(raw);
            return { kind: 'bool', value: flag, text: flag ? '支持' : '不支持' };
        }
        if (lower === 'object') {
            var objectNames = { model: '模型', list: '列表', model_list: '模型列表' };
            return { kind: 'text', text: objectNames[raw] || raw };
        }
        if (lower === 'supports_thinking_type') {
            return { kind: 'text', text: MODEL_THINKING_TYPE_LABELS[raw] || raw };
        }
        if (lower === 'created' || lower === 'created_at' || lower === 'updated') {
            var time = isNumeric(raw) ? formatTimestamp(raw) : null;
            if (time) return { kind: 'text', text: time };
        }
        if (lower.indexOf('context') === 0 || lower.indexOf('max_') === 0 || lower === 'tokenizer') {
            if (lower !== 'tokenizer') {
                var num = isNumeric(raw) ? formatBigNumber(raw) : null;
                if (num) return { kind: 'text', text: num };
            }
        }
        if (lower === 'think_efforts' || lower === 'reasoning_efforts' || lower === 'reasoning_effort') {
            var efforts = formatEfforts(raw);
            if (efforts) return { kind: 'text', text: efforts };
        }
        if (lower === 'permission' || lower === 'permissions') {
            var count = permissionCount(raw);
            if (count != null) return { kind: 'text', text: count + ' 项' };
        }
        var pretty = prettyJson(raw);
        if (pretty !== null) return { kind: 'json', text: pretty };
        return { kind: 'text', text: String(raw) };
    }

    /** 把原始 details 转成有序的行数组（跳过空值） */
    function rows(details) {
        if (!details || typeof details !== 'object') return [];
        var result = [];
        Object.keys(details).forEach(function (key) {
            var raw = details[key];
            if (raw == null || raw === '') return;
            var formatted = formatValue(key, raw);
            result.push({
                key: key,
                label: MODEL_DETAIL_LABELS[key] || key,
                kind: formatted.kind,
                value: formatted.value,
                text: formatted.text
            });
        });
        return result;
    }

    /** 是否存在可展示的详情（用于 v-if 判断，避免空提示） */
    function has(details) {
        return rows(details).length > 0;
    }

    return { rows: rows, has: has };
})();

const ModelDetailTip = {
    name: 'ModelDetailTip',

    props: {
        details: { type: Object, default: function () { return {}; } }
    },

    computed: {
        rows: function () {
            return ModelDetailUtils.rows(this.details);
        }
    },

    template: `
    <div class="model-detail-tip">
        <div v-for="row in rows" :key="row.key" class="model-detail-item">
            <span class="model-detail-key">{{ row.label }}</span>
            <span class="model-detail-value">
                <span v-if="row.kind === 'bool'"
                      class="model-detail-badge"
                      :class="row.value ? 'is-yes' : 'is-no'">{{ row.text }}</span>
                <span v-else-if="row.kind === 'json'" class="model-detail-json">{{ row.text }}</span>
                <span v-else>{{ row.text }}</span>
            </span>
        </div>
    </div>`
};
