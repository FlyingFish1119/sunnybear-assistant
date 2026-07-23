/**
 * 颜色工具
 *
 * 无外部依赖，纯计算。
 */
const ColorUtils = (function () {

    /**
     * 将颜色与白色混合，返回更浅的颜色
     * @param {string} color - 任意 CSS 颜色格式
     * @param {number} amount - 主色占比 (0~1)，越小越浅
     * @returns {string} rgb(r, g, b) 格式
     */
    function lighten(color, amount) {
        var div = document.createElement('div');
        div.style.color = color;
        div.style.display = 'none';
        document.body.appendChild(div);
        var computed = getComputedStyle(div).color;
        document.body.removeChild(div);
        var match = computed.match(/[\d.]+/g);
        if (!match || match.length < 3) return color;
        var r = parseInt(match[0]);
        var g = parseInt(match[1]);
        var b = parseInt(match[2]);
        var mr = Math.round(r + (255 - r) * (1 - amount));
        var mg = Math.round(g + (255 - g) * (1 - amount));
        var mb = Math.round(b + (255 - b) * (1 - amount));
        return 'rgb(' + mr + ', ' + mg + ', ' + mb + ')';
    }

    return { lighten: lighten };
})();
