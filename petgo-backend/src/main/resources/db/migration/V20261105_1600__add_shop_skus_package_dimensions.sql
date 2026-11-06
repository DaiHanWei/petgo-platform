-- v1.4.2 shop · Story 1.1 · 迁移 ①（D-10、P-8 ①）：SKU 包裹参数
--
-- 🔴 任一列为空 = 该 SKU「暂不可售」（加购 / 立即购买 422 sku-not-sellable，购物车失效原因 NOT_SELLABLE）。
-- 单位：克 / 厘米，与 Biteship rates 接口 items[].weight / length / width / height 一致。
-- 四列可空、不回填：存量 SKU 由运营上线前补齐（上线检查项；stag 部署前用 SQL 补，严禁写进迁移）。
-- 整数列一律 INTEGER（Hibernate Integer → int4；SMALLINT 会让 ddl-auto=validate 失败）。

ALTER TABLE shop_skus ADD COLUMN package_weight_g  INTEGER;
ALTER TABLE shop_skus ADD COLUMN package_length_cm INTEGER;
ALTER TABLE shop_skus ADD COLUMN package_width_cm  INTEGER;
ALTER TABLE shop_skus ADD COLUMN package_height_cm INTEGER;

ALTER TABLE shop_skus ADD CONSTRAINT ck_shop_skus_package_weight CHECK (package_weight_g  IS NULL OR package_weight_g  > 0);
ALTER TABLE shop_skus ADD CONSTRAINT ck_shop_skus_package_length CHECK (package_length_cm IS NULL OR package_length_cm > 0);
ALTER TABLE shop_skus ADD CONSTRAINT ck_shop_skus_package_width  CHECK (package_width_cm  IS NULL OR package_width_cm  > 0);
ALTER TABLE shop_skus ADD CONSTRAINT ck_shop_skus_package_height CHECK (package_height_cm IS NULL OR package_height_cm > 0);
