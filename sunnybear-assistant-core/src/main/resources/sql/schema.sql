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
    -- 插件扩展字段（JSON 字符串，语义由各插件自行约定，核心层不解析）。角色/世界会话存放绑定资源 ID
    extension TEXT
);
-- 索引：按会话 ID 加速查询
CREATE INDEX IF NOT EXISTS idx_chat_session_id ON chat_session(id);
-- 索引：按 type 加速过滤
CREATE INDEX IF NOT EXISTS idx_chat_session_type ON chat_session(type);

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


-- ChatModels 建表语句
CREATE TABLE IF NOT EXISTS chat_models (
    nick_name TEXT PRIMARY KEY,
    model_name TEXT NOT NULL,
    base_url TEXT NOT NULL,
    api_key TEXT NOT NULL
);
-- 索引：按模型名称加速查询
CREATE INDEX IF NOT EXISTS idx_chat_models_model_name ON chat_models(model_name);

-- KnowledgeEntry 知识库条目表
-- 每个条目为一条 wiki 式的词条（intro 简介 + content），embedding 仅对 intro 编码
-- 索引由 DatabaseMigrationRunner 统一创建（兼容旧库 title 列的迁移），此处不声明
CREATE TABLE IF NOT EXISTS knowledge_entry (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    intro       TEXT NOT NULL,
    content     TEXT NOT NULL,
    embedding   TEXT NOT NULL,
    create_time TEXT NOT NULL,
    update_time TEXT NOT NULL
);

-- SessionKnowledge session-知识库映射表
-- 记录某个会话已注入的知识条目 ID 列表（JSON 数组），确保知识注入的连续性
CREATE TABLE IF NOT EXISTS session_knowledge (
    id              TEXT PRIMARY KEY,
    session_id      TEXT NOT NULL UNIQUE,
    knowledge_ids   TEXT NOT NULL DEFAULT '[]',
    create_time     TEXT NOT NULL,
    update_time     TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_session_knowledge_session_id ON session_knowledge(session_id);

-- ChatMemory 建表语句
CREATE TABLE IF NOT EXISTS chat_memory (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    content     TEXT NOT NULL,
    create_time TEXT NOT NULL,
    update_time TEXT NOT NULL
);
-- 索引：按创建时间加速排序
CREATE INDEX IF NOT EXISTS idx_chat_memory_create_time ON chat_memory(create_time);


-- AiGreeting 建表语句
CREATE TABLE IF NOT EXISTS ai_greeting (
    id            TEXT PRIMARY KEY,
    text          TEXT NOT NULL,
    greeting_time TEXT NOT NULL,
    create_time   TEXT NOT NULL
);
-- 索引：按创建时间加速排序
CREATE INDEX IF NOT EXISTS idx_ai_greeting_create_time ON ai_greeting(create_time);

-- Task 建表语句
CREATE TABLE IF NOT EXISTS task (
    id          TEXT PRIMARY KEY,
    task_name   TEXT NOT NULL,
    task_desc   TEXT NOT NULL,
    status      TEXT NOT NULL,
    create_time TEXT NOT NULL,
    finish_time TEXT NULL
);
-- 索引：按创建时间加速排序
CREATE INDEX IF NOT EXISTS idx_task_create_time ON task(create_time);

-- TaskPrompt 提示词模板表
-- 预定义的 step 系统提示词，按 type 索引，TaskRunTool 查表获取不再由 AI 动态生成
CREATE TABLE IF NOT EXISTS task_prompt (
    type        TEXT PRIMARY KEY,
    prompt      TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    create_time TEXT NOT NULL,
    update_time TEXT NOT NULL
);

-- TaskStep 建表语句
CREATE TABLE IF NOT EXISTS task_step (
    id          TEXT PRIMARY KEY,
    task_id     TEXT NOT NULL,
    step_name   TEXT NOT NULL,
    step_desc   TEXT NOT NULL,
    result      TEXT NOT NULL,
    status      TEXT NOT NULL,
    sort        INTEGER NOT NULL DEFAULT 0,
    create_time TEXT NOT NULL,
    finish_time TEXT NULL
);
-- 索引：按任务 ID 加速查询
CREATE INDEX IF NOT EXISTS idx_task_step_task_id ON task_step(task_id);

CREATE TABLE IF NOT EXISTS cron_job (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    title       TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    cron        TEXT NOT NULL,
    message     TEXT NOT NULL,
    enable_pro  INTEGER NOT NULL DEFAULT 0,
    -- 无审查模式：开启后该定时任务触发的会话自动跳过工具确认与 AI 危险审查
    unreviewed  INTEGER NOT NULL DEFAULT 0,
    create_time TEXT NOT NULL,
    update_time TEXT NOT NULL
);
-- 索引：按定时任务 ID 加速查询
CREATE INDEX IF NOT EXISTS idx_cron_job_id ON cron_job(id);
