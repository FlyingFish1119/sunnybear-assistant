-- ChatSession 建表语句
CREATE TABLE IF NOT EXISTS chat_session (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    create_time TEXT NOT NULL,
    update_time TEXT NOT NULL,
    enable_pro INTEGER NOT NULL DEFAULT 0
);
-- 索引：按会话 ID 加速查询
CREATE INDEX IF NOT EXISTS idx_chat_session_id ON chat_session(id);

-- 迁移：如果表已存在但缺少 enable_pro 列：
-- ALTER TABLE chat_session ADD COLUMN enable_pro INTEGER NOT NULL DEFAULT 0;


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
-- 每个条目为一条 wiki 式的词条（title + content），embedding 仅对 title 编码
CREATE TABLE IF NOT EXISTS knowledge_entry (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    title       TEXT NOT NULL,
    content     TEXT NOT NULL,
    embedding   TEXT NOT NULL,
    create_time TEXT NOT NULL,
    update_time TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_knowledge_entry_title ON knowledge_entry(title);

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

