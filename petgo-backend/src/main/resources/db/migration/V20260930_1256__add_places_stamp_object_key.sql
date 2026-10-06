-- V1.3.2 batch-a · Story 1.4 —— 场所专属章（后台 AB-18B · 架构 delta AD-5 / AD-18 · 决策 D-10）。
--
-- · 可空：绝大多数场所一直用按 place_type 的默认章（客户端包内）；非空 = 运营在后台上传的专属章。
-- · 章面 URL 读取时按当前值现算（不在打卡行存快照）→ 换章对已盖出的章立即生效。
-- · 合并 / 下架都不清空、不删 OSS，只有后台「移除」清空（AD-18）。

ALTER TABLE places ADD COLUMN stamp_object_key VARCHAR(255);

COMMENT ON COLUMN places.stamp_object_key IS
    '场所专属章 OSS objectKey（公开桶、对象级 public-read）；NULL = 用按 place_type 的默认章。'
    '合并 / 下架不清空，只有后台『移除』清空（AD-18）。';
