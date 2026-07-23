/**
 * 战斗面板组件 —— 回合制 TRPG 战斗 UI。
 *
 * 监听 WebSocket 推送的 BATTLE_TURN 信号，展示双方状态、技能按钮和回合叙事。
 * 玩家点击技能后将 BattleTurnAction 回传后端，等待下一回合或战斗结束。
 *
 * Props:
 *   mainColor      — String  主题色
 *   sessionId      — String  当前会话 ID，用于回传行动时关联战斗
 *
 * 公开方法（通过 ref 调用）：
 *   show(turnAsk)  — 展示回合数据，turnAsk = BattleTurnAsk JSON 对象
 *   end(narrative) — 战斗结束，展示结局叙事，几秒后自动关闭
 */
const BattlePanel = {
    name: 'BattlePanel',

    template: `
    <div v-if="visible" class="battle-overlay" @click.self="() => {}">
      <div class="battle-dialog" ref="dialog">
        <!-- 头部 -->
        <div class="battle-header">
          <div class="battle-header-left">
            <div class="battle-header-icon">
              <i data-lucide="swords"></i>
            </div>
            <span class="battle-header-title">⚔️ 战斗</span>
            <span class="battle-header-round-num">第 {{ turnData.round }} 回合</span>
          </div>
          <span class="battle-header-round" :class="{ active: turnData.canAct }">
            {{ turnData.canAct ? '轮到你了' : '结算中...' }}
          </span>
        </div>

        <!-- 战斗消息滚动区 -->
        <div v-if="turnData.messages && turnData.messages.length > 0" class="battle-messages" ref="messagesArea">
          <div v-for="(msg, idx) in turnData.messages" :key="idx" class="battle-message-item">
            <div class="battle-message-marker"></div>
            <div class="battle-message-text markdown-body" v-html="$md.render(msg)"></div>
          </div>
        </div>

        <!-- 双方面板 -->
        <div class="battle-vs-row">
          <!-- 玩家 -->
          <div class="battle-char-card player">
            <div class="battle-char-name">{{ turnData.player.name }}</div>
            <div class="battle-stat-row">
              <span class="battle-stat-label">HP</span>
              <div class="battle-stat-bar">
                <div class="battle-stat-fill hp"
                     :style="hpBarStyle(turnData.player)">
                </div>
              </div>
              <span class="battle-stat-num" :class="{ low: turnData.player.hp / turnData.player.maxHp < 0.25 }">
                {{ turnData.player.hp }} / {{ turnData.player.maxHp }}
              </span>
            </div>
            <div class="battle-stat-row">
              <span class="battle-stat-label">MP</span>
              <div class="battle-stat-bar">
                <div class="battle-stat-fill mp"
                     :style="mpBarStyle(turnData.player)">
                </div>
              </div>
              <span class="battle-stat-num" :class="{ low: turnData.player.mp / turnData.player.maxMp < 0.25 }">
                {{ turnData.player.mp }} / {{ turnData.player.maxMp }}
              </span>
            </div>
            <div v-if="turnData.playerBuffs && turnData.playerBuffs.length > 0" class="battle-buff-list">
              <span v-for="b in turnData.playerBuffs" class="battle-buff-tag" :class="{ expiring: b.remainingTurns !== undefined && b.remainingTurns >= 0 && b.remainingTurns <= 1 }">
                {{ b.name }}
                <span v-if="b.remainingTurns !== undefined && b.remainingTurns >= 0" class="battle-buff-turns">
                  {{ b.remainingTurns }}回合
                </span>
              </span>
            </div>
          </div>

          <!-- VS -->
          <div class="battle-vs-divider">
            <span>VS</span>
          </div>

          <!-- 敌人 -->
          <div class="battle-char-card enemy">
            <div class="battle-char-name">{{ turnData.enemy.name }}</div>
            <div class="battle-stat-row">
              <span class="battle-stat-label">HP</span>
              <div class="battle-stat-bar">
                <div class="battle-stat-fill hp"
                     :style="hpBarStyle(turnData.enemy)">
                </div>
              </div>
              <span class="battle-stat-num" :class="{ low: turnData.enemy.hp / turnData.enemy.maxHp < 0.25 }">
                {{ turnData.enemy.hp }} / {{ turnData.enemy.maxHp }}
              </span>
            </div>
            <div class="battle-stat-row">
              <span class="battle-stat-label">MP</span>
              <div class="battle-stat-bar">
                <div class="battle-stat-fill mp"
                     :style="mpBarStyle(turnData.enemy)">
                </div>
              </div>
              <span class="battle-stat-num" :class="{ low: turnData.enemy.mp / turnData.enemy.maxMp < 0.25 }">
                {{ turnData.enemy.mp }} / {{ turnData.enemy.maxMp }}
              </span>
            </div>
            <div v-if="turnData.enemyBuffs && turnData.enemyBuffs.length > 0" class="battle-buff-list">
              <span v-for="b in turnData.enemyBuffs" class="battle-buff-tag enemy-buff" :class="{ expiring: b.remainingTurns !== undefined && b.remainingTurns >= 0 && b.remainingTurns <= 1 }">
                {{ b.name }}
                <span v-if="b.remainingTurns !== undefined && b.remainingTurns >= 0" class="battle-buff-turns">
                  {{ b.remainingTurns }}回合
                </span>
              </span>
            </div>
          </div>
        </div>

        <!-- 技能选择区 -->
        <div class="battle-skills-section">
          <div class="battle-skills-title">
            <i data-lucide="crosshair" style="width:16px;height:16px"></i>
            <span>选择技能</span>
          </div>
          <!-- 战斗已结束 -->
          <div v-if="battleEnded" class="battle-waiting">
            <span style="font-size:16px">⚔️ 战斗已结束</span>
          </div>
          <!-- 已提交行动，等待 GM 结算 -->
          <div v-else-if="waiting" class="battle-waiting">
            <div class="battle-waiting-spinner"></div>
            <span>行动已提交，等待回合结算...</span>
          </div>
          <!-- 非玩家回合（GM 结算敌人行动等），等待下一轮 -->
          <div v-else-if="!turnData.canAct" class="battle-waiting">
            <div class="battle-waiting-spinner"></div>
            <span>请等待当前回合结算完成...</span>
          </div>
          <!-- 玩家回合，显示技能按钮 -->
          <div v-else>
            <div class="battle-skills-grid">
              <button v-for="(skill, idx) in turnData.playerSkills"
                      :key="idx"
                      class="battle-skill-btn"
                      :style="{'--main-color': mainColor}"
                      :disabled="insufficientMp(skill)"
                      @click="selectSkill(idx)">
                <div class="battle-skill-name">{{ skill.name }}</div>
                <div v-if="skill.damageDice" class="battle-skill-dice">
                  <i data-lucide="dices" style="width:13px;height:13px"></i>
                  {{ skill.damageDice }}
                </div>
                <div class="battle-skill-meta">
                  <span v-if="skill.cost > 0" class="battle-skill-cost" :class="{ 'mp-low': turnData.player.mp < skill.cost }">
                    MP {{ skill.cost }}
                  </span>
                  <span v-if="skill.difficulty > 0" class="battle-skill-dc">DC {{ skill.difficulty }}</span>
                </div>
                <div v-if="skill.effect" class="battle-skill-effect">{{ skill.effect }}</div>
              </button>
            </div>
            <!-- 特殊行动按钮 -->
            <div class="battle-special-actions">
              <button class="battle-special-btn flee"
                      :style="{'--main-color': mainColor}"
                      @click="specialAction('flee')">
                <i data-lucide="log-out" style="width:14px;height:14px"></i>
                <span>逃跑</span>
              </button>
              <button class="battle-special-btn surrender"
                      @click="specialAction('surrender')">
                <i data-lucide="flag" style="width:14px;height:14px"></i>
                <span>放弃</span>
              </button>
            </div>
          </div>
        </div>

        <!-- 战斗结束：关闭按钮 -->
        <div v-if="battleEnded" class="battle-ended-bar">
          <button class="battle-close-btn" :style="{'--main-color': mainColor}" @click="closePanel">
            关闭战斗面板
          </button>
        </div>
      </div>
    </div>

    <!-- 确认弹窗 -->
    <confirm-dialog ref="confirmDialog" :main-color="mainColor"></confirm-dialog>`,

    props: {
        mainColor: { type: String, default: 'lightsalmon' },
        sessionId: { type: String, default: '' }
    },

    emits: [],

    data() {
        return {
            visible: false,
            waiting: false,
            battleEnded: false,
            turnData: {
                round: 1,
                messages: [],
                canAct: false,
                player: { name: '', hp: 0, mp: 0, maxHp: 0, maxMp: 0 },
                enemy: { name: '', hp: 0, mp: 0, maxHp: 0, maxMp: 0 },
                playerSkills: [],
                enemySkills: [],
                playerBuffs: [],
                enemyBuffs: []
            }
        };
    },

    methods: {
        /* ---- 公开方法 ---- */

        show(turnAsk) {
            this.turnData = turnAsk;
            if (turnAsk.canAct) {
                this.waiting = false;
            }
            this.visible = true;
            this.$nextTick(() => {
                this.refreshIcons();
                this.scrollMessagesToBottom();
                const el = this.$refs.dialog;
                if (el) el.focus();
            });
        },

        scrollMessagesToBottom() {
            const area = this.$refs.messagesArea;
            if (area) {
                area.scrollTop = area.scrollHeight;
            }
        },

        /** 战斗结束：将结局叙事追加到消息列表，锁定面板 */
        end(narrative) {
            this.visible = true;
            this.waiting = false;
            this.battleEnded = true;
            // 结局叙事追加到消息末尾
            if (this.turnData.messages) {
                this.turnData.messages.push('---\n\n### ⚔️ 战斗结束\n\n' + narrative);
            }
            this.turnData.canAct = false;
            this.$nextTick(() => {
                this.refreshIcons();
                this.scrollMessagesToBottom();
            });
        },

        closePanel() {
            this.visible = false;
            this.battleEnded = false;
        },

        /* ---- 内部方法 ---- */

        selectSkill(skillIndex) {
            this.submitAction({ sessionId: this.sessionId, skillIndex: skillIndex });
        },

        specialAction(type) {
            if (this.waiting) return;
            const isFlee = type === 'flee';
            const title = isFlee ? '逃跑' : '放弃战斗';
            const message = isFlee
                ? '确定要尝试逃跑吗？需要进行 D20 检定，失败则浪费本回合。'
                : '确定要放弃战斗吗？这将直接判定为失败。';
            const confirmType = isFlee ? 'warning' : 'danger';
            this.$refs.confirmDialog.show({
                title: title,
                message: message,
                confirmText: isFlee ? '逃跑' : '放弃',
                cancelText: '取消',
                type: confirmType
            }).then(() => {
                this.submitAction({ sessionId: this.sessionId, special: type });
            }).catch(() => {});
        },

        submitAction(action) {
            if (this.waiting) return;
            this.waiting = true;

            API.post('character/battle/action', action)
                .then(result => {
                    if (result.status !== 200) {
                        console.error('提交战斗行动失败:', result.message);
                        this.waiting = false;
                        ElementPlus.ElMessage.error(result.message || '提交行动失败');
                    }
                    // 成功时不重置 waiting，等待下一回合推送
                })
                .catch(err => {
                    console.error('提交战斗行动网络错误:', err);
                    this.waiting = false;
                    ElementPlus.ElMessage.error('网络请求失败，请检查网络连接');
                });
        },

        /* ---- 显示辅助 ---- */

        hpBarStyle(state) {
            if (!state || state.maxHp <= 0) return { width: '0%' };
            const pct = Math.round((state.hp / state.maxHp) * 100);
            return { width: Math.max(0, pct) + '%' };
        },

        mpBarStyle(state) {
            if (!state || state.maxMp <= 0) return { width: '0%' };
            const pct = Math.round((state.mp / state.maxMp) * 100);
            return { width: Math.max(0, pct) + '%' };
        },

        insufficientMp(skill) {
            if (!skill || !skill.cost) return false;
            return this.turnData.player.mp < skill.cost;
        },

        refreshIcons() {
            if (typeof lucide !== 'undefined') {
                this.$nextTick(() => lucide.createIcons());
            }
        }
    },

    watch: {
        visible(val) {
            if (!val) this.waiting = false;
        }
    },

    mounted() {
        this.refreshIcons();
    },

    updated() {
        this.refreshIcons();
    }
};
