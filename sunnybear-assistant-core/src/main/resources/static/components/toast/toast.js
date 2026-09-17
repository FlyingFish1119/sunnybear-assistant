/**
 * SunnyBear Toast —— 右上角信息卡式轻提示（接管 ElementPlus.ElMessage）
 *
 * 为什么要有它：
 *   Element Plus 的 ElMessage 是白底灰边、顶部居中往下挤的老样式，而且堆叠位置由 JS
 *   算好写在内联 top 上，方向改不动。这里做一层薄接管：项目里 170+ 处
 *   ElementPlus.ElMessage.xxx(...) 调用一行都不用改，视觉、动效、堆叠全归自己管。
 *
 * 用法（与原来完全一致）：
 *   ElementPlus.ElMessage.success('已复制到剪贴板');
 *   ElementPlus.ElMessage.error('网络请求失败，请检查网络连接');
 *   ElementPlus.ElMessage.warning('请先选择一个会话');
 *   // 需要两行结构时（标题 + 说明）：
 *   ElementPlus.ElMessage.success({ title: '已导出', message: '对话已导出为 Markdown 文件' });
 *   // 也可以直接走独立入口，不依赖 Element Plus：
 *   SbToast.error('出错了');
 *
 * 配色约定：
 *   success 使用主题色 --main-color（品牌色即正向反馈色）；
 *   warning / error 保持语义色（黄 / 红）——这两个是认知底线，不参与主题化。
 *
 * 加载要求：
 *   需在 element-plus.full.min.js 之后引入（本文件会自动补一次 DOMContentLoaded 重试兜底）。
 *   toast.css 由本文件自动注入，页面无需额外引入样式。
 */
(function () {
    'use strict';

    /* ==================== 常量 ==================== */

    var MAX_VISIBLE = 5;            // 同时最多显示条数，超出挤掉最老的一条
    var DEFAULT_DURATION = 3000;    // 自动关闭时长（ms），传 0 表示常驻不自动关
    var LAYER_ID = 'sbToastLayer';
    var STYLE_ID = 'sbToastStyle';

    var ICONS = {
        success: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round"><path d="M4 12.6 9.6 18.2 20 6.4"/></svg>',
        warning: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round"><path d="M12 5.5v9"/><path d="M12 18.4h.01"/></svg>',
        error:   '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round"><path d="M6.5 6.5l11 11M17.5 6.5l-11 11"/></svg>',
        info:    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round"><path d="M12 10.6v7.8"/><path d="M12 6.2h.01"/></svg>'
    };

    /** 当前存活实例，数组第 0 项是最新的一条 */
    var live = [];

    /* ==================== 样式注入 ==================== */

    /** 本脚本所在目录（供自动注入 toast.css 用） */
    function selfDir() {
        var cur = document.currentScript;
        if (cur && cur.src) return cur.src.replace(/[^\/]*$/, '');
        var found = document.querySelector('script[src*="toast.js"]');
        if (found && found.src) return found.src.replace(/[^\/]*$/, '');
        return '';
    }

    var CSS_DIR = selfDir();

    function injectStyle() {
        if (document.getElementById(STYLE_ID)) return;
        var link = document.createElement('link');
        link.id = STYLE_ID;
        link.rel = 'stylesheet';
        link.href = CSS_DIR + 'toast.css';
        document.head.appendChild(link);
    }

    /* ==================== DOM 构建 ==================== */

    function layer() {
        var el = document.getElementById(LAYER_ID);
        if (!el) {
            el = document.createElement('div');
            el.id = LAYER_ID;
            el.className = 'sb-toast-layer';
            document.body.appendChild(el);
        }
        return el;
    }

    /** 入参归一化：支持 '文本' / 数字 / { type, title, message, duration } */
    function normalize(input, forcedType) {
        var opt = {
            type: forcedType || 'info',
            title: '',
            message: '',
            duration: DEFAULT_DURATION
        };

        if (input == null) {
            opt.message = '';
        } else if (typeof input === 'string' || typeof input === 'number') {
            opt.message = String(input);
        } else if (typeof input === 'object') {
            if (input.type) opt.type = String(input.type);
            if (input.title != null) opt.title = String(input.title);
            if (input.message != null) opt.message = String(input.message);
            if (typeof input.duration === 'number') opt.duration = input.duration;
        }

        if (!ICONS[opt.type]) opt.type = 'info';
        return opt;
    }

    function build(opt) {
        var el = document.createElement('div');
        el.className = 'sb-toast sb-toast--' + opt.type + (opt.title ? ' sb-toast--rich' : '');
        el.setAttribute('role', 'status');
        el.setAttribute('aria-live', 'polite');

        var icon = document.createElement('span');
        icon.className = 'sb-toast__icon';
        icon.innerHTML = ICONS[opt.type];          // 固定常量串，非外部输入

        var body = document.createElement('div');
        body.className = 'sb-toast__body';

        if (opt.title) {
            var title = document.createElement('div');
            title.className = 'sb-toast__title';
            title.textContent = opt.title;         // 一律文本注入，防 XSS
            body.appendChild(title);

            var desc = document.createElement('div');
            desc.className = 'sb-toast__desc';
            desc.textContent = opt.message;
            body.appendChild(desc);
        } else {
            var msg = document.createElement('div');
            msg.className = 'sb-toast__msg';
            msg.textContent = opt.message;
            body.appendChild(msg);
        }

        el.appendChild(icon);
        el.appendChild(body);
        return el;
    }

    /* ==================== 关闭 / 计时 ==================== */

    function pause(inst) {
        if (inst.closed || !inst.timer) return;
        clearTimeout(inst.timer);
        inst.timer = null;
        inst.remain -= (Date.now() - inst.startAt);
        if (inst.remain < 0) inst.remain = 0;
    }

    function resume(inst) {
        if (inst.closed || inst.timer) return;
        if (inst.remain <= 0) { dismiss(inst); return; }
        inst.startAt = Date.now();
        inst.timer = setTimeout(function () { dismiss(inst); }, inst.remain);
    }

    /**
     * 关掉一条：先锁高度再塌陷到 0，让下面几条平滑上移，而不是「啪」地跳一下。
     */
    function dismiss(inst) {
        if (!inst || inst.closed) return;
        inst.closed = true;
        if (inst.timer) { clearTimeout(inst.timer); inst.timer = null; }

        var idx = live.indexOf(inst);
        if (idx !== -1) live.splice(idx, 1);

        var el = inst.el;
        el.style.height = el.offsetHeight + 'px';
        el.style.overflow = 'hidden';
        void el.offsetHeight;                      // 强制重排，锁定起始高度

        el.style.transition =
            'height .22s ease, margin .22s ease, padding .22s ease, opacity .16s ease, transform .22s ease';
        el.style.height = '0px';
        el.style.paddingTop = '0px';
        el.style.paddingBottom = '0px';
        el.style.marginTop = '-10px';              // 抵消容器 gap
        el.style.opacity = '0';
        el.style.transform = 'translateX(18px)';

        setTimeout(function () {
            if (el.parentNode) el.parentNode.removeChild(el);
        }, 240);
    }

    function closeAll() {
        live.slice().forEach(dismiss);
    }

    /* ==================== 弹出 ==================== */

    function show(input, forcedType) {
        var opt = normalize(input, forcedType);

        injectStyle();
        var box = layer();
        var el = build(opt);

        // 新消息插在最上，旧的顺次往下 —— 最新的一条永远在同一个位置，视线不用追
        box.insertBefore(el, box.firstChild);

        var inst = { el: el, timer: null, startAt: 0, remain: opt.duration, closed: false };
        live.unshift(inst);

        while (live.length > MAX_VISIBLE) {
            dismiss(live[live.length - 1]);
        }

        el.addEventListener('click', function () { dismiss(inst); });

        if (opt.duration > 0) {
            // 鼠标悬停时暂停倒计时，看完再走
            el.addEventListener('mouseenter', function () { pause(inst); });
            el.addEventListener('mouseleave', function () { resume(inst); });
            resume(inst);
        }

        return { close: function () { dismiss(inst); } };
    }

    /* ==================== 接管 ElementPlus.ElMessage ==================== */

    function install() {
        var EP = window.ElementPlus;
        if (!EP) return false;

        var current = EP.ElMessage;
        if (current && current.__sbToast) return true;   // 已接管，幂等

        var shim = function (options) { return show(options, 'info'); };
        shim.success = function (options) { return show(options, 'success'); };
        shim.warning = function (options) { return show(options, 'warning'); };
        shim.error   = function (options) { return show(options, 'error'); };
        shim.info    = function (options) { return show(options, 'info'); };
        shim.closeAll = closeAll;
        shim.close = closeAll;
        shim.__sbToast = true;

        EP.ElMessage = shim;
        return true;
    }

    /* ==================== 对外入口 ==================== */

    window.SbToast = {
        show: show,
        success: function (o) { return show(o, 'success'); },
        warning: function (o) { return show(o, 'warning'); },
        error:   function (o) { return show(o, 'error'); },
        info:    function (o) { return show(o, 'info'); },
        closeAll: closeAll
    };

    if (!install()) {
        // 兜底：万一被提到 element-plus 之前引入，等 DOM 就绪再补一次
        document.addEventListener('DOMContentLoaded', install, { once: true });
        window.addEventListener('load', install, { once: true });
    }
})();
