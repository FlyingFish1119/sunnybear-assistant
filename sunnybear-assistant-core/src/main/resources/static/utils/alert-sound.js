/**
 * 提醒音：有工具请求需要用户确认时，响一声把人叫回来。
 *
 * 声音是 Web Audio 现场合成的，不依赖任何音频文件 —— 少一个网络请求，
 * 也不会因为路径不对在某个页面上哑掉。音色照着一只小铃铛来：
 * 正弦基频叠两层泛音，8ms 极快起音、之后指数衰减，听感是「叮—咚」，
 * 不是电子味的滴滴声。
 *
 * 浏览器自动播放策略：页面一次用户交互都没发生过时，AudioContext 会停在
 * suspended，这时播放会被拒绝。这里不硬刚 —— resume() 失败就静默跳过，
 * 不报错、不弹东西。实际使用中，能收到工具确认说明早就发过消息了。
 *
 * 用法：AlertSound.play()
 *       （没加载本文件的页面拿不到这个全局对象，调用方需自行判空）
 */
(function () {
    /* ================= 可调参数 ================= */

    // 整体音量（0~1）。嫌吵先动这个，别急着改波形
    var VOLUME = 0.16;

    // 节流：多少毫秒内只响一次。
    // 一批工具可能同时请求确认，不节流会连成一串，反而听不清
    var THROTTLE = 800;

    // 两声上行（A5 → E5 的八度上方的纯五度），听感是「有件事来了」。
    // 想改成下行提示音，把两个 freq 对调即可
    var TONES = [
        { freq: 880.0,  at: 0.00, dur: 0.45, vol: 1.00 },   // A5
        { freq: 1318.5, at: 0.12, dur: 0.60, vol: 0.85 }    // E6
    ];

    // 泛音倍数：1 = 基频，2/3 各叠一层，音色才不至于干巴巴。
    // 只想听纯净正弦的话，把这里改成 [1]
    var OVERTONES = [1, 2, 3];

    /* =========================================== */

    var ctx = null;
    var lastPlayedAt = 0;

    function ensureCtx() {
        if (ctx) return ctx;
        var AC = window.AudioContext || window.webkitAudioContext;
        if (!AC) return null;
        try {
            ctx = new AC();
        } catch (e) {
            ctx = null;
        }
        return ctx;
    }

    // 敲一下：每个泛音一个振荡器 + 一条独立的音量包络，各自衰减
    function strike(c, freq, at, dur, vol) {
        for (var i = 0; i < OVERTONES.length; i++) {
            var osc = c.createOscillator();
            var gain = c.createGain();

            osc.type = 'sine';
            osc.frequency.value = freq * OVERTONES[i];

            // 泛音越高越轻，否则会变成刺啦的电子音
            var peak = vol / (i * 2.2 + 1);

            gain.gain.setValueAtTime(0, at);
            // 8ms 起音，够脆；起音太长就变成「呜」而不是「叮」了
            gain.gain.linearRampToValueAtTime(peak, at + 0.008);
            // 指数衰减到极小值收尾（exponentialRamp 的终点不能是 0，会报错）
            gain.gain.exponentialRampToValueAtTime(0.0001, at + dur);

            osc.connect(gain);
            gain.connect(c.destination);
            osc.start(at);
            osc.stop(at + dur + 0.05);
        }
    }

    function render(c) {
        // 往后挪 20ms 再排程，避免第一帧被调度抖动吃掉
        var t0 = c.currentTime + 0.02;
        for (var i = 0; i < TONES.length; i++) {
            var t = TONES[i];
            strike(c, t.freq, t0 + t.at, t.dur, VOLUME * t.vol);
        }
    }

    function play() {
        var now = Date.now();
        if (now - lastPlayedAt < THROTTLE) return;
        lastPlayedAt = now;

        var c = ensureCtx();
        if (!c) return;

        if (c.state === 'suspended') {
            // 没发生过用户交互时 resume 会被拒 —— 那就静默什么都不做
            c.resume().then(function () { render(c); }, function () {});
            return;
        }
        render(c);
    }

    // 页面切回前台时，AudioContext 可能已经不是 running 了，顺手拉一把
    document.addEventListener('visibilitychange', function () {
        if (!document.hidden && ctx && ctx.state === 'suspended') {
            ctx.resume().catch(function () {});
        }
    });

    window.AlertSound = { play: play };
})();
