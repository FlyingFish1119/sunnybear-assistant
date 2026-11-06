-- 定时任务领域：cron_job

CREATE TABLE IF NOT EXISTS cron_job (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    title       TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    cron        TEXT NOT NULL,
    message     TEXT NOT NULL,
    enable_pro  INTEGER NOT NULL DEFAULT 0,
    -- 无审查模式：开启后该定时任务触发的会话自动跳过工具确认与 AI 危险审查
    unreviewed  INTEGER NOT NULL DEFAULT 0,
    -- 单点模式：为 1 时只在 mode=local 的实例上调度，mode=remote 的实例直接跳过（多实例共用一套库用）
    local_only  INTEGER NOT NULL DEFAULT 0,
    create_time TEXT NOT NULL,
    update_time TEXT NOT NULL
);
-- 索引：按定时任务 ID 加速查询
CREATE INDEX IF NOT EXISTS idx_cron_job_id ON cron_job(id);
