-- CharacterInfo 角色信息表
CREATE TABLE IF NOT EXISTS character_info (
    id           TEXT PRIMARY KEY,
    name         TEXT NOT NULL,
    avatar       TEXT NOT NULL DEFAULT '',
    background   TEXT NOT NULL DEFAULT '',
    ai_settings  TEXT NOT NULL DEFAULT '{}',
    preset       TEXT NOT NULL DEFAULT '',
    main_color   TEXT NOT NULL DEFAULT '',
    opacity      REAL NOT NULL DEFAULT 0.85,
    tools        TEXT NOT NULL DEFAULT '{}',
    chat_select  TEXT NOT NULL DEFAULT '{}',
    create_time  TEXT NOT NULL,
    update_time  TEXT NOT NULL
);
-- 索引：按角色名称加速查询
CREATE INDEX IF NOT EXISTS idx_character_info_name ON character_info(name);

-- CharacterSessionMapping 角色-会话映射表
CREATE TABLE IF NOT EXISTS character_session_mapping (
    id          TEXT PRIMARY KEY,
    session_id  TEXT NOT NULL UNIQUE,
    character_id TEXT NOT NULL,
    create_time TEXT NOT NULL
);
-- 索引：按会话 ID 加速查询
CREATE INDEX IF NOT EXISTS idx_character_session_mapping_session ON character_session_mapping(session_id);
-- 索引：按角色 ID 加速查询
CREATE INDEX IF NOT EXISTS idx_character_session_mapping_character ON character_session_mapping(character_id);

-- CharacterGlossary 角色词条表
CREATE TABLE IF NOT EXISTS character_glossary (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    character_id TEXT NOT NULL,
    keyword      TEXT NOT NULL,
    desc         TEXT NOT NULL DEFAULT '',
    content      TEXT NOT NULL DEFAULT '',
    create_time  TEXT NOT NULL,
    update_time  TEXT NOT NULL
);
-- 唯一约束：每个角色下 keyword 唯一
CREATE UNIQUE INDEX IF NOT EXISTS idx_character_glossary_char_keyword
    ON character_glossary(character_id, keyword);
-- 索引：按角色 ID 加速查询
CREATE INDEX IF NOT EXISTS idx_character_glossary_character_id
    ON character_glossary(character_id);
