/**
 * 斜杠指令候选面板 —— 输入 / 时弹出，展示可用指令列表。
 * 支持二级选项：选中带 subCommand 的指令后，面板切换为选项列表（如会话列表）。
 *
 * Props:
 *   commands    — Array<{name, desc, usage, icon, subCommand?}>
 *   activeIndex — Number                             高亮项序号
 *   visible     — Boolean                            是否显示
 *   mainColor   — String                             主题色
 *   subOptions  — Array<{id, label, desc?}>          二级选项列表（有值时显示二级面板）
 *   subTitle    — String                             二级面板标题
 *
 * Emits:
 *   select     — 选中一级指令，参数为 command 对象
 *   subSelect  — 选中二级选项，参数为 option 对象
 *   back       — 从二级面板返回一级
 *   update:activeIndex — 鼠标 hover 时更新高亮
 *
 * 用法示例：
 *   <command-suggest
 *       :commands="filteredCommands"
 *       :active-index="commandActiveIndex"
 *       :visible="commandSuggestVisible"
 *       :main-color="mainColor"
 *       :sub-options="subOptions"
 *       :sub-title="'选择一个会话'"
 *       @select="onCommandSelect"
 *       @sub-select="onSubSelect"
 *       @back="onCommandBack"
 *       @update:active-index="idx => commandActiveIndex = idx"
 *   ></command-suggest>
 */
const CommandSuggest = {
    name: 'CommandSuggest',

    template: `
    <transition name="cmd-fade">
      <div v-if="visible && (commands.length > 0 || subOptions.length > 0)"
           class="command-suggest-panel"
           :style="{'--main-color': mainColor}">

        <!-- ====== 一级：指令列表 ====== -->
        <template v-if="subOptions.length === 0">
          <div class="command-suggest-header">
            <i data-lucide="terminal" style="width:14px;height:14px"></i>
            <span>可用指令</span>
            <span class="command-suggest-hint">
              <kbd>↑↓</kbd> 导航 &nbsp;<kbd>Enter</kbd> 选择 &nbsp;<kbd>Esc</kbd> 关闭
            </span>
          </div>
          <div
              v-for="(cmd, idx) in commands"
              :key="cmd.name"
              :class="['command-suggest-item', { active: idx === activeIndex }]"
              @click="$emit('select', cmd)"
              @mouseenter="$emit('update:activeIndex', idx)">
            <i :data-lucide="cmd.icon" class="command-suggest-icon"></i>
            <div class="command-suggest-body">
              <span class="command-suggest-name">{{ cmd.name }}</span>
              <span class="command-suggest-desc">{{ cmd.desc }}</span>
            </div>
            <span class="command-suggest-usage">{{ cmd.usage }}</span>
          </div>
        </template>

        <!-- ====== 二级：选项列表 ====== -->
        <template v-else>
          <div class="command-suggest-header sub-header">
            <i data-lucide="arrow-left"
               class="command-suggest-back"
               style="width:16px;height:16px;cursor:pointer"
               @click="$emit('back')"></i>
            <span>{{ subTitle }}</span>
            <span class="command-suggest-hint">
              <kbd>Enter</kbd> 选择 &nbsp;<kbd>Esc</kbd> 返回
            </span>
          </div>
          <div
              v-for="(opt, idx) in subOptions"
              :key="opt.id"
              :class="['command-suggest-item', { active: idx === activeIndex }]"
              @click="$emit('subSelect', opt)"
              @mouseenter="$emit('update:activeIndex', idx)">
            <i data-lucide="message-square" class="command-suggest-icon"></i>
            <div class="command-suggest-body">
              <span class="command-suggest-name">{{ opt.label }}</span>
              <span v-if="opt.desc" class="command-suggest-desc">{{ opt.desc }}</span>
            </div>
            <span class="command-suggest-usage">{{ opt.id }}</span>
          </div>
        </template>

      </div>
    </transition>
    `,

    props: {
        commands:    { type: Array,  default: () => [] },
        activeIndex: { type: Number, default: 0 },
        visible:     { type: Boolean, default: false },
        mainColor:   { type: String, default: 'lightsalmon' },
        subOptions:  { type: Array,  default: () => [] },
        subTitle:    { type: String, default: '' }
    },

    emits: ['select', 'subSelect', 'back', 'update:activeIndex'],

    watch: {
        activeIndex() {
            this.$nextTick(() => {
                const el = this.$el.querySelector('.command-suggest-item.active');
                if (el) el.scrollIntoView({ block: 'nearest' });
            });
        }
    },

    updated() {
        if (typeof lucide !== 'undefined') {
            this.$nextTick(() => lucide.createIcons());
        }
    }
};
