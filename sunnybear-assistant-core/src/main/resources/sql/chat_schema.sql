-- 会话领域：会话、项目、消息
-- 会话是核心聚合；项目是围绕同一件事的会话分组；消息挂在会话下。

-- ChatSession 建表语句
CREATE TABLE IF NOT EXISTS chat_session (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    type TEXT NOT NULL DEFAULT 'chat',
    create_time TEXT NOT NULL,
    update_time TEXT NOT NULL,
    enable_pro INTEGER NOT NULL DEFAULT 0,
    -- 无审查模式：开启后该会话内所有工具的确认与 AI 危险审查失效（与工具内的 AUTO 模式语义相反）
    unreviewed INTEGER NOT NULL DEFAULT 0,
    -- 关联的定时任务 ID：type='cron' 时非空，用于把执行会话归到对应 cron_job 下
    cron_id INTEGER,
    -- 插件扩展字段（JSON 字符串，语义由各插件自行约定，核心层不解析）。角色/世界会话存放绑定资源 ID
    extension TEXT
);
-- 索引：按会话 ID 加速查询
CREATE INDEX IF NOT EXISTS idx_chat_session_id ON chat_session(id);
-- 索引：按 type 加速过滤
CREATE INDEX IF NOT EXISTS idx_chat_session_type ON chat_session(type);
-- 索引：按 cron_id 加速定时任务执行会话的归组查询
CREATE INDEX IF NOT EXISTS idx_chat_session_cron_id ON chat_session(cron_id);

-- ChatProject 项目表
-- 项目是独立元数据（不是会话），一个项目聚合多个围绕同一件事的会话；
-- 会话通过 chat_session.extension 里的 chat_project_id 反向关联本表 id（沿用角色会话存 characterId 的同一套做法）。
CREATE TABLE IF NOT EXISTS chat_project (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    -- 项目描述：主要用途是给 Jev 判定「这句话属不属于这个项目」当语义线索，可为空
    description TEXT NOT NULL DEFAULT '',
    -- 会话列表里那条竖色条的颜色（形如 #RRGGBB）；空串表示由前端按 id 兜底取色
    color       TEXT NOT NULL DEFAULT '',
    -- 自动追加到新会话首条消息的文件名清单（JSON 数组文本，如 ["PROJECT.md"]）；
    -- 只放项目根下的纯文件名；老库没有这列，由 ChatProjectRepositoryImplement 启动时补列
    auto_append_files TEXT NOT NULL DEFAULT '[]',
    create_time TEXT NOT NULL,
    update_time TEXT NOT NULL
);
-- 索引：按项目名加速查询（与 character_info 保持一致的取名习惯）
CREATE INDEX IF NOT EXISTS idx_chat_project_name ON chat_project(name);

-- ChatMessage 建表语句
CREATE TABLE IF NOT EXISTS chat_message (
    session_id  TEXT NOT NULL,
    id          TEXT PRIMARY KEY,
    parent_id   TEXT NULL ,
    tool_call_id TEXT NULL,
    name        TEXT NOT NULL,
    role        TEXT NOT NULL CHECK (role IN ('system', 'user', 'assistant', 'tool')),
    reasoning_content TEXT NULL,
    contents     TEXT NOT NULL DEFAULT '{}',
    tool_calls  TEXT NULL,
    extension  TEXT NULL,
    active      INTEGER NOT NULL DEFAULT TRUE,
    -- 分支记忆箭头：指向当前选中的孩子 id；NULL = 该层全亮（线性链 / 工具扇出 / 无选择）
    kid_index  TEXT NULL,
    create_time TEXT NOT NULL
);
-- 索引：按会话 ID 加速查询
CREATE INDEX IF NOT EXISTS idx_chat_message_session_id ON chat_message(session_id);
-- 索引：按消息 ID 加速查询
CREATE INDEX IF NOT EXISTS idx_chat_message_id ON chat_message(id);
-- 索引：按父级 ID 加速查询
CREATE INDEX IF NOT EXISTS idx_chat_message_parent_id ON chat_message(parent_id);
-- 索引：按创建时间加速排序
CREATE INDEX IF NOT EXISTS idx_chat_message_creat_time ON chat_message(create_time);
