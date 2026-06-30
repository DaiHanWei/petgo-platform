-- Story 2.1 跟进（code review #1）：存量兽医「既往认证」grandfather 回填。
-- 实测最大号+1 = V43（V42=consult_anomalies）。
--
-- 背景：2.1 接单门控仅 CERTIFIED/EXPIRING_SOON 可接单；V33 建表前已存在的兽医无资质行，
-- VetQualificationService.getStatus 对缺行缺省 PENDING_COMPLETION → 上线即**全平台兽医无法接单**
-- （含线上 demo 兽医 drdewi id=1），用户问诊请求无人可接。
--
-- 处置：为迁移时刻**已存在且尚无资质行**的兽医各补一条 CERTIFIED 行（既往执业视同已认证，保持上线前行为）。
--   sipdh_expiry 留 NULL —— 到期扫描（2.8）只扫 sipdh_expiry IS NOT NULL，故 grandfather 行不会被翻成
--   EXPIRING_SOON/EXPIRED；待运营经 2.7 录入真实 SIPDH 后纳入正常到期生命周期。
-- 一次性回填：仅作用于迁移时刻的 vet_accounts；此后新建兽医仍走正常资质流程（无行→不可接单）。
INSERT INTO vet_qualifications (vet_account_id, status, created_at, updated_at)
SELECT va.id, 'CERTIFIED', now(), now()
FROM vet_accounts va
WHERE NOT EXISTS (
    SELECT 1 FROM vet_qualifications q WHERE q.vet_account_id = va.id
);
