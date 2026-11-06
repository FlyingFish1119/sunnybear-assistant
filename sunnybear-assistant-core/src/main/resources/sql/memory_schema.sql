-- 记忆领域：长期记忆 + 自动记忆整理待办

-- ChatMemory 建表语句
-- group_name：分组名（如「我是谁」「我的习惯」）
CREATE TABLE IF NOT EXISTS chat_memory (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    content     TEXT NOT NULL,
    group_name  TEXT NOT NULL DEFAULT '未分类',
    create_time TEXT NOT NULL,
    update_time TEXT NOT NULL
);
-- 索引：按创建时间加速排序
CREATE INDEX IF NOT EXISTS idx_chat_memory_create_time ON chat_memory(create_time);

-- SessionMemoryJob 自动记忆整理待办表
-- 一行 = 一段等待整理的对话待办，不是账本：整理完就把整行删掉。
-- 删行这个动作同时充当抢占锁：DELETE ... WHERE session_id=? AND last_message_time=? 删到 1 行才算归我跑，
-- 删到 0 行说明已被别处拿走、或这期间用户又说话了（touch 已插入新的一行）。
CREATE TABLE IF NOT EXISTS session_memory_job (
    session_id        TEXT PRIMARY KEY,
    -- 到期时刻：最后一条用户消息时间 + 空闲小时数
    due_time          TEXT NOT NULL,
    -- 本段对话的计时依据（最后一条用户消息时间），兼作"这一轮待办"的版本号
    last_message_time TEXT NOT NULL
);
-- 索引：启动扫描与到期查询按到期时间过滤
CREATE INDEX IF NOT EXISTS idx_session_memory_job_due ON session_memory_job(due_time);
