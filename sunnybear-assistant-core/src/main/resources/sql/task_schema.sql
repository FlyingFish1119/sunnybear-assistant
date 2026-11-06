-- 任务领域：任务、步骤、提示词模板

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
