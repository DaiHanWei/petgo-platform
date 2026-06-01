-- PetGo 基线迁移（Story 1.1）。本 Story 不建任何业务表。
-- 放一张最小可验证表，证明 Flyway 迁移链路生效；后续业务表从 V2 起按执行顺序单调追加（决策 E2）。
CREATE TABLE schema_meta (
    key   VARCHAR(64)  PRIMARY KEY,
    value VARCHAR(255) NOT NULL
);

INSERT INTO schema_meta (key, value) VALUES ('baseline', '1.1');
