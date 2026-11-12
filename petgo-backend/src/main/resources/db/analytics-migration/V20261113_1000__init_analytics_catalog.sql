-- analytics-pipeline Story 1.1：埋点库首条迁移（只在埋点库执行，历史表在埋点库内）。
-- 只建两张目录表，供 Story 1.2 把 event-catalog.yaml / metric-definitions.yaml 同步进来给 AI 查。
-- 🔴 本文件一旦被任何环境应用即冻结：缺列另起新迁移 ALTER，不要回头改这里。
-- 授权（GRANT SELECT 给 readerRole 占位符对应的只读角色）归 Story 1.2 的 V20261113_1010，本文件不写。
-- 埋点表一律 JdbcTemplate 访问，禁止 JPA 实体。

-- 事件清单（D-7）：事件名原样（含 'Application Opened' 这类带空格的）。
CREATE TABLE event_catalog (
    event        varchar(128) PRIMARY KEY,
    source       varchar(8)   NOT NULL,
    status       varchar(16)  NOT NULL,
    domain       varchar(32),
    description  text         NOT NULL,
    -- 上报点位置，供人和 AI 追溯
    code_ref     varchar(255),
    -- [{name,type,description,user_identifier}]
    properties   jsonb        NOT NULL DEFAULT '[]'::jsonb,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_event_catalog_source CHECK (source IN ('APP', 'SERVER', 'H5')),
    CONSTRAINT ck_event_catalog_status CHECK (status IN ('ACTIVE', 'PLANNED', 'RETIRED')),
    CONSTRAINT ck_event_catalog_properties_array CHECK (jsonb_typeof(properties) = 'array')
);

COMMENT ON TABLE event_catalog IS '事件清单（唯一来源 resources/analytics/event-catalog.yaml，启动时同步）';

-- 口径文档（D-9 / FR-26）：团队编号如 S1、电商#6。
CREATE TABLE metric_definitions (
    code         varchar(32)  PRIMARY KEY,
    name         varchar(128) NOT NULL,
    definition   text         NOT NULL,
    -- 来源表 / 事件
    sources      jsonb        NOT NULL DEFAULT '[]'::jsonb,
    exclusions   text,
    known_bias   text,
    -- resources/analytics/rollup/<指标族>.sql
    sql_path     varchar(255),
    -- [{date,change}]
    changelog    jsonb        NOT NULL DEFAULT '[]'::jsonb,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_metric_definitions_sources_array CHECK (jsonb_typeof(sources) = 'array'),
    CONSTRAINT ck_metric_definitions_changelog_array CHECK (jsonb_typeof(changelog) = 'array')
);

COMMENT ON TABLE metric_definitions IS '指标口径（唯一来源 resources/analytics/metric-definitions.yaml，启动时同步）';
