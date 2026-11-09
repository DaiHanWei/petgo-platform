-- ops AI 查数（主题 ops-ai-query，spec §5.1，Story 1.4）
-- 对话与消息两张表；任务状态机的权威状态在 ai_query_messages.status（spec §4.1）。
-- 整数列一律 integer（不用 smallint）；状态 varchar + CHECK；对外标识用不可枚举 token。
-- 问题原文只存这里，不进审计、不进日志；对话按 updated_at 30 天后由清理任务删除（Story 2.2）。

CREATE TABLE ai_query_conversations (
    id                BIGSERIAL    PRIMARY KEY,
    token             VARCHAR(64)  NOT NULL,
    admin_account_id  BIGINT       NOT NULL REFERENCES admin_accounts (id),
    -- 问题前 80 字
    title             VARCHAR(80)  NOT NULL,
    -- 回答语言，只在新建对话时选择（spec D3）；枚举落库 UPPER_SNAKE，调用 agent 时转为 zh / en / id
    language          VARCHAR(8)   NOT NULL,
    -- Claude 会话 id，追问时 --resume 沿用
    agent_session_id  VARCHAR(128),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_ai_query_conversations_token UNIQUE (token),
    CONSTRAINT ck_ai_query_conversations_language CHECK (language IN ('ZH', 'EN', 'ID'))
);

-- 左栏：本人对话按最后更新倒序
CREATE INDEX idx_ai_query_conversations_owner_updated
    ON ai_query_conversations (admin_account_id, updated_at DESC);
-- 30 天清理
CREATE INDEX idx_ai_query_conversations_updated
    ON ai_query_conversations (updated_at);

CREATE TABLE ai_query_messages (
    id               BIGSERIAL     PRIMARY KEY,
    token            VARCHAR(64)   NOT NULL,
    conversation_id  BIGINT        NOT NULL REFERENCES ai_query_conversations (id) ON DELETE CASCADE,
    question         TEXT          NOT NULL,
    answer_md        TEXT,
    status           VARCHAR(16)   NOT NULL,
    error_code       VARCHAR(32),
    -- 导出文件清单 [{name, size}]
    files            JSONB         NOT NULL DEFAULT '[]'::jsonb,
    agent_secs       INTEGER,
    agent_turns      INTEGER,
    cost_usd         NUMERIC(10, 4),
    -- 提交给 agent 的时刻（PENDING → RUNNING）
    submitted_at     TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_ai_query_messages_token UNIQUE (token),
    CONSTRAINT ck_ai_query_messages_status CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'TIMEOUT')),
    CONSTRAINT ck_ai_query_messages_question_len CHECK (char_length(question) BETWEEN 1 AND 2000)
);

-- 扫描器只看未完成任务
CREATE INDEX idx_ai_query_messages_unfinished
    ON ai_query_messages (status, created_at)
    WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX idx_ai_query_messages_conversation
    ON ai_query_messages (conversation_id, id);
CREATE INDEX idx_ai_query_messages_updated
    ON ai_query_messages (updated_at);
