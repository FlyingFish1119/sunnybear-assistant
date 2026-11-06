-- 知识领域：知识库条目 + 会话注入记录

-- KnowledgeEntry 知识库条目表
-- 每个条目为一条 wiki 式的词条（intro 简介 + content），对话时由 LLM 依据 intro 自动挑选注入
CREATE TABLE IF NOT EXISTS knowledge_entry (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    intro       TEXT NOT NULL,
    content     TEXT NOT NULL,
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
