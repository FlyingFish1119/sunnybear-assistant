/**
 * 角色名标签（顶栏左栏插件，锚点 'topbar-left'）。
 *
 * 原为 character_index 顶栏内联的「角色名 · 模型名」一段；重写后作为插件组件，
 * 通过 TopbarPlugins.registerSlot('topbar-left', ...) 挂载。
 *
 * 角色信息由页面在启动时写入页面级共享对象 CharacterPage（见 character_index.html），
 * 本组件只读展示，不自行请求接口。
 *
 * Props:
 *   mainColor — String  主题色
 *
 * Injects:
 *   characterPage — 页面级共享状态 { characterInfo }
 */
const CharacterNameTag = {
    name: 'CharacterNameTag',

    template: `
    <span class="model-name-tag">
        <span v-if="character"
              :style="{'--main-color': mainColor}"
              style="font-weight:600;color:var(--main-color);white-space:nowrap">{{ character.name }}</span>
        <span v-if="character" style="color:#999">·</span>
    </span>`,

    props: {
        mainColor: { type: String, default: 'lightsalmon' }
    },

    inject: {
        characterPage: { default: null }
    },

    computed: {
        character: function () {
            return this.characterPage ? this.characterPage.characterInfo : null;
        }
    }
};
