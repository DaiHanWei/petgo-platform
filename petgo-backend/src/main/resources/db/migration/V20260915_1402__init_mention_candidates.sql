-- @ 提及的候选集（V1.3.0 batch-b1 Story 3.1 · FR-119 · AD-10 · B1-D12）。
--
-- 🔴 **独立建表，不每次打开输入框现扫**（2026-09-14 拍板）。
--    现扫要跨 comments / content_likes / content_posts 多张表回溯，而 @ 输入框是**打字时的即时交互** ——
--    慢一点就毁掉体验。这张表是那几张表的**派生物**：丢了可以从原表重算，不是真值来源。
--
-- 🔴 **这张表最要紧的不是它长什么样，是它怎么被喂饱、怎么不撑爆**（AC2）。两条口径定死在这里：
--    ① **写入**：随互动**异步**写（`@Async` + 既有的 ContentLikedEvent / ContentCommentedEvent，
--       见 MentionCandidateListener）。不走同步写是因为「评论/点赞」是热路径，
--       而候选集晚半秒没人看得出来。
--       ⚠️ 护栏：异步只用 `@Async`，**不引 MQ、不引调度中间件**（CLAUDE.md）。
--    ② **裁剪**：**写入时顺手淘汰**（每次 upsert 之后删掉该 owner 最新 N 条之外的），
--       不上定时任务。好处是表的上界是硬的：行数 ≤ 用户数 × N，与互动量无关。
--
-- 🔴 **为什么每人留 50 而不是正好 30**：拉黑排除是在**查询时**做的。
--    表里正好只存 30 人时，其中 3 个被拉黑，用户就只剩 27 个候选。留冗余才能保证排除后仍有 30 个可选。
--
-- ⚠️ **没有全局用户搜索**（AD-10 Rule 3 / AC5）：输入昵称只在这 30 人内过滤，
--    @ 不到没打过交道的陌生人。这张表的存在正是为了让"搜索"这件事在本版本里根本不必发生。
CREATE TABLE IF NOT EXISTS mention_candidates (
    id                 BIGSERIAL PRIMARY KEY,
    -- 这份候选集属于谁（输入 @ 的那个人）。
    owner_id           BIGINT      NOT NULL,
    -- 候选人。
    candidate_id       BIGINT      NOT NULL,
    -- 最近一次互动时间。**排序键**，也是裁剪时的淘汰依据。
    -- ⚠️ 不是 created_at：一个人反复与你互动，应当一直排在前面，而不是停在第一次。
    last_interacted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 一对 (owner, candidate) 只有一行 —— 写入走 ON CONFLICT DO UPDATE 刷新时间。
-- ⚠️ 这条唯一约束是幂等写入的**前提**，不是锦上添花：没有它，每次互动都会新插一行，
--    50 条的裁剪上限会把同一个人重复 50 次，候选集里全是他自己。
ALTER TABLE mention_candidates DROP CONSTRAINT IF EXISTS uq_mention_candidates_owner_candidate;
ALTER TABLE mention_candidates ADD CONSTRAINT uq_mention_candidates_owner_candidate
    UNIQUE (owner_id, candidate_id);

-- 🔴 不能 @ 自己 —— 在库里就挡掉，而不是只靠 service 记得判。
ALTER TABLE mention_candidates DROP CONSTRAINT IF EXISTS ck_mention_candidates_not_self;
ALTER TABLE mention_candidates ADD CONSTRAINT ck_mention_candidates_not_self
    CHECK (owner_id <> candidate_id);

-- 取候选（owner + 时间倒序取前 N）与裁剪（同一把尺子找出该 owner 的第 N+1 名之后）走同一条索引。
CREATE INDEX IF NOT EXISTS idx_mention_candidates_owner_recent
    ON mention_candidates (owner_id, last_interacted_at DESC, id DESC);

-- 注销级联（D1/D2）：用户注销时，他作为 owner 的整份候选集与他出现在别人候选集里的行
-- **都要清掉**。两侧都建索引，删除时不必全表扫。
-- ⚠️ owner 侧已被上面那条复合索引的前缀覆盖，这里只补 candidate 侧。
CREATE INDEX IF NOT EXISTS idx_mention_candidates_candidate
    ON mention_candidates (candidate_id);
