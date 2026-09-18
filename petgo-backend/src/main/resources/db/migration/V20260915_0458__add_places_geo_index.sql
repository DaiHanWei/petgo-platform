-- 场所距离排序的索引（V1.3.0 batch-b1 Story 1.2 · AD-2 Rule 3）。
--
-- 🔴 **纬度、经度各建一条索引**（不是一条复合索引）：粗筛是
--   `latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?`
-- 两个都是范围条件。复合索引 (lat, lng) 只有第一列能做有效范围扫描，第二列退化成过滤 ——
-- 两条单列索引让 PostgreSQL 可以走 BitmapAnd 把两个范围都吃掉。
--
-- 部分索引只覆盖在架的行：已下架场所不进任何列表，不该占索引体积
-- （与 ix_places_active_recent 同一处理）。
--
-- 🛡 **本迁移仍然零扩展**：没有 CREATE EXTENSION，禁 PostGIS / cube / earthdistance（AD-2 Rule 1/4）。
-- 距离本身不在 SQL 里算 —— 在 SQL 里对列套三角函数会让上面这两条索引全部失效，
-- 变成全表扫描后再排序。粗筛交给索引、距离在应用层算，这正是 AD-2 Rule 2 的分工。
CREATE INDEX IF NOT EXISTS ix_places_active_latitude
    ON places (latitude)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS ix_places_active_longitude
    ON places (longitude)
    WHERE status = 'ACTIVE';

COMMENT ON INDEX ix_places_active_latitude  IS '距离排序矩形粗筛（纬度范围）；与经度索引走 BitmapAnd';
COMMENT ON INDEX ix_places_active_longitude IS '距离排序矩形粗筛（经度范围）';
