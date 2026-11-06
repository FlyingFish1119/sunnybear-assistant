-- 问候领域：AI 问候语

-- AiGreeting 建表语句
CREATE TABLE IF NOT EXISTS ai_greeting (
    id            TEXT PRIMARY KEY,
    text          TEXT NOT NULL,
    suggestions   TEXT NOT NULL DEFAULT '[]',
    greeting_time TEXT NOT NULL,
    create_time   TEXT NOT NULL
);
-- 索引：按创建时间加速排序
CREATE INDEX IF NOT EXISTS idx_ai_greeting_create_time ON ai_greeting(create_time);
