/* ============================================================
 * 看板熊 live 版 —— PSD 分层 + 原生 JS 拟态动效
 * ------------------------------------------------------------
 * 素材：static/icon/signboard_bear/live/（由 PSD 分层导出）
 * 几何：window.SUNNY_BEAR_GEOMETRY（bear-geometry.js，脚本自动生成）
 *
 * 动效清单：
 *   呼吸     整体缩放 + 上下起伏（正弦，永续）
 *   眨眼     眼白+眼仁+睫毛纵向压缩，随机间隔（睫毛在 face 之上，单独同步）
 *   瞳孔跟随 鼠标位置驱动 —— 只动眼仁，眼白保持不动
 *   耳朵抽动 随机弹性抖动，左右错开方向
 *   刘海飘动 缓慢旋转 + 斜切
 *   头部微转 整头绕颈部旋转，跟随鼠标（带平滑）
 *   上身轻摆 身体组绕底部中心轻摆
 *
 * 用法：
 *   var bear = SunnyBearMascot.create(stageEl);
 *   bear.start();   // 可见时才跑，省 CPU
 *   bear.stop();
 *   bear.destroy();
 * ============================================================ */
(function (global) {
    'use strict';

    // 注意：几何数据里的 file 字段已含 'live/' 前缀（见 bear-geometry.js），这里不要再拼一层
    var ASSET_BASE = 'icon/signboard_bear/';

    /* 动效参数：手感都集中在这里，想调只改这块 */
    var CFG = {
        // 呼吸
        breath: { periodMs: 4600, scale: 0.007, liftPx: 1.8 },
        // 头部跟随鼠标
        headFollow: { maxAngle: 3.6, maxShift: 1.9, ease: 0.085 },
        // 上身轻摆
        bodyFollow: { maxAngle: 0.3, ease: 0.07 },
        // 眼仁跟随（range = 眼白内可用的最大偏移比例）
        iris: { range: 1.4, ease: 0.15 },
        // 眨眼
        blink: { gapMinMs: 2600, gapMaxMs: 6400, closeMs: 70, openMs: 120, closedScaleY: 0.06 },
        // 耳朵抽动
        ear: { gapMinMs: 3800, gapMaxMs: 9500, ampDeg: 7, durMs: 380 },
        // 刘海飘动
        hair: { periodMs: 5600, angleDeg: 1.8, skewDeg: 0.65 }
    };

    /* 绘制顺序（从底到顶）：沿用 PSD 图层顺序；
       眼睛单独成组，方便"眨眼整组压缩"与"只动眼仁"两件事分开做 */
    var BODY_ORDER = ['topwear', 'neck', 'neckwear', 'handwear-l', 'handwear-r'];
    /* 头部绘制顺序（从底到顶），严格按 PSD 图层排列：
         ears → 眼窝底(face-r-eye / face-l-eye) → 眼睛(眼白+眼仁)
         → face / mouth / nose → 睫毛 → headwear / front hair / eyebrow
       face 盖在眼睛上面，它的两个眼窝是透明的 —— 眼睛只从洞里露出来；
       眼仁偏到眼白之外的部分会被 face 直接挡掉，不会溢出到脸上 */
    var HEAD_BASE = ['ears-r', 'ears-l'];
    var HEAD_SOCKET = ['face-l-eye', 'face-r-eye'];
    var HEAD_FACE = ['face', 'mouth', 'nose'];
    var HEAD_TAIL = ['headwear', 'front hair', 'eyebrow-l', 'eyebrow-r'];
    var EYES = [
        { key: 'r', white: 'eyewhite-r', iris: 'irides-r', lash: 'eyelash-r' },
        { key: 'l', white: 'eyewhite-l', iris: 'irides-l', lash: 'eyelash-l' }
    ];
    var EAR_ORIGIN = { 'ears-r': '82% 100%', 'ears-l': '28% 100%' };
    var HAIR_ORIGIN = '45% 22%';

    function clamp(v, lo, hi) { return v < lo ? lo : (v > hi ? hi : v); }
    function rand(lo, hi) { return lo + Math.random() * (hi - lo); }

    /**
     * 在给定的舞台元素里搭出分层看板熊，并返回控制器。
     * @param {HTMLElement} stage 承载分层图的容器（模板里已有 .mascot-stage）
     */
    function create(stage) {
        if (!stage) return null;
        if (stage.__bearLive) return stage.__bearLive;

        var geo = global.SUNNY_BEAR_GEOMETRY;
        if (!geo || !geo.layers || !geo.layers.length) {
            console.warn('[mascot] 缺少 SUNNY_BEAR_GEOMETRY，看板熊动效跳过');
            return null;
        }

        var byName = {};
        geo.layers.forEach(function (l) { byName[l.name] = l; });

        var el = {};          // 层名 -> <img>
        var contentW = geo.contentSize[0];
        var contentH = geo.contentSize[1];

        function makeLayer(name) {
            var l = byName[name];
            if (!l) return null;
            var img = document.createElement('img');
            img.className = 'mascot-layer';
            img.alt = '';
            img.draggable = false;
            img.setAttribute('data-part', name);
            img.src = ASSET_BASE + l.file;
            img.style.left = l.x.toFixed(4) + '%';
            img.style.top = l.y.toFixed(4) + '%';
            img.style.width = l.w.toFixed(4) + '%';
            img.style.height = l.h.toFixed(4) + '%';
            el[name] = img;
            return img;
        }

        /* ---------- 组装 DOM：身体组 / 头部组（含左右眼组） ---------- */
        stage.innerHTML = '';

        var bodyGroup = document.createElement('div');
        // ⚠ 类名不能叫 mascot-body：css/send-area.css（插件页那份旧样式）里有
        //   `.send-area-mascot .mascot-body` 规则，会把身体组变成透明的 90px 小块并顶到画面外
        bodyGroup.className = 'mascot-group mascot-body-group';
        BODY_ORDER.forEach(function (n) { var i = makeLayer(n); if (i) bodyGroup.appendChild(i); });

        var headGroup = document.createElement('div');
        headGroup.className = 'mascot-group mascot-head';
        var neckGeo = byName['neck'];
        var headOriginX = neckGeo ? (neckGeo.px[0] + neckGeo.px[2] / 2) / contentW : 0.5;
        var headOriginY = neckGeo ? (neckGeo.px[1] + neckGeo.px[3] * 0.7) / contentH : 0.36;
        headGroup.style.transformOrigin = (headOriginX * 100).toFixed(2) + '% ' + (headOriginY * 100).toFixed(2) + '%';

        HEAD_BASE.forEach(function (n) { var i = makeLayer(n); if (i) headGroup.appendChild(i); });

        // 眼窝底：垫在眼睛下面（PSD 里这两层就在最底层）
        HEAD_SOCKET.forEach(function (n) { var i = makeLayer(n); if (i) headGroup.appendChild(i); });

        var eyeGroups = [];
        var eyeLashes = [];

        EYES.forEach(function (spec) {
            var whiteGeo = byName[spec.white];
            var irisGeo = byName[spec.iris];
            if (!whiteGeo || !irisGeo) return;

            // 眨眼的压缩轴心：眼白中心
            var ox = (whiteGeo.px[0] + whiteGeo.px[2] / 2) / contentW;
            var oy = (whiteGeo.px[1] + whiteGeo.px[3] / 2) / contentH;
            var origin = (ox * 100).toFixed(2) + '% ' + (oy * 100).toFixed(2) + '%';

            // 眼白 + 眼仁：压在 face 底下，从 face 的透明眼窝里露出来
            var g = document.createElement('div');
            g.className = 'mascot-group mascot-eye mascot-eye-' + spec.key;
            g.style.transformOrigin = origin;

            [spec.white, spec.iris].forEach(function (n) {
                var i = makeLayer(n);
                if (i) g.appendChild(i);
            });
            headGroup.appendChild(g);

            eyeGroups.push({
                el: g,
                irisEl: el[spec.iris],
                // 眼仁可走的最大距离（原图像素）：眼白与眼仁的半径差 × 系数
                maxX: (whiteGeo.px[2] - irisGeo.px[2]) / 2 * CFG.iris.range,
                maxY: (whiteGeo.px[3] - irisGeo.px[3]) / 2 * CFG.iris.range
            });

            // 睫毛在 PSD 里画在 face 之上（它本来就在眼白轮廓外），
            // 跟着眼白沉到 face 底下会被脸皮整条吃掉，所以单独拎出来
            var lash = makeLayer(spec.lash);
            if (lash) {
                // ⚠ 睫毛已经是 headGroup 的直接子元素，transform-origin 的百分比是相对
                //   "它自己的小框"算的，不能直接套眼组那个 origin（那是相对整个舞台算的）。
                //   这里换算成"眼白中心相对睫毛左上角"的比例，眨眼才会和眼白同轴同步。
                var lashGeo = byName[spec.lash];
                lash.style.transformOrigin =
                    ((whiteGeo.px[0] + whiteGeo.px[2] / 2 - lashGeo.px[0]) / lashGeo.px[2] * 100).toFixed(2) + '% ' +
                    ((whiteGeo.px[1] + whiteGeo.px[3] / 2 - lashGeo.px[1]) / lashGeo.px[3] * 100).toFixed(2) + '%';
                eyeLashes.push(lash);
            }
        });

        // face 盖在眼睛之上：它的两个眼窝是透明的，眼睛正好从洞里露出来
        HEAD_FACE.forEach(function (n) { var i = makeLayer(n); if (i) headGroup.appendChild(i); });

        // 睫毛层（在 face 之上、刘海之下）
        eyeLashes.forEach(function (i) { headGroup.appendChild(i); });

        HEAD_TAIL.forEach(function (n) { var i = makeLayer(n); if (i) headGroup.appendChild(i); });

        Object.keys(EAR_ORIGIN).forEach(function (n) {
            if (el[n]) el[n].style.transformOrigin = EAR_ORIGIN[n];
        });
        if (el['front hair']) el['front hair'].style.transformOrigin = HAIR_ORIGIN;

        stage.appendChild(bodyGroup);
        stage.appendChild(headGroup);

        /* ---------- 运行状态 ---------- */
        var state = {
            running: false,
            raf: 0,
            scale: 0.25,
            lastStageW: 0,
            lastScaleCheck: 0,
            target: { x: 0, y: 0 },     // 鼠标归一化位置 -1..1
            eye: { x: 0, y: 0 },
            head: { x: 0, y: 0 },
            body: { x: 0 },
            blinkStart: 0,
            nextBlink: 0,
            lastBlinkScaleY: 1,
            twitchStart: 0,
            nextTwitch: 0,
            lastEarAmp: null
        };

        function refreshScale() {
            var w = stage.clientWidth || 0;
            if (w > 0 && w !== state.lastStageW) {
                state.lastStageW = w;
                state.scale = w / contentW;
            }
        }

        /* ---------- 每帧：呼吸 / 跟随 / 眨眼 / 耳朵 / 头发 ---------- */
        function frame(now) {
            if (!state.running) return;
            state.raf = requestAnimationFrame(frame);

            if (now - state.lastScaleCheck > 400) {
                state.lastScaleCheck = now;
                refreshScale();
            }

            var s = state.scale || 0.25;
            var t = state.target;

            state.eye.x += (t.x - state.eye.x) * CFG.iris.ease;
            state.eye.y += (t.y - state.eye.y) * CFG.iris.ease;
            state.head.x += (t.x - state.head.x) * CFG.headFollow.ease;
            state.head.y += (t.y - state.head.y) * CFG.headFollow.ease;
            state.body.x += (t.x - state.body.x) * CFG.bodyFollow.ease;

            // 呼吸：整体起伏 + 极轻微缩放
            var bp = Math.sin(now / CFG.breath.periodMs * Math.PI * 2);
            stage.style.transform =
                'translateY(' + (-bp * CFG.breath.liftPx * s).toFixed(3) + 'px)' +
                ' scale(' + (1 + bp * CFG.breath.scale).toFixed(5) + ')';

            // 头部：跟随鼠标微转 + 呼吸的细微带动
            headGroup.style.transform =
                'rotate(' + (state.head.x * CFG.headFollow.maxAngle + bp * 0.28).toFixed(3) + 'deg)' +
                ' translate(' + (state.head.x * CFG.headFollow.maxShift * s).toFixed(3) + 'px,' +
                                 (state.head.y * CFG.headFollow.maxShift * s).toFixed(3) + 'px)';

            // 上身轻摆
            bodyGroup.style.transform =
                'rotate(' + (state.body.x * CFG.bodyFollow.maxAngle + bp * 0.12).toFixed(3) + 'deg)';

            // 眼仁跟随：只动眼仁，眼白纹丝不动
            for (var i = 0; i < eyeGroups.length; i++) {
                var g = eyeGroups[i];
                g.irisEl.style.transform =
                    'translate(' + (state.eye.x * g.maxX * s).toFixed(3) + 'px,' +
                                   (state.eye.y * g.maxY * s).toFixed(3) + 'px)';
            }

            // 眨眼
            if (!state.nextBlink) state.nextBlink = now + rand(CFG.blink.gapMinMs, CFG.blink.gapMaxMs);
            if (!state.blinkStart && now >= state.nextBlink) {
                state.blinkStart = now;
                state.nextBlink = 0;
            }
            var scaleY = 1;
            if (state.blinkStart) {
                var elapsed = now - state.blinkStart;
                var total = CFG.blink.closeMs + CFG.blink.openMs;
                if (elapsed >= total) {
                    state.blinkStart = 0;
                    state.nextBlink = now + rand(CFG.blink.gapMinMs, CFG.blink.gapMaxMs);
                } else if (elapsed < CFG.blink.closeMs) {
                    var cp = elapsed / CFG.blink.closeMs;
                    scaleY = 1 - (1 - CFG.blink.closedScaleY) * (cp * cp);
                } else {
                    var op = (elapsed - CFG.blink.closeMs) / CFG.blink.openMs;
                    scaleY = CFG.blink.closedScaleY + (1 - CFG.blink.closedScaleY) * op;
                }
            }
            if (Math.abs(scaleY - state.lastBlinkScaleY) > 0.0015) {
                state.lastBlinkScaleY = scaleY;
                for (var j = 0; j < eyeGroups.length; j++) {
                    eyeGroups[j].el.style.transform = 'scaleY(' + scaleY.toFixed(4) + ')';
                }
                // 睫毛是独立元素，眨眼要单独同步一次（不然只有眼睛闭合、睫毛不动）
                for (var k = 0; k < eyeLashes.length; k++) {
                    eyeLashes[k].style.transform = 'scaleY(' + scaleY.toFixed(4) + ')';
                }
            }

            // 耳朵抽动（左右反相，看起来像被什么东西挠了一下）
            if (!state.nextTwitch) state.nextTwitch = now + rand(CFG.ear.gapMinMs, CFG.ear.gapMaxMs);
            if (!state.twitchStart && now >= state.nextTwitch) {
                state.twitchStart = now;
                state.nextTwitch = 0;
            }
            var amp = 0;
            if (state.twitchStart) {
                var ep = (now - state.twitchStart) / CFG.ear.durMs;
                if (ep >= 1) {
                    state.twitchStart = 0;
                    state.nextTwitch = now + rand(CFG.ear.gapMinMs, CFG.ear.gapMaxMs);
                } else {
                    amp = Math.sin(ep * Math.PI * 3.2) * (1 - ep);
                }
            }
            if (state.lastEarAmp === null || Math.abs(amp - state.lastEarAmp) > 0.002) {
                state.lastEarAmp = amp;
                if (el['ears-r']) el['ears-r'].style.transform = 'rotate(' + (amp * CFG.ear.ampDeg).toFixed(3) + 'deg)';
                if (el['ears-l']) el['ears-l'].style.transform = 'rotate(' + (-amp * CFG.ear.ampDeg * 0.85).toFixed(3) + 'deg)';
            }

            // 刘海飘动：小角度旋转 + 斜切，幅度小到只当"微微在动"
            if (el['front hair']) {
                var hp = Math.sin(now / CFG.hair.periodMs * Math.PI * 2);
                var hs = Math.sin(now / CFG.hair.periodMs * Math.PI * 2 + 1.1);
                el['front hair'].style.transform =
                    'rotate(' + (hp * CFG.hair.angleDeg).toFixed(3) + 'deg)' +
                    ' skewX(' + (hs * CFG.hair.skewDeg).toFixed(3) + 'deg)';
            }
        }

        /* ---------- 鼠标/触摸跟随 ---------- */
        function onPointerMove(e) {
            var pt = (e.touches && e.touches.length) ? e.touches[0] : e;
            if (!pt) return;
            var rect = stage.getBoundingClientRect();
            if (!rect.width) return;
            var cx = rect.left + rect.width / 2;
            var cy = rect.top + rect.height * 0.45;   // 参考点取头部附近，眼仁偏得自然些
            var nx = (pt.clientX - cx) / Math.max(320, global.innerWidth * 0.5);
            var ny = (pt.clientY - cy) / Math.max(240, global.innerHeight * 0.5);
            state.target.x = clamp(nx, -1, 1);
            state.target.y = clamp(ny, -1, 1);
        }

        function resetTarget() {
            state.target.x = 0;
            state.target.y = 0;
        }

        var instance = {
            stage: stage,
            start: function () {
                if (state.running) return;
                state.running = true;
                refreshScale();
                var now = (global.performance || Date).now();
                state.nextBlink = now + rand(900, 2600);
                state.nextTwitch = now + rand(700, 2400);
                document.addEventListener('mousemove', onPointerMove, { passive: true });
                document.addEventListener('touchmove', onPointerMove, { passive: true });
                document.addEventListener('mouseleave', resetTarget);
                state.raf = requestAnimationFrame(frame);
            },
            stop: function () {
                if (!state.running) return;
                state.running = false;
                cancelAnimationFrame(state.raf);
                document.removeEventListener('mousemove', onPointerMove);
                document.removeEventListener('touchmove', onPointerMove);
                document.removeEventListener('mouseleave', resetTarget);
            },
            destroy: function () {
                instance.stop();
                stage.innerHTML = '';
                stage.__bearLive = null;
            }
        };

        stage.__bearLive = instance;
        return instance;
    }

    global.SunnyBearMascot = { create: create, config: CFG };
})(window);
