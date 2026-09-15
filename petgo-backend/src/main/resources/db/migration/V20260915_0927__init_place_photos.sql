-- 场所照片（V1.3.0 batch-b1 Story 1.9 · FR-112.3「他人可为场所补充照片，标注上传者」）。
--
-- 🔴 **为什么必须从 `places.photo_urls` 那个 JSONB 数组搬出来**（Story 1.9 Dev Notes 要求写明理由）：
--    Story 1.1 建表时照片只是一个 URL 字符串数组 —— 那个形态**装不下本 story 要的三样东西**：
--    ① **每张照片的上传者**（AC2 要标注，而数组里只有 URL）；
--    ② **每张照片自己的审核态**（AC3 先发后审 —— 补充的照片在过审前只有上传者自己看得见）。
--       审核回调要改的是"某一张"，在 JSONB 数组上做就是读-改-写整个数组，
--       两条照片同时过审必丢一条（后写的用自己读到的旧数组覆盖）；
--    ③ **单张删除 / 下架**（同理）。
--    扩成"JSONB 数组里放对象"能装下字段，但装不下并发正确性 —— 所以另起一张表（CROSS-STORY C3：表名带模块前缀）。
--
-- 🔴 **单一事实源**：本迁移把存量 `places.photo_urls` 搬进来（上传者 = 标记人），
--    然后**删掉那一列**。留着它会变成第二份真相 —— 下一个人往哪边写都"对"，而读路径只认一边。
--    ⚠️ 这一列从未在任何环境被写入过真实数据以外的东西，且整个场所功能尚未发版；
--    若本迁移在某个已有数据的环境上跑，下面的 INSERT ... SELECT 会把数据完整搬过去，不丢。
CREATE TABLE IF NOT EXISTS place_photos (
    id                BIGSERIAL PRIMARY KEY,
    place_id          BIGINT       NOT NULL,
    -- 上传者（users.id）。AC2 要在详情页标注他。
    uploader_id       BIGINT       NOT NULL,
    -- 公开桶 CDN 全 URL。⚠️ **不是签名 URL** —— 签名 URL 禁入库（NFR-5）。
    url               VARCHAR(1024) NOT NULL,
    -- 审核态（AC3 先发后审），取值域同 Java 枚举 CommentModerationStatus。
    -- 标记场所时一并提交的那批走的是**同步富审核**（已在 Story 1.3 过了图审），所以落 VISIBLE；
    -- 事后补充的落 UNDER_REVIEW，过审才对他人可见。
    moderation_status VARCHAR(24)  NOT NULL DEFAULT 'VISIBLE',
    -- 展示顺序：同一场所内按它升序，相同就按 id。标记人首批按提交顺序 0..n-1，
    -- 补充的排在后面（取当前最大 +1），这样"首图"永远是标记人那张。
    sort_order        INTEGER      NOT NULL DEFAULT 0,
    -- 🔴 是不是"标记这个场所时一并提交的那批"。
    -- 它与"上传者是不是标记人"**不是一回事**：标记人事后也可以给自己标的场所补图，
    -- 那些属于补充照片。注销级联要豁免的是**前者**（场所条目本身的资料：首图 / OG 预览图
    -- 都取它，随人一起隐藏会把整个场所变成无图条目），不是"这个人传的所有图"。
    -- 存成一列而不是每次去比对 places.created_by：后者既判错、又是一次按行的额外查询。
    is_original       BOOLEAN      NOT NULL DEFAULT false,
    deleted_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 读路径：某场所的未删照片按展示顺序。
CREATE INDEX IF NOT EXISTS ix_place_photos_place_order
    ON place_photos (place_id, sort_order, id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS ix_place_photos_uploader
    ON place_photos (uploader_id);

-- 存量搬迁：把 places.photo_urls 里的每个元素展开成一行，上传者 = 标记人，顺序 = 数组下标。
-- ⚠️ 用 WITH ORDINALITY 拿下标；空数组 / NULL 的场所自然不产生行。
INSERT INTO place_photos (place_id, uploader_id, url, moderation_status, sort_order, is_original,
                          created_at, updated_at)
SELECT p.id, p.created_by, elem.url, 'VISIBLE', elem.ord - 1, true, p.created_at, p.created_at
FROM places p
CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(p.photo_urls, '[]'::jsonb))
     WITH ORDINALITY AS elem(url, ord)
WHERE NOT EXISTS (SELECT 1 FROM place_photos pp WHERE pp.place_id = p.id);

-- 🔴 删列：单一事实源。留着它 = 第二份真相。
ALTER TABLE places DROP COLUMN IF EXISTS photo_urls;

COMMENT ON TABLE  place_photos                   IS '场所照片（Story 1.9）；每张带上传者与自己的审核态，取代 places.photo_urls';
COMMENT ON COLUMN place_photos.uploader_id       IS '上传者 users.id —— 详情页要标注他（AC2）';
COMMENT ON COLUMN place_photos.url               IS '公开桶 CDN 全 URL，非签名 URL（NFR-5）';
COMMENT ON COLUMN place_photos.moderation_status IS '审核态，取值域同 Java 枚举 CommentModerationStatus；补充的照片先发后审';
COMMENT ON COLUMN place_photos.sort_order        IS '同场所内的展示顺序；标记人首批 0..n-1，补充的排在后面';
COMMENT ON COLUMN place_photos.is_original       IS '是否为标记场所时一并提交的那批（注销级联豁免它，因为它是场所条目本身的资料）';
