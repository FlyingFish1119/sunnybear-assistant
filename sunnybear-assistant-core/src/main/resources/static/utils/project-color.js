/**
 * 项目配色 —— 色板与取色规则的唯一出处。
 *
 * 为什么单独抽一个文件：项目面板要用它（色条、换色板），左侧会话列表也要用它
 * （会话行的竖色条）—— 两处必须是同一份，不然同一个项目在两边的颜色会对不上。
 *
 * 色板与后端 ChatProjectServiceImplement.PALETTE 是同一套（新建项目时后端按序分配），
 * 换色板要两边一起换。
 */
const ProjectColor = (function () {

    /** 低饱和的一小套，与后端 PALETTE 对齐 */
    var PALETTE = ['#E1785C', '#D9A441', '#7FA86B', '#5B93C7',
        '#9184C9', '#C97BA6', '#4FA39B', '#A8805B'];

    /**
     * 取项目色。
     * 项目自带 color 就用它；为空（老数据 / 后端没给）则按 id 哈希从色板里兜一个，
     * 保证同一个项目每次渲染颜色稳定，不会闪。
     */
    function of(project) {
        if (!project) return PALETTE[0];
        if (project.color) return project.color;
        var id = String(project.id || project.name || '');
        var sum = 0;
        for (var i = 0; i < id.length; i++) {
            sum = (sum * 31 + id.charCodeAt(i)) % 100003;
        }
        return PALETTE[sum % PALETTE.length];
    }

    /** 按项目 id 取色：会话行只知道 extension 里的 chat_project_id，用它查不到项目对象时兜底 */
    function ofId(projectId) {
        return of({ id: projectId });
    }

    return {
        PALETTE: PALETTE,
        of: of,
        ofId: ofId
    };
})();
