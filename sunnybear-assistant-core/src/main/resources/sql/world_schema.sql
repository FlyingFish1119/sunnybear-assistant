-- WorldInfo 世界观表（核心世界观 + 整体配置）
CREATE TABLE IF NOT EXISTS world_info (
    id                  TEXT PRIMARY KEY,
    name                TEXT NOT NULL,
    description         TEXT NOT NULL DEFAULT '',
    preset              TEXT NOT NULL DEFAULT '',
    background          TEXT NOT NULL DEFAULT '',
    main_color          TEXT NOT NULL DEFAULT '',   -- 世界观主题色
    narration_enable    INTEGER NOT NULL DEFAULT 1,   -- 旁白启用
    possess_name        TEXT NOT NULL DEFAULT '',     -- 玩家夺舍的角色 name，空串 = 不夺舍
    max_rounds          INTEGER NOT NULL DEFAULT 5,   -- 每轮最大轮数
    scheduler_ai_settings TEXT NOT NULL DEFAULT '{}', -- 调度器 AI 配置（JSON：adapterName/model，仅这两项）
    create_time         TEXT NOT NULL,
    update_time         TEXT NOT NULL
);
-- 索引：按世界观名称加速查询
CREATE INDEX IF NOT EXISTS idx_world_info_name ON world_info(name);

-- WorldCharacter 世界观下的群组角色表（id 主键 + 世界内 name 唯一）
CREATE TABLE IF NOT EXISTS world_character (
    id          TEXT PRIMARY KEY,
    world_id    TEXT NOT NULL,
    name        TEXT NOT NULL,
    ai_settings TEXT NOT NULL DEFAULT '{}',   -- 所选模型配置（JSON：adapterName/model/温度等，不含设定文本）
    setting     TEXT NOT NULL DEFAULT '',     -- 角色设定
    intro       TEXT NOT NULL DEFAULT '',     -- 简介
    avatar      TEXT NOT NULL DEFAULT '',     -- 头像 base64
    create_time TEXT NOT NULL,
    update_time TEXT NOT NULL
);
-- 每个世界观内角色名唯一
CREATE UNIQUE INDEX IF NOT EXISTS idx_world_character_world_name ON world_character(world_id, name);

-- WorldKnowledge 世界观知识条目表（id 为 UUID 文本主键）
CREATE TABLE IF NOT EXISTS world_knowledge (
    id          TEXT PRIMARY KEY,
    world_id    TEXT NOT NULL,
    title       TEXT NOT NULL DEFAULT '',
    content     TEXT NOT NULL DEFAULT '',
    create_time TEXT NOT NULL,
    update_time TEXT NOT NULL
);
-- 索引：按世界观加速查询
CREATE INDEX IF NOT EXISTS idx_world_knowledge_world_id ON world_knowledge(world_id);

-- WorldKnowledgeCharacter 知识↔角色 关联表（一条知识可被同一世界观下多个角色知晓）
-- 存角色稳定 id，角色改名不影响关联
CREATE TABLE IF NOT EXISTS world_knowledge_character (
    knowledge_id TEXT NOT NULL,
    character_id TEXT NOT NULL,
    PRIMARY KEY (knowledge_id, character_id)
);
-- 反查：某角色知晓哪些知识
CREATE INDEX IF NOT EXISTS idx_world_knowledge_character_char ON world_knowledge_character(character_id);

-- WorldSessionMapping 世界-会话映射表（群聊会话绑定到世界观，一个会话只属于一个世界）
CREATE TABLE IF NOT EXISTS world_session_mapping (
    id          TEXT PRIMARY KEY,
    session_id  TEXT NOT NULL UNIQUE,
    world_id    TEXT NOT NULL,
    create_time TEXT NOT NULL
);
-- 索引：按会话 ID 加速查询
CREATE INDEX IF NOT EXISTS idx_world_session_mapping_session ON world_session_mapping(session_id);
-- 索引：按世界观 ID 加速查询
CREATE INDEX IF NOT EXISTS idx_world_session_mapping_world ON world_session_mapping(world_id);
