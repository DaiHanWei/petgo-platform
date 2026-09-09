-- ============================================================
-- 内容运营所需数据 SQL
-- 创建日期：2026-07-17 · 最近更新：2026-08-31（付费指标统一为「付费人数 + 付费次数」两口径各一组，日报与文末历史查询同口径）
-- 说明：以下查询全部基于现有数据库表，不依赖任何新增埋点。
--       表名/字段名/枚举值已对照 db-schema-reference.md（staging 库 petgo_stag，56 张表）核对。
--
-- 口径（本人判断，需运营/产品确认是否符合预期）：
--   1. 「新增用户数」「总安装用户数」排除 role=ADMIN 和种子/虚拟账号——
--      通过 google_sub 前缀识别：admin: 开头 = 运营 ADMIN 账号，virtual: / seed-tailtopia- 开头 = 种子账号
--      （stag 库目前没有 account_type 列，等它上线后可以换成更直接的判断）。
--   2. 「发帖数/发帖用户数」只统计 status='PUBLISHED' AND deleted_at IS NULL 的公开可见帖子，
--      审核中(UNDER_REVIEW)、作者已注销隐藏(AUTHOR_DEACTIVATED)、已删除的帖子不计入。
--   3. 「评论数」只统计 deleted_at IS NULL 的评论（stag 库 comments 表暂时没有 moderation_status 列，
--      所以没有再加"审核通过"的过滤）。
--   4. content_likes 取消点赞是直接删行，没有软删标记，点赞数无需额外过滤。
--   5. 「当日发diary的建档用户数」中的 diary = content_posts.type='GROWTH_MOMENT'（成长日历快乐时刻），
--      且发帖人必须是 pet_profiles 的 owner（即已建档用户）。
--   6. 付费指标：「付费人数」= 当天发生过至少一次真实成交的去重真实用户数（同样排 ADMIN/种子账号）；
--      「付费次数」= 当天的成交笔数，不去重（同一人多笔各算一次）。两个口径各给人数+次数一组：
--      ① 现金到账：payment_intents.status='PAID'，覆盖全部 QRIS 真钱（问诊/PawCoin 充值/AI 解锁/身份证高清图），
--         对财务口径最贴。payment_intents 没有 paid_at 列，用 updated_at 当到账时刻——PAID 是终态不再变，
--         updated_at 即到账那一刻。
--      ② 含 PawCoin 消费：consult_orders.paid_at（问诊）∪ ai_consult_orders(status='COMPLETED').paid_at（AI 解锁）
--         ∪ id_card_hd_purchases.purchased_at（身份证高清图）∪ PawCoin 充值到账。站内用余额付的也算，对运营口径最贴。
--      两者差主要是「先前充值、今天用余额买问诊」的人：① 不算他今天付费，② 算。
--      已退款订单当天仍算付费用户（退款不追溯改写付款事实）；要剔除在 spend_events 的问诊分支加
--      AND co.status NOT IN ('REFUNDING','REFUNDED')。
--
-- 日报口径：
--   - 所有时间窗口的截止点统一是"查询当天的 00:00"，即"今日新增"实际统计的是上一个完整自然日
--     （比如 7/20 执行这条查询，看到的是 7/19 00:00 ~ 7/20 00:00 一整天，因为 7/20 还没走完，
--     统计还不完整，不适合拿来做日报）。
--   - 周一执行时自动补上周五、周六、周日三天（分天各一行，不合并求和），其他日期只有昨天一天。
--     判断逻辑：EXTRACT(DOW FROM CURRENT_DATE)，Postgres 里周日=0、周一=1……周六=6。
--
-- 两个指标的含义（本人推断，需运营/产品确认）：
--   - 总安装用户数：不是当天新增，而是"截至该天 24:00 为止"的累计注册用户数，放进每日一行的表里
--     是为了看累计增长趋势。
--   - 今日帖子总得分：指"当天新发布的帖子"，用它们截至该天 24:00 为止收到的点赞×1+评论×5 算出来的总分，
--     衡量的是"这天发的内容质量/热度"，跟「总互动得分」不同——后者统计的是"当天发生的互动"
--     （不管被互动的帖子是哪天发的）。
--     注意：截止点是该天 24:00，不是"跑查询的那一刻"，所以同一天的数字不会随着后续互动继续往上涨，
--     补跑历史日期也能复现同样的结果。
--
-- 次日留存：数据库没有登录/打开 App 日志表（真正的登录事件只进 PostHog，不落库，
--   见 埋点文件/analytics-posthog-tracking.md），所以这份 SQL 不包含次日留存，改去 PostHog 里查。
-- ============================================================

WITH report_days AS (
    SELECT gs::date AS report_date
    FROM generate_series(
        CURRENT_DATE - (CASE WHEN EXTRACT(DOW FROM CURRENT_DATE) = 1 THEN 3 ELSE 1 END),
        CURRENT_DATE - INTERVAL '1 day',
        INTERVAL '1 day'
    ) AS gs
),
new_users_by_day AS (
    SELECT rd.report_date, COUNT(u.id) AS new_users
    FROM report_days rd
    LEFT JOIN users u
      ON u.created_at::date = rd.report_date
     AND u.role = 'USER'
     AND COALESCE(u.google_sub, '') NOT LIKE 'admin:%'
     AND COALESCE(u.google_sub, '') NOT LIKE 'virtual:%'
     AND COALESCE(u.google_sub, '') NOT LIKE 'seed-tailtopia-%'
    GROUP BY rd.report_date
),
total_installs_by_day AS (
    SELECT rd.report_date,
        (SELECT COUNT(*)
         FROM users u2
         WHERE u2.created_at::date <= rd.report_date
           AND u2.role = 'USER'
           AND COALESCE(u2.google_sub, '') NOT LIKE 'admin:%'
           AND COALESCE(u2.google_sub, '') NOT LIKE 'virtual:%'
           AND COALESCE(u2.google_sub, '') NOT LIKE 'seed-tailtopia-%'
        ) AS total_installed_users
    FROM report_days rd
),
posts_by_day AS (
    SELECT rd.report_date,
        COUNT(DISTINCT cp.author_id) AS posting_users,
        COUNT(cp.id)                 AS new_posts
    FROM report_days rd
    LEFT JOIN content_posts cp
      ON cp.created_at::date = rd.report_date
     AND cp.status = 'PUBLISHED'
     AND cp.deleted_at IS NULL
    GROUP BY rd.report_date
),
profiles_by_day AS (
    SELECT rd.report_date, COUNT(pp.id) AS new_pet_profiles
    FROM report_days rd
    LEFT JOIN pet_profiles pp
      ON pp.created_at::date = rd.report_date
    GROUP BY rd.report_date
),
cumulative_pet_owners_by_day AS (
    -- 累计建档用户数：截至该天 24:00 为止，累计拥有至少一个 pet_profiles 的去重用户数（同一用户建多个档只算一次）
    SELECT rd.report_date,
        (SELECT COUNT(DISTINCT pp2.owner_id)
         FROM pet_profiles pp2
         WHERE pp2.created_at::date <= rd.report_date
        ) AS cumulative_pet_owners
    FROM report_days rd
),
diary_posters_by_day AS (
    -- 建档用户中，当日发过 diary（GROWTH_MOMENT 成长日历快乐时刻）的人数；发帖人必须是 pet_profiles 的 owner
    SELECT rd.report_date, COUNT(DISTINCT cp.author_id) AS pet_owner_diary_posters
    FROM report_days rd
    LEFT JOIN content_posts cp
      ON cp.created_at::date = rd.report_date
     AND cp.status = 'PUBLISHED'
     AND cp.deleted_at IS NULL
     AND cp.type = 'GROWTH_MOMENT'
     AND EXISTS (SELECT 1 FROM pet_profiles pp WHERE pp.owner_id = cp.author_id)
    GROUP BY rd.report_date
),
interactions_raw AS (
    SELECT post_id, 'like' AS source, created_at FROM content_likes
    UNION ALL
    SELECT post_id, 'comment' AS source, created_at FROM comments WHERE deleted_at IS NULL
),
interaction_by_day AS (
    SELECT rd.report_date, COUNT(DISTINCT ir.post_id) AS posts_with_interaction
    FROM report_days rd
    LEFT JOIN interactions_raw ir ON ir.created_at::date = rd.report_date
    GROUP BY rd.report_date
),
silent_posts_by_day AS (
    -- 今日沉默帖子数：当天发布(status='PUBLISHED' 且未删除)、且当天完全没有收到互动（0 点赞、0 评论）的帖子数。
    -- 互动的判定口径与其他指标一致：只看 created_at 落在当天的点赞/评论（评论已过滤 deleted_at IS NULL）。
    SELECT rd.report_date, COUNT(cp.id) AS silent_posts
    FROM report_days rd
    LEFT JOIN content_posts cp
      ON cp.created_at::date = rd.report_date
     AND cp.status = 'PUBLISHED'
     AND cp.deleted_at IS NULL
     AND NOT EXISTS (
         SELECT 1 FROM interactions_raw ir
         WHERE ir.post_id = cp.id
           AND ir.created_at::date = rd.report_date
     )
    GROUP BY rd.report_date
),
engagement_by_day AS (
    SELECT rd.report_date,
        COALESCE(SUM(CASE WHEN ir.source = 'like'    THEN 1 ELSE 0 END), 0) * 1
      + COALESCE(SUM(CASE WHEN ir.source = 'comment' THEN 1 ELSE 0 END), 0) * 5 AS total_engagement_score
    FROM report_days rd
    LEFT JOIN interactions_raw ir ON ir.created_at::date = rd.report_date
    GROUP BY rd.report_date
),
post_score_by_day AS (
    -- 今日帖子总得分：当天新发布的帖子，用它们截至该天 24:00 为止累计的点赞×1+评论×5 求和
    -- （截止点跟其他指标一致，不再把该天之后新产生的互动算进来）
    SELECT rd.report_date,
        COALESCE(SUM(s.post_score), 0) AS posts_total_score
    FROM report_days rd
    LEFT JOIN content_posts cp
      ON cp.created_at::date = rd.report_date
     AND cp.status = 'PUBLISHED'
     AND cp.deleted_at IS NULL
    LEFT JOIN LATERAL (
        SELECT
            (SELECT COUNT(*)
             FROM content_likes cl
             WHERE cl.post_id = cp.id
               AND cl.created_at::date <= rd.report_date) * 1
          + (SELECT COUNT(*)
             FROM comments cm
             WHERE cm.post_id = cp.id
               AND cm.deleted_at IS NULL
               AND cm.created_at::date <= rd.report_date) * 5 AS post_score
    ) s ON TRUE
    GROUP BY rd.report_date
),
all_posts_avg_score_by_day AS (
    -- 全部帖子全量平均分：截至该天 24:00 为止已发布的全部公开可见帖子，
    -- 各自截至该天累计的点赞×1+评论×5，取平均——按天变化，跟其他指标的截止点保持一致。
    -- 分母是"截至该天的存量帖子总数"，所以这个数会随着存量变大而被摊薄，看趋势而不是看绝对值。
    SELECT rd.report_date,
        AVG(s.post_score) FILTER (WHERE p.id IS NOT NULL) AS avg_score
    FROM report_days rd
    LEFT JOIN content_posts p
      ON p.created_at::date <= rd.report_date
     AND p.status = 'PUBLISHED'
     AND p.deleted_at IS NULL
    LEFT JOIN LATERAL (
        SELECT
            (SELECT COUNT(*)
             FROM content_likes cl
             WHERE cl.post_id = p.id
               AND cl.created_at::date <= rd.report_date) * 1
          + (SELECT COUNT(*)
             FROM comments cm
             WHERE cm.post_id = p.id
               AND cm.deleted_at IS NULL
               AND cm.created_at::date <= rd.report_date) * 5 AS post_score
    ) s ON TRUE
    GROUP BY rd.report_date
),
all_time_engagement_by_day AS (
    -- 总互动得分_全部历史：截至该天 24:00 为止的全部历史点赞×1+评论×5 的总和，按天累计（逐日递增）——
    -- 跟「总安装用户数_累计」一样是累计口径，用来看互动总量的增长趋势
    SELECT rd.report_date,
        (SELECT COUNT(*)
         FROM content_likes cl
         WHERE cl.created_at::date <= rd.report_date) * 1
      + (SELECT COUNT(*)
         FROM comments cm
         WHERE cm.deleted_at IS NULL
           AND cm.created_at::date <= rd.report_date) * 5 AS total_engagement_score_all_time
    FROM report_days rd
),
daily_interaction_per_post AS (
    -- 当天有互动的帖子，各自当天新产生的点赞/评论次数（不是帖子的累计总数）
    SELECT rd.report_date, ir.post_id,
        SUM(CASE WHEN ir.source = 'like'    THEN 1 ELSE 0 END) AS today_likes,
        SUM(CASE WHEN ir.source = 'comment' THEN 1 ELSE 0 END) AS today_comments
    FROM report_days rd
    JOIN interactions_raw ir ON ir.created_at::date = rd.report_date
    GROUP BY rd.report_date, ir.post_id
),
daily_interaction_avg_by_day AS (
    -- 当日互动帖子平均得分：只看当天有互动的帖子，按它们当天新增的点赞×1+评论×5 取平均
    SELECT rd.report_date,
        AVG(dip.today_likes * 1 + dip.today_comments * 5) AS interacted_posts_avg_score_today
    FROM report_days rd
    LEFT JOIN daily_interaction_per_post dip ON dip.report_date = rd.report_date
    GROUP BY rd.report_date
),
real_users AS (
    SELECT u.id
    FROM users u
    WHERE u.role = 'USER'
      AND COALESCE(u.google_sub, '') NOT LIKE 'admin:%'
      AND COALESCE(u.google_sub, '') NOT LIKE 'virtual:%'
      AND COALESCE(u.google_sub, '') NOT LIKE 'seed-tailtopia-%'
),
cash_paid AS (
    -- 付费口径①：真钱到账（见口径 6）
    SELECT pi.user_id, pi.updated_at::date AS d, pi.purpose
    FROM payment_intents pi
    JOIN real_users ru ON ru.id = pi.user_id
    WHERE pi.status = 'PAID'
),
spend_events AS (
    -- 付费口径②：消费成交（含 PawCoin 扣减）+ 充值（见口径 6）
    SELECT co.user_id, co.paid_at::date AS d
    FROM consult_orders co
    JOIN real_users ru ON ru.id = co.user_id
    WHERE co.paid_at IS NOT NULL
    UNION ALL
    SELECT ao.user_id, ao.paid_at::date
    FROM ai_consult_orders ao
    JOIN real_users ru ON ru.id = ao.user_id
    WHERE ao.status = 'COMPLETED' AND ao.paid_at IS NOT NULL
    UNION ALL
    SELECT hd.user_id, hd.purchased_at::date
    FROM id_card_hd_purchases hd
    JOIN real_users ru ON ru.id = hd.user_id
    UNION ALL
    SELECT cp.user_id, cp.d
    FROM cash_paid cp
    WHERE cp.purpose = 'PAWCOIN_TOPUP'
),
paying_users_by_day AS (
    -- 付费人数 = 当天有成交的去重用户数；付费次数 = 当天成交笔数（同一人多笔各算一次）。口径见口径 6。
    SELECT rd.report_date,
        (SELECT COUNT(DISTINCT cp.user_id) FROM cash_paid cp     WHERE cp.d = rd.report_date) AS paying_users_cash,
        (SELECT COUNT(*)                   FROM cash_paid cp     WHERE cp.d = rd.report_date) AS payments_cash,
        (SELECT COUNT(DISTINCT se.user_id) FROM spend_events se  WHERE se.d = rd.report_date) AS paying_users_incl_pawcoin,
        (SELECT COUNT(*)                   FROM spend_events se  WHERE se.d = rd.report_date) AS payments_incl_pawcoin
    FROM report_days rd
)
SELECT
    rd.report_date                       AS 日期,
    nu.new_users                         AS 新增用户数,
    ti.total_installed_users             AS 总安装用户数_累计,
    p.posting_users                      AS 发帖用户数,
    p.new_posts                          AS 新增帖子数,
    pf.new_pet_profiles                  AS 新增建档用户数,
    cpo.cumulative_pet_owners            AS 累计建档用户数,
    dp.pet_owner_diary_posters           AS 当日发diary的建档用户数,
    ib.posts_with_interaction            AS 有互动的帖子数,
    sp.silent_posts                      AS 今日沉默帖子数,
    eb.total_engagement_score            AS 总互动得分,
    ps.posts_total_score                 AS 今日帖子总得分,
    apa.avg_score                        AS 全部帖子全量平均分,
    dia.interacted_posts_avg_score_today AS 当日互动帖子平均得分,
    ate.total_engagement_score_all_time  AS 总互动得分_全部历史,
    pu.paying_users_cash                 AS 付费人数_现金到账,
    pu.payments_cash                     AS 付费次数_现金到账,
    pu.paying_users_incl_pawcoin         AS 付费人数_含PawCoin消费,
    pu.payments_incl_pawcoin             AS 付费次数_含PawCoin消费
FROM report_days rd
JOIN new_users_by_day        nu  ON nu.report_date  = rd.report_date
JOIN total_installs_by_day   ti  ON ti.report_date  = rd.report_date
JOIN posts_by_day            p   ON p.report_date   = rd.report_date
JOIN profiles_by_day         pf  ON pf.report_date  = rd.report_date
JOIN cumulative_pet_owners_by_day cpo ON cpo.report_date = rd.report_date
JOIN diary_posters_by_day    dp  ON dp.report_date  = rd.report_date
JOIN interaction_by_day      ib  ON ib.report_date  = rd.report_date
JOIN silent_posts_by_day     sp  ON sp.report_date  = rd.report_date
JOIN engagement_by_day       eb  ON eb.report_date  = rd.report_date
JOIN post_score_by_day       ps  ON ps.report_date  = rd.report_date
JOIN daily_interaction_avg_by_day dia ON dia.report_date = rd.report_date
JOIN all_time_engagement_by_day ate ON ate.report_date = rd.report_date
JOIN all_posts_avg_score_by_day apa ON apa.report_date = rd.report_date
JOIN paying_users_by_day     pu  ON pu.report_date  = rd.report_date
ORDER BY rd.report_date;
