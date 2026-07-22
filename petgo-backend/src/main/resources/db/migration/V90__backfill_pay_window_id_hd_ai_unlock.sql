-- bug 20260722：AI 解锁 / KTP 身份证 QRIS 付款超时回填。
--
-- 背景：ID_HD / AI_UNLOCK 原先建意图不设 expires_at（NULL = 永不过期），超时后重开支付页仍取回旧二维码。
-- 代码已改为建单时传 60min 付款窗（同充值），但【部署前】已存在的 PENDING 意图 expires_at 仍为 NULL，
-- 且被稳定幂等键（id-hd:<petId> / ai-unlock:<triageId>）锁死——isExpiredAt(NULL)=false，懒过期永不触发，
-- 用户永远拿不到新码。
--
-- 本迁移把这些存量 PENDING 意图补上 expires_at = created_at + 60min：
--   - 早于「now - 60min」建的 → 立刻算过期 → 下次重开即懒过期置 EXPIRED 并建新码；
--   - 60min 内建的 → 窗口内仍可复用同码，超窗后自然过期。
-- 只回填 ID_HD / AI_UNLOCK（本次加窗的两类）；VET_CONSULT 有独立超时机制、PAWCOIN_TOPUP 已有窗，均不动。
-- 幂等：仅 expires_at IS NULL 的行受影响，重跑无副作用。时间戳 UTC。

UPDATE payment_intents
SET expires_at = created_at + INTERVAL '60 minutes'
WHERE status = 'PENDING'
  AND expires_at IS NULL
  AND purpose IN ('ID_HD', 'AI_UNLOCK');
