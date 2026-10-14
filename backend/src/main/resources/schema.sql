-- 直播自动切片工具 · H2 建表脚本（字段与需求文档 §5 数据模型一致）
-- spring.sql.init.mode=always，重复执行安全（CREATE TABLE IF NOT EXISTS）

CREATE TABLE IF NOT EXISTS live_room (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    platform        VARCHAR(20),
    room_url        VARCHAR(500),
    room_name       VARCHAR(100),
    anchor_name     VARCHAR(100),
    live_title      VARCHAR(200),
    monitor_enabled BOOLEAN   DEFAULT TRUE,
    auto_record     BOOLEAN   DEFAULT TRUE,
    auto_publish    BOOLEAN   DEFAULT TRUE,
    analyze_template VARCHAR(30) DEFAULT 'highlight',
    status          VARCHAR(20) DEFAULT 'MONITORING',
    last_live_at    TIMESTAMP,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP
);

CREATE TABLE IF NOT EXISTS recording_session (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id         BIGINT,
    room_name       VARCHAR(100),
    platform        VARCHAR(20),
    status          VARCHAR(20),
    started_at      TIMESTAMP,
    ended_at        TIMESTAMP,
    duration_sec    INT,
    recorded_sec    INT       DEFAULT 0,
    file_path       VARCHAR(500),
    file_size_bytes BIGINT    DEFAULT 0,
    analyze_status  VARCHAR(20) DEFAULT 'NONE',
    created_at      TIMESTAMP
);

CREATE TABLE IF NOT EXISTS clip (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id    BIGINT,
    room_id       BIGINT,
    room_name     VARCHAR(100),
    start_sec     INT,
    end_sec       INT,
    duration_sec  INT,
    score         INT,
    ai_reason     VARCHAR(500),
    danmaku_heat  INT,
    danmaku_count   INT,
    danmaku_per_min INT,
    peak_per_min    INT,
    unique_users    INT,
    top_danmaku     VARCHAR(1000),
    anchor_name     VARCHAR(100),
    status        VARCHAR(20) DEFAULT 'PENDING',
    file_path     VARCHAR(500),
    created_at    TIMESTAMP
);

CREATE TABLE IF NOT EXISTS work (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    clip_id          BIGINT,
    room_name        VARCHAR(100),
    title            VARCHAR(200),
    description      VARCHAR(1000),
    tags             VARCHAR(500),
    cover_path       VARCHAR(500),
    subtitle_enabled BOOLEAN  DEFAULT TRUE,
    subtitle_style   VARCHAR(30),
    aspect_ratio     VARCHAR(10),
    status           VARCHAR(20) DEFAULT 'DRAFT',
    output_path      VARCHAR(500),
    score            INT,
    duration_sec     INT,
    created_at       TIMESTAMP,
    updated_at       TIMESTAMP
);

CREATE TABLE IF NOT EXISTS review_record (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    work_id    BIGINT,
    decision   VARCHAR(20),
    confidence INT,
    reason     VARCHAR(500),
    operator   VARCHAR(20),
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS publish_task (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    work_id      BIGINT,
    work_title   VARCHAR(200),
    platform     VARCHAR(20),
    status       VARCHAR(20),
    progress     INT       DEFAULT 0,
    phase_sec    INT       DEFAULT 0,
    remote_id    VARCHAR(60),
    error_msg    VARCHAR(500),
    published_at TIMESTAMP,
    created_at   TIMESTAMP,
    view_count     INT,
    like_count     INT,
    danmaku_count  INT,
    view_synced_at TIMESTAMP
);

-- 合集（多作品串联，功能 M4）
CREATE TABLE IF NOT EXISTS compilation (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    title              VARCHAR(200),
    work_ids           VARCHAR(1000),
    work_count         INT,
    total_duration_sec INT,
    status             VARCHAR(20) DEFAULT 'DRAFT',
    output_path        VARCHAR(500),
    progress           INT         DEFAULT 0,
    error_msg          VARCHAR(500),
    created_at         TIMESTAMP,
    updated_at         TIMESTAMP
);

CREATE TABLE IF NOT EXISTS pipeline_job (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_type    VARCHAR(20),
    entity_type VARCHAR(20),
    entity_id   BIGINT,
    status      VARCHAR(20),
    progress    INT       DEFAULT 0,
    retry_count INT       DEFAULT 0,
    log         TEXT,
    started_at  TIMESTAMP,
    finished_at TIMESTAMP,
    created_at  TIMESTAMP
);

-- key / value 已通过 JDBC URL 的 NON_KEYWORDS 解除 H2 保留字限制
CREATE TABLE IF NOT EXISTS app_setting (
    key   VARCHAR(60) PRIMARY KEY,
    value TEXT
);

-- ---------- M4 新列：兼容旧库（H2 支持 ADD COLUMN IF NOT EXISTS，新库由上方 CREATE 直接带列） ----------
ALTER TABLE live_room ADD COLUMN IF NOT EXISTS auto_publish BOOLEAN DEFAULT TRUE;
ALTER TABLE publish_task ADD COLUMN IF NOT EXISTS view_count INT;
ALTER TABLE publish_task ADD COLUMN IF NOT EXISTS like_count INT;
ALTER TABLE publish_task ADD COLUMN IF NOT EXISTS danmaku_count INT;
ALTER TABLE publish_task ADD COLUMN IF NOT EXISTS view_synced_at TIMESTAMP;

-- ---------- M5 新列：切片页弹幕统计增强（FR-P4-06） ----------
ALTER TABLE clip ADD COLUMN IF NOT EXISTS danmaku_count INT;
ALTER TABLE clip ADD COLUMN IF NOT EXISTS danmaku_per_min INT;
ALTER TABLE clip ADD COLUMN IF NOT EXISTS peak_per_min INT;
ALTER TABLE clip ADD COLUMN IF NOT EXISTS unique_users INT;
ALTER TABLE clip ADD COLUMN IF NOT EXISTS top_danmaku VARCHAR(1000);
ALTER TABLE clip ADD COLUMN IF NOT EXISTS anchor_name VARCHAR(100);

-- ---------- 轮播去重：片段内容指纹（8×8 灰度关键帧平均哈希，hex） ----------
ALTER TABLE clip ADD COLUMN IF NOT EXISTS fingerprint VARCHAR(500);

-- ---------- 断流可见性：录制会话记录断流重连次数 ----------
ALTER TABLE recording_session ADD COLUMN IF NOT EXISTS reconnect_count INT;

-- ---------- 高光集锦成片：整录自动选段拼接 ----------
ALTER TABLE work ADD COLUMN IF NOT EXISTS source_path VARCHAR(500);
ALTER TABLE work ADD COLUMN IF NOT EXISTS reel_segments VARCHAR(1000);

-- ---------- 房间备注名与直播标题分离：备注名不再被直播标题覆盖 ----------
ALTER TABLE live_room ADD COLUMN IF NOT EXISTS live_title VARCHAR(200);

-- ---------- 房间级发布标签：发布到 B站 时自动带上话题标签 ----------
ALTER TABLE live_room ADD COLUMN IF NOT EXISTS publish_tags VARCHAR(500);

-- ---------- 封面素材库：主播图片素材 + 标题文字合成封面（房间未配素材则退回成片截帧 + 文字） ----------
CREATE TABLE IF NOT EXISTS cover_asset (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id    BIGINT       NOT NULL,
    file_path  VARCHAR(500),
    file_name  VARCHAR(200),
    created_at TIMESTAMP
);

-- work.room_id：房间级封面素材按作品归属房间挑选（建作品时从会话/片段带过来）
-- work.cover_image：人工为单个作品指定的封面底图，优先级高于房间素材
-- 作品自身时长（成片真实秒数）：集锦由多段拼成，不能用单个片段的时长代替
ALTER TABLE work ADD COLUMN IF NOT EXISTS duration_sec INT;
ALTER TABLE work ADD COLUMN IF NOT EXISTS room_id BIGINT;
ALTER TABLE work ADD COLUMN IF NOT EXISTS cover_image VARCHAR(500);
