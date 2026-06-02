-- Story 2.6：名片 H5。pet_profiles 增 og_image_url（社交预览预渲染 OG 静态图，存①公开桶+CDN）。
-- Flyway 序号：接 V5__init_health_events 之后单调分配（决策 E2）。schema 归 Flyway，ddl-auto=validate。

ALTER TABLE pet_profiles ADD COLUMN og_image_url VARCHAR(1024);
