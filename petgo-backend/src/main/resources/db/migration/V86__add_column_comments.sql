-- V86: 为全部业务表补中文字段注释
--
-- 背景：运营/产品通过只读账号 ops_readonly 直接查库取数，此前 556 个字段仅 4 个有注释，
-- 表名列名无法自解释。本迁移为 55 张业务表 / 546 个字段补齐中文注释，
-- 使 DBeaver / VSCode 等客户端能直接展示字段含义。
--
-- 说明：
--   * 不含 flyway_schema_history（Flyway 自有表，不由本项目注释）。
--   * COMMENT ON 是幂等的，重复执行安全，不改变任何数据与结构。
--   * 注释内容来源于 JPA 实体类 Javadoc、既往迁移注释与 service 层实现，非推测。
--   * 【PII】标记涉个人身份/健康数据的字段；【加密存储】标记密文列。

-- ========== users ==========
COMMENT ON TABLE users IS '用户账号主表：App 端真实用户 + 运营 ADMIN 账号 + 运营建的虚拟种子账号，均在本表。注销采用「就地匿名化」（软删标记 + 擦除个人信息），不物理删行，以保留其发过的内容';
COMMENT ON COLUMN users.id IS '主键，用户内部 id（不对外暴露）';
COMMENT ON COLUMN users.google_sub IS 'Google 登录唯一标识（OAuth sub）【PII】。全表唯一。特殊前缀含义：admin:<邮箱>=运营 ADMIN 账号占位 / virtual: 或 seed-tailtopia-=虚拟种子账号（不可登录）/ deleted:<id>=账号已注销后写入的墓碑值（防同账号复活、防唯一冲突）。仅 Apple 登录的用户此列为空';
COMMENT ON COLUMN users.email IS '登录邮箱【PII】，来自 Google/Apple 授权。注销匿名化后置空（快照转存到 deleted_email）';
COMMENT ON COLUMN users.display_name IS '第三方登录带回的姓名【PII】。Apple 登录不返回姓名故为空。注销匿名化后置空（快照转存到 deleted_display_name）';
COMMENT ON COLUMN users.avatar_url IS '头像图片地址；仅存平台自有 OSS 地址。Apple 登录不提供头像。注销匿名化后置空';
COMMENT ON COLUMN users.nickname IS '用户昵称，App 内展示名，最长 20 字符【PII】。注销匿名化后置空';
COMMENT ON COLUMN users.pet_status IS '养宠身份（决定首页内容可见范围）：HAS_PET=我有宠物（内容全显，解锁成长档案）/ PLANNING=暂无但计划养（首页不显成长日历内容）/ ENTHUSIAST=宠物爱好者（内容全显）。新用户引导前为空';
COMMENT ON COLUMN users.onboarding_completed IS '是否已完成新用户引导流程：true=已完成引导（已选养宠身份、填昵称等）';
COMMENT ON COLUMN users.role IS '账号角色：USER=普通用户 / VET=兽医 / ADMIN=运营管理员';
COMMENT ON COLUMN users.created_at IS '账号创建时间（UTC 时区）';
COMMENT ON COLUMN users.updated_at IS '记录最后更新时间（UTC 时区）';
COMMENT ON COLUMN users.deleted_at IS '注销完成时间（UTC 时区）。仅当用户主动注销、后台注销作业执行匿名化时写入；非空即代表该账号已注销（此时个人信息各列已被擦空，其发过的内容仍保留并显示为「已注销用户」）';
COMMENT ON COLUMN users.password_hash IS '登录密码的不可逆哈希（BCrypt）【PII】。仅运营 ADMIN 账密登录用；第三方登录用户为空';
COMMENT ON COLUMN users.apple_sub IS 'Apple 登录唯一标识（Sign in with Apple 的 sub）【PII】。全表唯一。仅 Google 登录的用户此列为空；与 google_sub 至少其一非空。注销匿名化后置空';
COMMENT ON COLUMN users.status IS '账号启用状态（运营可切换，与注销互相独立）：ACTIVE=正常，可登录 / DEACTIVATED=被运营停用，立即不可登录（可重新激活恢复）';
COMMENT ON COLUMN users.is_system_default_name IS '当前昵称是否为「违规重置」自动生成的默认编码名（形如 user_xxxx）：true=昵称因审核判定违规被系统重置过。注意这与注销匿名化无关';
COMMENT ON COLUMN users.deleted_email IS '注销前的邮箱快照【PII】。仅在注销时写入，且仅供运营后台用户列表/详情展示「是谁注销了」；业务侧/公开侧/兽医侧一律读原 email 列（已置空，不泄漏）';
COMMENT ON COLUMN users.deleted_display_name IS '注销前的姓名快照【PII】。仅在注销时写入，且仅供运营后台展示「是谁注销了」；业务侧读原 display_name 列（已置空）';
COMMENT ON COLUMN users.locale IS '用户语言偏好，用于系统推送按用户语言渲染文案。取值 id（印尼语）/ en（英语），由 App 请求头 Accept-Language 捕获；为空视为默认 id';
COMMENT ON COLUMN users.account_type IS '账号类型：REAL=真实用户（Google/Apple 登录）/ VIRTUAL=虚拟账号（运营创建的种子内容作者，无第三方身份、无密码、无法登录）';
COMMENT ON COLUMN users.created_by IS '建号的运营管理员 id（关联 admin_accounts.id）。仅虚拟账号有值，真实用户为空';
COMMENT ON COLUMN users.enabled IS '虚拟账号是否启用：true=启用可用于发种子内容，false=已停用。真实用户恒为 true（真实用户的封禁请看 status 列）';
COMMENT ON COLUMN users.published_count IS '该虚拟账号已发布的种子内容条数（运营侧统计用）。真实用户恒为 0';

-- ========== refresh_tokens ==========
COMMENT ON TABLE refresh_tokens IS '登录续期令牌（refresh token）句柄表：用户登录后免重复登录靠它续期。只存不可逆哈希、绝不存明文；每次续期即「轮换」——旧句柄作废、发新句柄，防止令牌被重放盗用';
COMMENT ON COLUMN refresh_tokens.id IS '主键';
COMMENT ON COLUMN refresh_tokens.user_id IS '令牌所属主体的 id。注意需与 subject_type 一起看：subject_type=USER 时关联 users.id，=VET 时关联 vet_accounts.id（两张表各自独立自增，单看本列会串号）';
COMMENT ON COLUMN refresh_tokens.token_hash IS '续期令牌的不可逆哈希【PII】。全表唯一；明文只在下发那一刻给到客户端，服务端永不留存';
COMMENT ON COLUMN refresh_tokens.expires_at IS '令牌过期时间（UTC 时区）。过期后不可再用于续期，用户需重新登录';
COMMENT ON COLUMN refresh_tokens.revoked IS '令牌是否已作废：true=已失效不可用（登出、令牌轮换后的旧句柄、或安全撤销时置 true）';
COMMENT ON COLUMN refresh_tokens.created_at IS '令牌签发时间（UTC 时区）';
COMMENT ON COLUMN refresh_tokens.subject_type IS '令牌主体类型，决定 user_id 关联到哪张表：USER=普通用户（users 表）/ VET=兽医（vet_accounts 表）。默认 USER（兼容早期历史句柄）';

-- ========== account_deletions ==========
COMMENT ON TABLE account_deletions IS '账号注销作业表：用户申请注销后，由后台异步作业按状态机逐步执行级联删除/匿名化，可重试、可审计、不半途残留。本表刻意不落任何个人信息，仅存用户 id + 进度 + 时间，作为合规留证';
COMMENT ON COLUMN account_deletions.id IS '主键';
COMMENT ON COLUMN account_deletions.user_id IS '申请注销的用户 id（关联 users.id）。每个用户最多一条（唯一约束）';
COMMENT ON COLUMN account_deletions.status IS '注销作业状态：PENDING=已受理待执行 / PROCESSING=执行中 / DONE=已完成（数据已删除或匿名化）/ FAILED=执行失败（会被重扫重试）';
COMMENT ON COLUMN account_deletions.retry_count IS '作业失败后的累计重试次数，每失败一次 +1（用于排查卡住的注销请求）';
COMMENT ON COLUMN account_deletions.requested_at IS '用户提交注销申请、系统受理的时间（UTC 时区），合规留证用';
COMMENT ON COLUMN account_deletions.completed_at IS '注销执行完成的时间（UTC 时区）。仅当状态流转到 DONE 时写入，未完成/失败时为空';

-- ========== violation_counts ==========
COMMENT ON TABLE violation_counts IS '账号违规计数表：按「用户 + 违规类型」聚合累计其被人工审核判定违规的次数。⚠️ 仅供运营参考记录、不驱动任何自动处置——达到任何次数都不会触发封号或拦截（阈值处置为后续版本预留）。逐条处置的详细证据在 admin_audit_logs';
COMMENT ON COLUMN violation_counts.id IS '主键';
COMMENT ON COLUMN violation_counts.account_id IS '违规内容所属的 App 用户 id（关联 users.id）。刻意未建外键约束（为保证注销时的删除次序），用户注销时由程序级联清空其计数行';
COMMENT ON COLUMN violation_counts.violation_type IS '违规类型：POST=帖子 / COMMENT=评论 / NAME=昵称或宠物名 / AVATAR=头像。同一用户每种类型各一行';
COMMENT ON COLUMN violation_counts.violation_count IS '该用户该类型累计被人工判定违规的次数。仅统计人工判定；自动同步拦截（内容压根没发出去）不计入';
COMMENT ON COLUMN violation_counts.first_violation_at IS '该用户该类型首次被计入违规的时间（UTC 时区）';
COMMENT ON COLUMN violation_counts.last_violation_at IS '该用户该类型最近一次被计入违规的时间（UTC 时区）';
COMMENT ON COLUMN violation_counts.created_at IS '记录创建时间（UTC 时区）';
COMMENT ON COLUMN violation_counts.updated_at IS '记录最后更新时间（UTC 时区）';

-- ========== user_monthly_free_quota ==========
COMMENT ON TABLE user_monthly_free_quota IS '用户每月免费额度表：记录用户当月已用掉多少次 AI 问诊详情的免费解锁。一人一月一行，换月即产生新行 = 额度自然重置（无需定时任务）';
COMMENT ON COLUMN user_monthly_free_quota.id IS '主键';
COMMENT ON COLUMN user_monthly_free_quota.user_id IS '额度所属用户 id（关联 users.id）。与 period 组合唯一';
COMMENT ON COLUMN user_monthly_free_quota.period IS '额度所属月份，格式 YYYY-MM（例：2026-07）。⚠️ 按印尼西部时间（WIB/雅加达）计算，而非本项目其它字段惯用的 UTC——因为「每月 1 号刷新额度」对印尼用户必须是当地时间';
COMMENT ON COLUMN user_monthly_free_quota.used_count IS '该用户当月已使用的免费解锁次数。原子递增保证并发下不超发，且不会为负';
COMMENT ON COLUMN user_monthly_free_quota.created_at IS '记录创建时间，即该用户当月首次使用免费额度的时间（UTC 时区）';
COMMENT ON COLUMN user_monthly_free_quota.updated_at IS '记录最后更新时间，即最近一次消耗免费额度的时间（UTC 时区）';

-- ========== pet_profiles ==========
COMMENT ON TABLE pet_profiles IS '宠物档案表：一个账号一只宠物（V1 硬约束），承载成长记忆与对外宠物名片';
COMMENT ON COLUMN pet_profiles.id IS '主键，内部自增 id，不对外暴露';
COMMENT ON COLUMN pet_profiles.owner_id IS '档案所属用户，关联 users.id；一个用户至多一条档案（唯一约束）';
COMMENT ON COLUMN pet_profiles.avatar_url IS '宠物头像图片地址（公开桶 + CDN，上传时已由客户端剥离 EXIF）';
COMMENT ON COLUMN pet_profiles.name IS '宠物名字，最长 20 字【PII】';
COMMENT ON COLUMN pet_profiles.breed IS '品种，用户手填，可空，最长 60 字';
COMMENT ON COLUMN pet_profiles.birthday IS '宠物生日，可空；用于生日提醒与陪伴纪念日等里程碑【PII】';
COMMENT ON COLUMN pet_profiles.intro IS '一句话介绍，最长 30 字，可空';
COMMENT ON COLUMN pet_profiles.card_token IS '对外宠物名片 token：不可枚举随机串（SecureRandom ≥128bit base62），公开名片页 /p/{cardToken} 用它定位档案，避免外泄自增 id';
COMMENT ON COLUMN pet_profiles.created_at IS '档案创建时间（UTC 时区）';
COMMENT ON COLUMN pet_profiles.updated_at IS '档案最后更新时间（UTC 时区）';
COMMENT ON COLUMN pet_profiles.og_image_url IS '名片分享用的社交预览图（OG 图）地址，预渲染后存公开桶 + CDN；档案或头像变更时重新生成';
COMMENT ON COLUMN pet_profiles.pet_type IS '宠物类型（创建后不可修改，决定分配哪套里程碑清单）：CAT=猫、DOG=狗、OTHER=其他';
COMMENT ON COLUMN pet_profiles.is_system_default_name IS '当前宠物名是否为「违规重置」生成的系统默认编码名（形如 Pet_<hex>）：true=名字曾被审核判违规并被系统重置；false=用户自己起的名。注意与注销匿名化无关';
COMMENT ON COLUMN pet_profiles.serial_id IS '宠物身份证(KTP)展示流水号，全平台自增；惰性分配——老用户/未生成身份证时为空，删档时该号回收进 pet_serial_pool 供复用。仅作展示编号，不作对外资源标识';

-- ========== pet_milestones ==========
COMMENT ON TABLE pet_milestones IS '宠物里程碑清单表：建档时按宠物类型为每只宠物物化一份「第一次」里程碑清单；本表只存清单本身，是否完成看 milestone_completions 有无对应行';
COMMENT ON COLUMN pet_milestones.id IS '主键，内部自增 id，不对外暴露';
COMMENT ON COLUMN pet_milestones.pet_profile_id IS '所属宠物档案，关联 pet_profiles.id';
COMMENT ON COLUMN pet_milestones.code IS '里程碑目录码（如 C-S1 / D-M3 / G-S9），前缀 C=猫、D=狗、G=其他；这是稳定的对外标识，App 按此码本地化显示标题';
COMMENT ON COLUMN pet_milestones.level IS '里程碑级别：S=小里程碑、M=中里程碑、L=大里程碑';
COMMENT ON COLUMN pet_milestones.trigger_type IS '点亮方式：SYSTEM_AUTO=系统自动点亮、USER_CHECKIN=用户手动打卡关联一条成长日历内容、PUSH_PUBLISH=收到推送当天发布内容点亮';
COMMENT ON COLUMN pet_milestones.sort_order IS '在清单中的展示排序号，数字越小越靠前';
COMMENT ON COLUMN pet_milestones.created_at IS '该清单行写入时间（UTC 时区），即建档或回溯补齐的时间';

-- ========== milestone_completions ==========
COMMENT ON TABLE milestone_completions IS '里程碑完成记录表：只记录已完成的里程碑，一个里程碑至多一条（完成后不可撤销）';
COMMENT ON COLUMN milestone_completions.id IS '主键，内部自增 id，不对外暴露';
COMMENT ON COLUMN milestone_completions.pet_milestone_id IS '完成的里程碑，关联 pet_milestones.id；唯一约束保证同一里程碑只能完成一次';
COMMENT ON COLUMN milestone_completions.source IS '完成来源：SYSTEM_AUTO=系统自动点亮、USER_CHECKIN=用户手动打卡关联既有内容、PUBLISH=用户发布一条成长日历内容后回填';
COMMENT ON COLUMN milestone_completions.linked_content_id IS '打卡/发布时关联的成长日历内容 id，关联 content_posts.id；系统自动点亮的里程碑为空。一条内容至多关联一个里程碑';
COMMENT ON COLUMN milestone_completions.completed_at IS '里程碑完成时间（UTC 时区）';

-- ========== milestone_shares ==========
COMMENT ON TABLE milestone_shares IS '里程碑庆祝分享表：用户分享某个里程碑时生成的对外公开 H5 页(/m/{shareToken})数据快照；同一宠物同一里程碑只有一条，重复分享复用同一链接只刷新文案';
COMMENT ON COLUMN milestone_shares.id IS '主键，内部自增 id，不对外暴露';
COMMENT ON COLUMN milestone_shares.share_token IS '对外分享 token：不可枚举随机串（≥128bit base62），公开分享页据此直出，避免外泄自增 id';
COMMENT ON COLUMN milestone_shares.pet_profile_id IS '被分享的宠物档案，关联 pet_profiles.id（后端按登录态回填，不信任客户端传入）';
COMMENT ON COLUMN milestone_shares.code IS '被分享的里程碑目录码（如 C-S1），对应 pet_milestones.code';
COMMENT ON COLUMN milestone_shares.level IS '被分享里程碑的级别：S=小里程碑、M=中里程碑、L=大里程碑';
COMMENT ON COLUMN milestone_shares.pet_name IS '分享时的宠物名字快照，用于 H5 页展示【PII】';
COMMENT ON COLUMN milestone_shares.title IS '分享页主标题：客户端按当前语言渲染好后上传的庆祝文案';
COMMENT ON COLUMN milestone_shares.body IS '分享页正文：客户端按当前语言渲染好后上传的庆祝文案，默认空串';
COMMENT ON COLUMN milestone_shares.locale IS '生成这份文案时客户端使用的语言（如 id / zh / en）';
COMMENT ON COLUMN milestone_shares.collection_levels IS '「已解锁合集」快照：分享当时已完成里程碑的级别字符串，按合集顺序每个字符一位（S/M/L），供 H5 页复刻合集展示区';
COMMENT ON COLUMN milestone_shares.completed_at IS '被分享里程碑的完成时间快照（UTC 时区），可空';
COMMENT ON COLUMN milestone_shares.created_at IS '首次生成分享的时间（UTC 时区）';
COMMENT ON COLUMN milestone_shares.updated_at IS '最后一次刷新分享文案的时间（UTC 时区）';

-- ========== health_records ==========
COMMENT ON TABLE health_records IS '宠物结构化健康记录表【PII】整表为健康敏感数据：用户在成长档案里手动录入的疫苗/驱虫/月经/绝育/自定义记录；问诊产生的记录不进本表（见 health_events）。删档随之硬删除，日志严禁记录明文';
COMMENT ON COLUMN health_records.id IS '主键，内部自增 id，不对外暴露';
COMMENT ON COLUMN health_records.pet_profile_id IS '所属宠物档案，关联 pet_profiles.id；档案删除时本表记录级联硬删除';
COMMENT ON COLUMN health_records.type IS '记录类型【PII】：VACCINE=疫苗、DEWORM=驱虫、MENSTRUATION=月经、NEUTER=绝育、CUSTOM=用户自定义。显示名称由 App 按类型本地化';
COMMENT ON COLUMN health_records.custom_name IS '自定义记录名称，最长 20 字；仅 type=CUSTOM 时必填，其余类型为空【PII】';
COMMENT ON COLUMN health_records.vaccine_name IS '疫苗名称，最长 30 字；仅 type=VACCINE 时可填，选填【PII】';
COMMENT ON COLUMN health_records.event_date IS '事件发生日期（用户所在地日期语义，不允许填未来日期）【PII】';
COMMENT ON COLUMN health_records.note IS '用户备注，最长 100 字，可空【PII】';
COMMENT ON COLUMN health_records.created_at IS '记录创建时间（UTC 时区）';
COMMENT ON COLUMN health_records.updated_at IS '记录最后修改时间（UTC 时区）';

-- ========== health_events ==========
COMMENT ON TABLE health_events IS '健康事件表【PII】整表为健康敏感数据：一次 AI 分诊或兽医问诊结束后，用户选择「存入档案」与否的决策记录，存档的会进入成长时间线。日志严禁记录明文，账号/档案注销时级联删除（含私密桶图片）';
COMMENT ON COLUMN health_events.id IS '主键，内部自增 id，不对外暴露';
COMMENT ON COLUMN health_events.pet_id IS '所属宠物档案，关联 pet_profiles.id';
COMMENT ON COLUMN health_events.source_type IS '事件来源：AI_TRIAGE=AI 智能分诊、VET_CONSULT=兽医问诊';
COMMENT ON COLUMN health_events.source_ref IS '来源问诊/会话的对外 token；同时是幂等键（唯一），保证一次问诊只产生一条存档决策、不会重复弹窗或重复存档';
COMMENT ON COLUMN health_events.event_date IS '事件发生日期（本次问诊发生的日期）【PII】';
COMMENT ON COLUMN health_events.symptom_summary IS '症状摘要【PII】；仅选择存档时写入，跳过存档时为空';
COMMENT ON COLUMN health_events.ai_level IS 'AI 分诊风险等级【PII】：GREEN=绿色（可自行观察，零变现）、YELLOW=黄色（建议关注/咨询）、RED=红色（建议立即就医）；未评级或跳过存档时为空';
COMMENT ON COLUMN health_events.advice_summary IS '处理建议摘要【PII】；仅选择存档时写入，跳过存档时为空';
COMMENT ON COLUMN health_events.image_keys IS '问诊图片在私密存储桶中的对象 key 列表（JSON 数组）【PII】；展示时临时换签名 URL，绝不直接存会过期的外部链接';
COMMENT ON COLUMN health_events.archive_decision IS '用户的存档决策：ARCHIVED=已存入成长档案（有症状/建议/图片）、SKIPPED=用户选择不存档（只留决策记录用于幂等，不含健康明细）';
COMMENT ON COLUMN health_events.created_at IS '事件记录写入时间（UTC 时区）';

-- ========== pet_serial_pool ==========
COMMENT ON TABLE pet_serial_pool IS '宠物身份证流水号回收池：档案被删除时，其已分配的展示流水号回收进本表；下次生成身份证优先复用池中最小号，池空才取新号，保证编号紧凑';
COMMENT ON COLUMN pet_serial_pool.serial_id IS '待复用的宠物身份证流水号（主键），来源于被删除档案的 pet_profiles.serial_id';

-- ========== id_card_hd_purchases ==========
COMMENT ON TABLE id_card_hd_purchases IS '宠物身份证高清图购买记录表：一次性付费、永久解锁；一个账号至多一条（存在即已解锁），删档或换宠后仍保留';
COMMENT ON COLUMN id_card_hd_purchases.id IS '主键，内部自增 id，不对外暴露';
COMMENT ON COLUMN id_card_hd_purchases.user_id IS '购买者，关联 users.id；唯一约束体现「一次购买永久解锁」并兜底防重复扣费。用户注销时级联删除';
COMMENT ON COLUMN id_card_hd_purchases.pet_profile_id IS '购买时对应的宠物档案，关联 pet_profiles.id，仅作记录；档案被删除时置空，购买记录不丢、解锁不失效';
COMMENT ON COLUMN id_card_hd_purchases.pay_channel IS '支付渠道：QRIS=扫码真实收款、PAWCOIN=站内 PawCoin 余额扣减';
COMMENT ON COLUMN id_card_hd_purchases.payment_intent_id IS 'QRIS 支付对应的收款单，关联 payment_intents.id，用于对账溯源；PawCoin 即时扣费时为空';
COMMENT ON COLUMN id_card_hd_purchases.purchased_at IS '购买完成时间（UTC 时区）';

-- ========== avatar_reviews ==========
COMMENT ON TABLE avatar_reviews IS '头像审核记录表：用户头像/宠物头像换新后的异步图像审核流水，低风险自动通过，中高风险进人工队列，判违规则重置为平台默认头像并推送通知';
COMMENT ON COLUMN avatar_reviews.id IS '主键，内部自增 id，不对外暴露';
COMMENT ON COLUMN avatar_reviews.subject_type IS '被审对象类型：USER_AVATAR=用户头像（subject_id 指向 users.id）、PET_AVATAR=宠物头像（subject_id 指向 pet_profiles.id）';
COMMENT ON COLUMN avatar_reviews.subject_id IS '被审对象 id：按 subject_type 关联 users.id 或 pet_profiles.id；仅内部使用，不对外暴露';
COMMENT ON COLUMN avatar_reviews.avatar_url IS '送审时的头像地址，同时作为「版本键」：出审核结果时若对象当前头像已换成别的，这条即作废(STALE_DISCARDED)。严禁写入业务日志';
COMMENT ON COLUMN avatar_reviews.risk_score IS '第三方图像审核给出的综合风险分，0.000–1.000，越高越可能违规；三方服务降级或尚未评分时为空。<0.6 自动通过、≥0.6 进人工队列、≥0.8 高优先级';
COMMENT ON COLUMN avatar_reviews.verdict IS '审核结论（评分中为空）：PASS=通过（自动或运营判过）、PENDING_REVIEW=待人工裁定、VIOLATION=运营判违规（已重置默认头像并推送）、STALE_DISCARDED=头像已换新导致本条作废（静默丢弃、不处置不推送）、DEGRADED_QUEUED=三方服务降级保守入队（绝不自动放行）';
COMMENT ON COLUMN avatar_reviews.status IS '审核流水线状态：QUEUED=已提交、评分中；AUTO_PASSED=低风险自动通过（终态，静默不推送）；MANUAL_PENDING=待运营人工处置；RESOLVED=已终态（结论见 verdict）';
COMMENT ON COLUMN avatar_reviews.priority IS '人工队列优先级：NORMAL=普通（默认，含三方降级入队）、HIGH=高优先（图像高置信违规或风险分 ≥0.8）';
COMMENT ON COLUMN avatar_reviews.created_at IS '送审时间（UTC 时区）';
COMMENT ON COLUMN avatar_reviews.updated_at IS '状态最后变更时间（UTC 时区）';

-- =====================================================================
-- 问诊模块（consult）字段中文注释
-- 覆盖 8 张表 / 114 个字段
-- 所有时间戳列均为 timestamptz，一律以 UTC 存储
-- 【PII】标记 = 健康数据/个人隐私，日志与对外接口严禁明文外泄
--
-- 【重要背景：两条并存的问诊发起链路】
--   · V1.0 旧链路：用户直接建 consult_sessions（status=WAITING）→ 兽医从「待接单会话」接单 → 免费、不建订单
--   · V1.1 付费链路：用户建 consult_requests（state=QUEUEING）→ 兽医接单 → 用户限时付款
--                    → 删除 request 行 + 建 consult_sessions（直接 IN_PROGRESS）+ 建 consult_orders
--   · consult_sessions 是两条链路共用的「会话载体表」，不下线；要停用的只是「用户直接建 WAITING 会话」这条发起方式。
--   · ⚠️ 判断某会话是否走旧链路，判据是 status=WAITING（付费链路的会话从不以 WAITING 落库）。
--     切勿用 source=DIRECT 判断——source 只是「来源标记」（用户直接发起 vs 分诊升级），与是否收费无关，
--     付费链路建的会话同样是 DIRECT。
-- =====================================================================


-- ========== consult_orders ==========
COMMENT ON TABLE consult_orders IS '兽医问诊付费订单表（V1.1 付费链路）。仅在用户付款成功后才创建，是持久记录、进用户订单中心。未扣费的请求不会在此建行，因此本表没有「已取消」状态——取消/超时留在 consult_requests 层并直接删行。';
COMMENT ON COLUMN consult_orders.id IS '主键（内部自增 id，不对外暴露）';
COMMENT ON COLUMN consult_orders.order_token IS '对外订单号，22 位随机字符串、不可枚举。App 与接口一律用它标识订单，绝不外露自增 id';
COMMENT ON COLUMN consult_orders.user_id IS '下单用户，关联 users 表';
COMMENT ON COLUMN consult_orders.vet_id IS '接单兽医，关联 vet_accounts 表（兽医账号与普通用户账号是两套独立的表）';
COMMENT ON COLUMN consult_orders.pet_profile_id IS '本次问诊针对的宠物档案，关联 pet_profiles 表';
COMMENT ON COLUMN consult_orders.status IS '订单状态：IN_PROGRESS=问诊会话进行中；COMPLETED=问诊已结束；REFUNDING=退款处理中；REFUNDED=已退款完成。无「已取消」态（没付钱就不会建单）';
COMMENT ON COLUMN consult_orders.amount IS '订单实付金额，单位为印尼盾 IDR 的最小单位（IDR 无小数分位，故 50000 即 Rp50,000）。下单时快照，后台改价不影响历史订单';
COMMENT ON COLUMN consult_orders.pay_channel IS '支付渠道：QRIS=印尼扫码现金支付（走第三方收款网关）；PAWCOIN=站内 PawCoin 余额扣减（1 koin = Rp1，无外部收款）。历史遗留的 DANA 渠道已于 2026-07-13 产品决策取消，不再产生新值';
COMMENT ON COLUMN consult_orders.payment_intent_id IS '关联的收款单，指向 payment_intents 表的自增 id。PawCoin 站内扣减无收款单，此列为空';
COMMENT ON COLUMN consult_orders.vet_payout IS '兽医到手金额快照，单位 IDR（如 30000 = Rp30,000，即 50000 × 60%）。成交时算好落库，后台调整分成比例不影响历史订单';
COMMENT ON COLUMN consult_orders.vet_share_rate_snapshot IS '兽医分成比例快照，百分数整数（如 60 表示 60%）。成交时定格';
COMMENT ON COLUMN consult_orders.unit_price_snapshot IS '单次问诊单价快照，单位 IDR（如 50000 = Rp50,000）。成交时定格，后台改价不影响历史与退款计算';
COMMENT ON COLUMN consult_orders.refund_rejected IS '退款是否曾被驳回：true=用户申请过退款但被运营驳回、订单已回落到 COMPLETED；false=未发生过退款驳回';
COMMENT ON COLUMN consult_orders.session_started_at IS '问诊会话（IM 聊天）开始时刻，UTC。付款成功建立 IM 会话时写入';
COMMENT ON COLUMN consult_orders.session_ended_at IS '问诊会话结束时刻，UTC。会话进入终态、订单转 COMPLETED 时写入';
COMMENT ON COLUMN consult_orders.paid_at IS '用户付款成功时刻，UTC';
COMMENT ON COLUMN consult_orders.created_at IS '订单创建时间，UTC（即付款成功建单的时刻）';
COMMENT ON COLUMN consult_orders.updated_at IS '最后更新时间，UTC';
COMMENT ON COLUMN consult_orders.rebroadcast_count IS '建单时从对应问诊请求快照过来的「重新广播次数」——即该请求在成单前，因兽医接单后用户未按时付款而被退回队列重新广播的累计次数。次数偏高提示可能有异常行为，供运营核查';
COMMENT ON COLUMN consult_orders.admin_verify_status IS '运营人工核查标记：NULL=未标记；TO_VERIFY=已标记为待核查；VERIFIED=已核查完毕。纯人工注记，不改变订单业务状态、不触发退款、不做任何自动拦截';
COMMENT ON COLUMN consult_orders.admin_verify_note IS '运营核查备注，内部可见，最长 255 字';
COMMENT ON COLUMN consult_orders.admin_verify_by IS '执行核查标记的后台管理员，关联 admin_accounts 表（后台账号与 App 用户/兽医账号完全隔离）';
COMMENT ON COLUMN consult_orders.admin_verify_at IS '运营核查标记时间，UTC';


-- ========== consult_sessions ==========
COMMENT ON TABLE consult_sessions IS '兽医问诊会话表（IM 聊天会话的载体）。V1.0 旧链路与 V1.1 付费链路共用本表：旧链路由用户直接建行（status=WAITING）等待兽医接单；付费链路在用户付款成功后才建行、且直接以 IN_PROGRESS 落库（从不经过 WAITING）。因此 status=WAITING 是「旧链路」的确证判据，切勿用 source 判断收费与否。';
COMMENT ON COLUMN consult_sessions.id IS '主键';
COMMENT ON COLUMN consult_sessions.user_id IS '发起问诊的用户，关联 users 表。用户注销时按隐私规则匿名化置空（解除关联，保留会话运营价值）';
COMMENT ON COLUMN consult_sessions.vet_id IS '接诊兽医，关联 vet_accounts 表。接单前为空；兽医退单时清空并重新回到等待态';
COMMENT ON COLUMN consult_sessions.status IS '会话状态：WAITING=等待兽医接单（仅 V1.0 旧链路会产生）；IN_PROGRESS=进行中（可 IM 聊天）；PENDING_CLOSE=兽医已点结束、进入 30 分钟评分与续聊窗口；CLOSED=已关闭（正常终态）；INTERRUPTED=被强制中断（兽医被封禁或用户被停用，终态，不评分不存档）；CANCELLED=用户在等待中主动取消（终态）。「占用中」只算 WAITING 与 IN_PROGRESS 两态（同一用户同时只能有 1 个进行中问诊）；PENDING_CLOSE 不占名额';
COMMENT ON COLUMN consult_sessions.source IS '问诊来源标记：DIRECT=用户直接发起；AI_UPGRADE=从 AI 分诊结果升级为真人兽医问诊。⚠️ 仅是来源标记，与是否收费无关——付费链路建的会话同样是 DIRECT';
COMMENT ON COLUMN consult_sessions.waiting_started_at IS '等待接单的计时起点，UTC。等待满 1 分钟（60 秒）未被接单即视为超时，前端弹出超时提示；用户选择「继续等待」会重置本时间。仅 V1.0 旧链路使用';
COMMENT ON COLUMN consult_sessions.im_conversation_id IS '腾讯 IM 的会话标识，用于定位这次问诊的聊天窗口。接单成功后写入；兽医退单时清空';
COMMENT ON COLUMN consult_sessions.created_at IS '会话创建时间，UTC';
COMMENT ON COLUMN consult_sessions.updated_at IS '最后更新时间，UTC。⚠️ 不可用于历史列表的显示时间与排序（会被后续动作刷新导致时间漂移），显示口径应取中断时刻或兽医点结束的时刻';
COMMENT ON COLUMN consult_sessions.triage_task_id IS '来源的 AI 分诊任务，关联 triage_tasks 表。仅 source=AI_UPGRADE 时填写';
COMMENT ON COLUMN consult_sessions.ai_danger_level IS 'AI 分诊危险等级快照，仅 GREEN（绿色，可自行观察）或 YELLOW（黄色，建议问诊）两种取值。【红线】绝不含 RED（红色态需立即就医、零商业引流，后端与数据库双重兜底拒绝）。非 AI 升级来源时为空';
COMMENT ON COLUMN consult_sessions.ai_symptom_text IS '【PII】症状描述文字快照（用户自填，或从 AI 分诊带入）。属健康数据，日志严禁记录明文';
COMMENT ON COLUMN consult_sessions.ai_image_refs IS '【PII】症状图片列表，JSON 数组，存的是私密存储桶的对象 key（引用），取用时才现场签发短时效 URL。【红线】绝不存签名 URL。属健康数据';
COMMENT ON COLUMN consult_sessions.version IS '乐观锁版本号。用于兽医并发抢单时裁决（同一会话只有一人能接单成功，其余提示「已被接走」）';
COMMENT ON COLUMN consult_sessions.pending_close_started_at IS '兽医点击结束的时刻，UTC。是 30 分钟评分与续聊窗口的计时起点；同时也是问诊历史列表的显示时间与排序口径（一次性写入、后续不变，避免时间漂移）';
COMMENT ON COLUMN consult_sessions.closed_reason IS '会话关闭原因：RATED=用户完成评分后关闭；UNRATED=30 分钟窗口到期用户仍未评分，系统自动关闭。未关闭时为空';
COMMENT ON COLUMN consult_sessions.rating_prompt_state IS '评分补弹提醒状态：NONE=无需补弹（已评分或会话仍进行中）；PENDING=超时未评分，下次进入问诊页补弹一次评分框；PROMPTED=已补弹过、不再弹（用户仍可主动评分，跳过则永久无评分）';
COMMENT ON COLUMN consult_sessions.interrupted_reason IS '会话被强制中断的原因：VET_BANNED=运营封禁了接诊兽医；USER_DEACTIVATED=运营停用了该用户。未被中断时为空';
COMMENT ON COLUMN consult_sessions.interrupted_at IS '会话被强制中断的时刻，UTC。也是被中断问诊在历史列表的显示时间与排序口径';
COMMENT ON COLUMN consult_sessions.release_count IS '兽医退单次数：兽医接单后又退回（会话从进行中退回等待、重新广播）的累计次数。每个请求正常最多退单 2 次，大于 2 即为异常信号，交运营人工处理';
COMMENT ON COLUMN consult_sessions.vet_diagnosis IS '【PII】兽医最终诊断，JSON 结构化表单，兽医结束会话时定格。含：诊断结论（必填）、一般建议、是否需用药、药名、用药频次、复诊时间、恶化征兆、多久内恶化须就医。属健康数据，日志严禁记录';
COMMENT ON COLUMN consult_sessions.suspend_deadline_at IS '封禁挂起截止时刻，UTC（服务端权威 15 分钟）。当接诊兽医被封禁、而这是一场付费会话时置此值：会话保持 IN_PROGRESS 不变（IM 仍可用、用户仍在控制、体验不被劫持），用户可在窗口内自行结束逃生；到期或用户主动逃生则强制中断并退款。非空即表示「挂起中」。免费会话遇兽医封禁是立即中断，本列恒为空';


-- ========== consult_requests ==========
COMMENT ON TABLE consult_requests IS '兽医问诊请求表（V1.1 付费链路的「付款前」临时态）。用户发起问诊先在此建行入队等待兽医接单，兽医接单后用户限时付款；付款成功即删除本行并转为 consult_orders + consult_sessions。取消/超时一律物理删行——因此本表没有「已取消」状态，这也是订单中心看不到「已取消订单」的原因。';
COMMENT ON COLUMN consult_requests.id IS '主键（内部自增 id，不对外暴露）';
COMMENT ON COLUMN consult_requests.request_token IS '对外请求标识，22 位随机字符串、不可枚举。App 与接口一律用它标识请求';
COMMENT ON COLUMN consult_requests.user_id IS '发起请求的用户，关联 users 表';
COMMENT ON COLUMN consult_requests.pet_profile_id IS '本次问诊针对的宠物档案，关联 pet_profiles 表';
COMMENT ON COLUMN consult_requests.vet_id IS '接单兽医，关联 vet_accounts 表。接单前为空';
COMMENT ON COLUMN consult_requests.state IS '请求状态，仅两个活态：QUEUEING=已入队、等待兽医接单；ACCEPTED_AWAIT_PAY=兽医已接单、等待用户在付款窗内付款。无「已取消」态（取消/超时直接删行，付款成功也删行）';
COMMENT ON COLUMN consult_requests.queue_deadline_at IS '排队等待截止时刻，UTC（服务端权威，入队后 1 分钟）。到期仍无兽医接单即视为排队超时，请求失败并记入 failed_consult_requests';
COMMENT ON COLUMN consult_requests.pay_deadline_at IS '付款截止时刻，UTC（服务端权威，兽医接单后 5 分钟。注：早期版本为 1.5 分钟，V1.1 已延长为 5 分钟）。到期未付款则请求退回队列重新广播给其他兽医；重播次数达上限（默认 5 次）或请求存活超过 30 分钟，则彻底失败、不再回队';
COMMENT ON COLUMN consult_requests.paused_at IS '付款窗暂停时刻，UTC。用户在付款过程中跳去充值 PawCoin 时置此值：付款倒计时暂停、该请求不被超时扫描器扫走；返回后按「剩余时长」顺延出新的付款截止时间。非空即表示「暂停中」';
COMMENT ON COLUMN consult_requests.rebroadcast_count IS '重新广播次数：因兽医接单后用户未按时付款、请求被退回队列重新广播的累计次数。达上限（默认 5 次）即彻底失败不再回队；成单时会快照进 consult_orders 供运营核查';
COMMENT ON COLUMN consult_requests.created_at IS '请求创建（入队）时间，UTC';
COMMENT ON COLUMN consult_requests.updated_at IS '最后更新时间，UTC';
COMMENT ON COLUMN consult_requests.source IS '问诊来源标记：DIRECT=用户直接发起并自填病例；AI_UPGRADE=从 AI 分诊结果升级而来（AI 上下文由后端从分诊任务拉取快照）。⚠️ 仅是来源标记，与收费无关——付费链路的请求同样多为 DIRECT';
COMMENT ON COLUMN consult_requests.triage_task_id IS '来源的 AI 分诊任务，关联 triage_tasks 表。仅 source=AI_UPGRADE 时填写';
COMMENT ON COLUMN consult_requests.ai_danger_level IS 'AI 分诊危险等级快照，仅 GREEN（绿色）或 YELLOW（黄色）两种取值；DIRECT 来源为空。【红线】绝不含 RED——红色态零商业引流，RED 永不入队（后端兜底拒绝 + 数据库 CHECK 约束双重保障）';
COMMENT ON COLUMN consult_requests.symptom_text IS '【PII】症状描述文字，兽医接单前据此判断是否接单。属健康数据，日志严禁记录明文';
COMMENT ON COLUMN consult_requests.image_object_keys IS '【PII】症状图片列表，JSON 数组，存的是私密存储桶的对象 key（引用），取用时才现场签发短时效 URL。【红线】绝不存签名 URL。属健康数据';


-- ========== consult_ratings ==========
COMMENT ON TABLE consult_ratings IS '问诊评分表。用户在兽医结束问诊后的 30 分钟窗口内评分，1-5 星必填 + 100 字以内评语选填。每场会话至多一条评分。评分仅运营可见（不对外展示给其他用户）。';
COMMENT ON COLUMN consult_ratings.id IS '主键';
COMMENT ON COLUMN consult_ratings.session_id IS '被评分的问诊会话，关联 consult_sessions 表（一场会话至多一条评分，唯一约束）';
COMMENT ON COLUMN consult_ratings.vet_id IS '被评分的兽医，关联 vet_accounts 表';
COMMENT ON COLUMN consult_ratings.user_id IS '评分用户，关联 users 表。用户注销时匿名化置空（解除关联，但保留星级与评语供运营参考）';
COMMENT ON COLUMN consult_ratings.stars IS '评分星级，1-5 的整数，必填（数据库 CHECK 约束限定范围）';
COMMENT ON COLUMN consult_ratings.comment IS '【PII】评语文字，选填，最长 100 字。用户自由输入，可能含个人信息';
COMMENT ON COLUMN consult_ratings.created_at IS '评分提交时间，UTC';


-- ========== consult_anomalies ==========
COMMENT ON TABLE consult_anomalies IS '问诊异常工单表（针对「已建立会话」后出现异常的情形，供运营后台处理）。【红线】仅承载会话的元数据，绝不包含 IM 聊天正文、AI 分诊上下文或用户上传的图片。一场会话至多一张工单；工单不可删除，处理完毕即归档为 RESOLVED。';
COMMENT ON COLUMN consult_anomalies.id IS '主键';
COMMENT ON COLUMN consult_anomalies.session_id IS '出现异常的问诊会话，关联 consult_sessions 表（一场会话至多一张工单，唯一约束兜底去重）';
COMMENT ON COLUMN consult_anomalies.user_id IS '涉事用户，关联 users 表。用户注销匿名化后会话的 user_id 会被置空，故本列可为空';
COMMENT ON COLUMN consult_anomalies.vet_id IS '涉事兽医，关联 vet_accounts 表';
COMMENT ON COLUMN consult_anomalies.session_started_at IS '会话开始时刻快照，UTC（异常发生时从会话复制而来）';
COMMENT ON COLUMN consult_anomalies.session_ended_at IS '会话结束时刻快照，UTC（异常发生时从会话复制而来）';
COMMENT ON COLUMN consult_anomalies.session_status IS '异常发生时的会话状态快照（如 INTERRUPTED）。是文本快照，不随会话后续变化而更新';
COMMENT ON COLUMN consult_anomalies.anomaly_type IS '异常类型：VET_BANNED=运营封禁兽医导致其进行中会话被强制中断。当前版本这是唯一的触发来源';
COMMENT ON COLUMN consult_anomalies.status IS '工单状态：OPEN=待处理；RESOLVED=已处理（即归档，工单不可删除）';
COMMENT ON COLUMN consult_anomalies.internal_note IS '运营内部备注，用户不可见。覆盖式写入（新备注替换旧备注）';
COMMENT ON COLUMN consult_anomalies.resolution_image_key IS '处理凭证图片，存的是私密存储桶的对象 key（引用），选填。【红线】绝不存签名 URL';
COMMENT ON COLUMN consult_anomalies.resolved_by IS '处理该工单的后台管理员，关联 admin_accounts 表';
COMMENT ON COLUMN consult_anomalies.resolved_at IS '工单处理（归档）时间，UTC';
COMMENT ON COLUMN consult_anomalies.created_at IS '工单创建时间，UTC';
COMMENT ON COLUMN consult_anomalies.updated_at IS '最后更新时间，UTC';


-- ========== consult_order_stage_events ==========
COMMENT ON TABLE consult_order_stage_events IS '问诊订单节点事件表（consult_orders 的子表）。订单每经过一个关键节点（接单/付款/会话起止/退款）就新增一行，只增不改不删（append-only），用于对账与审计留痕。故本表没有更新时间列。';
COMMENT ON COLUMN consult_order_stage_events.id IS '主键';
COMMENT ON COLUMN consult_order_stage_events.consult_order_id IS '所属订单，关联 consult_orders 表的自增 id';
COMMENT ON COLUMN consult_order_stage_events.event_type IS '节点事件类型：ACCEPTED=兽医接单；PAID=用户付款成功；SESSION_STARTED=问诊会话开始；SESSION_ENDED=问诊会话结束；REFUND_REQUESTED=用户发起退款申请；REFUND_COMPLETED=退款完成；REFUND_REJECTED=退款被运营驳回';
COMMENT ON COLUMN consult_order_stage_events.occurred_at IS '该节点实际发生的业务时刻，UTC（可能早于本行的写入时间 created_at）';
COMMENT ON COLUMN consult_order_stage_events.note IS '节点备注，选填，最长 255 字';
COMMENT ON COLUMN consult_order_stage_events.created_at IS '本行写入数据库的时间，UTC';


-- ========== failed_consult_requests ==========
COMMENT ON TABLE failed_consult_requests IS '问诊请求未成功记录表（供运营后台跟进）。只记录「从未建立会话」就失败的请求（用户取消/等待超时/系统故障），与「已建立会话后出现异常」的 consult_anomalies 工单相互独立。系统自动落库，运营可标记跟进并归档。';
COMMENT ON COLUMN failed_consult_requests.id IS '主键（内部自增 id，不对外暴露）';
COMMENT ON COLUMN failed_consult_requests.request_token IS '对外请求标识，不可枚举的随机字符串（唯一）。运营用它定位这次失败的请求';
COMMENT ON COLUMN failed_consult_requests.user_id IS '发起请求的用户，关联 users 表';
COMMENT ON COLUMN failed_consult_requests.session_id IS '内部关联的会话 id（关联 consult_sessions 表），仅供技术排查、不对外暴露。多数情况下为空（本表记录的正是「未建立会话」的失败）';
COMMENT ON COLUMN failed_consult_requests.submitted_at IS '用户提交问诊请求的时刻，UTC';
COMMENT ON COLUMN failed_consult_requests.cancelled_at IS '请求失败/被取消的时刻，UTC。与 submitted_at 相减即用户实际等待的时长';
COMMENT ON COLUMN failed_consult_requests.cancel_reason IS '失败原因：USER_CANCEL=用户在等待中主动取消；TIMEOUT=等待超时无兽医接单；SYSTEM_FAILURE=系统故障。其中 SYSTEM_FAILURE 必须先标记已跟进才允许归档';
COMMENT ON COLUMN failed_consult_requests.online_vet_count IS '请求失败当时的在线兽医数量快照。用于判断失败是因为「无兽医在线」还是「有兽医但没人接」';
COMMENT ON COLUMN failed_consult_requests.followed_up IS '运营是否已跟进：true=已标记跟进过；false=尚未跟进。系统故障类（SYSTEM_FAILURE）必须为 true 才能归档';
COMMENT ON COLUMN failed_consult_requests.note IS '运营跟进备注，选填，最长 1000 字';
COMMENT ON COLUMN failed_consult_requests.archived_at IS '归档时间，UTC。非空即表示该记录已归档、不再出现在待处理列表';
COMMENT ON COLUMN failed_consult_requests.created_at IS '本行创建时间，UTC';
COMMENT ON COLUMN failed_consult_requests.updated_at IS '最后更新时间，UTC';


-- ========== ai_consult_orders ==========
COMMENT ON TABLE ai_consult_orders IS 'AI 分诊报告付费解锁订单表。与真人兽医问诊订单（consult_orders）是完全独立的两套订单，各自编号。仅付费解锁才建订单：PawCoin 站内扣减为同步、直接建成已完成；扫码现金（QRIS）为异步、先建待付款再等到账。用免费额度解锁不建订单（直接记在分诊任务上）。';
COMMENT ON COLUMN ai_consult_orders.id IS '主键（内部自增 id，不对外暴露）';
COMMENT ON COLUMN ai_consult_orders.order_token IS '对外订单号，22 位随机字符串、不可枚举（唯一）';
COMMENT ON COLUMN ai_consult_orders.user_id IS '下单用户，关联 users 表';
COMMENT ON COLUMN ai_consult_orders.triage_task_id IS '被解锁的 AI 分诊任务，关联 triage_tasks 表';
COMMENT ON COLUMN ai_consult_orders.amount IS '订单金额，单位为印尼盾 IDR 的最小单位（IDR 无小数分位）。PawCoin 支付时按 1 koin = Rp1 等值扣减。下单时快照成交价，后台改价不影响历史订单';
COMMENT ON COLUMN ai_consult_orders.pay_channel IS '支付渠道：QRIS=印尼扫码现金支付（异步到账）；PAWCOIN=站内 PawCoin 余额扣减（同步完成）。历史遗留的 DANA 渠道已于 2026-07-13 产品决策取消，不再产生新值';
COMMENT ON COLUMN ai_consult_orders.payment_intent_token IS '关联的收款单标识，指向 payment_intents 表的对外 token（不可枚举）。现金支付到账回调时靠它反查订单、拿到要解锁的分诊任务。PawCoin 站内扣减无收款单，此列为空';
COMMENT ON COLUMN ai_consult_orders.status IS '订单状态：PENDING_PAYMENT=现金扫码已生成收款单、等待到账（此态尚未解锁报告）；COMPLETED=已支付、报告已解锁（PawCoin 直接建成此态）；ABNORMAL=对账异常（钱已到账但分诊任务缺失、或该任务已被其他方式解锁、或金额不符等），系统只记录不报错，留待运营对账处理';
COMMENT ON COLUMN ai_consult_orders.paid_at IS '支付到账时刻，UTC。订单转为 COMPLETED 时写入';
COMMENT ON COLUMN ai_consult_orders.created_at IS '订单创建时间，UTC';
COMMENT ON COLUMN ai_consult_orders.updated_at IS '最后更新时间，UTC';

-- ============================================================================
-- 资金域（pay）字段中文注释 —— 覆盖 payment_intents / refund_requests /
-- ledger_entries / pawcoin_transactions / pawcoin_wallets / pawcoin_config /
-- pawcoin_topup_tiers / vet_settlements / pricing_config
--
-- 金额单位统一约定（已从代码/迁移查证）：
--   * 所有 bigint 金额列 = IDR（印尼盾）最小币种单位。IDR 无小数位，故 10000 = Rp10.000。
--   * PawCoin（koin）与 IDR 1:1 等值（1 koin = Rp1），故钱包/流水的数值同为该单位。
--   * 收款网关 = 印尼 GemPay（QRIS 扫码）；退款出款走 Iris 转账接口。
-- 时间戳一律 timestamptz、UTC 存储（唯一例外：vet_settlements.period 用雅加达时间 WIB 的年月）。
-- ============================================================================


-- ========== payment_intents ==========
COMMENT ON TABLE payment_intents IS '支付意图表：所有收费场景（兽医问诊 / PawCoin 充值 / AI 详情解锁 / 身份证高清图）的资金起点，一次付款申请一行';
COMMENT ON COLUMN payment_intents.id IS '主键（内部自增 id，不对外暴露）';
COMMENT ON COLUMN payment_intents.public_token IS '对外的付款单标识，随机不可枚举，App 和外部链接只用它（绝不用 id）';
COMMENT ON COLUMN payment_intents.user_id IS '发起付款的用户，关联 users 表';
COMMENT ON COLUMN payment_intents.purpose IS '付款用途：VET_CONSULT=兽医问诊；PAWCOIN_TOPUP=PawCoin 充值；AI_UNLOCK=AI 分诊详情解锁；ID_HD=宠物身份证高清图下载';
COMMENT ON COLUMN payment_intents.channel IS '支付渠道：QRIS=扫码真钱支付（走 GemPay 收款）；PAWCOIN=站内 PawCoin 余额扣减（无外部收款）。历史约束里的 DANA 已废弃不再产生';
COMMENT ON COLUMN payment_intents.amount IS '付款金额，IDR 最小单位（IDR 无小数，10000 即 Rp10.000）；必须为正数';
COMMENT ON COLUMN payment_intents.currency IS '币种代码，恒为 IDR（印尼盾）';
COMMENT ON COLUMN payment_intents.status IS '付款状态：PENDING=待支付（唯一非终态）；PAID=已到账；FAILED=支付失败/被取消；EXPIRED=超时未付。三个终态不可再变';
COMMENT ON COLUMN payment_intents.gateway_ref IS '支付网关（GemPay）那边的订单号，收款单创建成功后回填；全表唯一，用于回调与轮询的去重比对';
COMMENT ON COLUMN payment_intents.gateway_meta IS '网关返回的原始信息快照（含二维码内容等，已脱敏，不含签名和凭证）';
COMMENT ON COLUMN payment_intents.version IS '乐观锁版本号（系统内部用，防止回调与轮询同时改状态导致冲突）';
COMMENT ON COLUMN payment_intents.created_at IS '付款单创建时间（UTC）';
COMMENT ON COLUMN payment_intents.updated_at IS '最后更新时间（UTC）';
COMMENT ON COLUMN payment_intents.expires_at IS '付款窗口截止时刻（UTC）。仅 PawCoin 充值单填写 = 下单时刻 + 60 分钟（充值二维码 60 分钟有效，窗口内重复打开复用同一个 QR，超时自动置 EXPIRED）；其余用途留空表示不按时间过期';


-- ========== refund_requests ==========
COMMENT ON TABLE refund_requests IS '退款申请表：一笔问诊订单最多一条。两段审批——①客服判定是否该退 ②主管审批 + 财务打款；三个角色（提交/审批/打款）必须是不同的人，超级管理员也不豁免';
COMMENT ON COLUMN refund_requests.id IS '主键（内部自增 id，不对外暴露）';
COMMENT ON COLUMN refund_requests.refund_token IS '对外的退款单标识，随机不可枚举，App 与后台链接只用它';
COMMENT ON COLUMN refund_requests.order_id IS '要退款的问诊订单，关联 consult_orders 表；一个订单只能有一条退款申请';
COMMENT ON COLUMN refund_requests.related_ticket_id IS '关联的客服工单，关联 feedback_tickets 表；可为空（工单被删除时此处置空，退款申请本身不受影响）';
COMMENT ON COLUMN refund_requests.user_id IS '申请退款的用户，关联 users 表';
COMMENT ON COLUMN refund_requests.need_decision IS '第一段「是否该退」的客服判定：PENDING=待判定；APPROVED=同意退款（用户可去选收款方式）；REJECTED=驳回（订单回落为已完成并通知用户）';
COMMENT ON COLUMN refund_requests.submitter_admin_id IS '做出第一段判定的客服，关联 admin_accounts 表';
COMMENT ON COLUMN refund_requests.approval_status IS '第二段「退款申请审批」状态：空=用户尚未填收款方式；PENDING_APPROVAL=待主管审批；APPROVED=主管已批准，待财务打款；REJECTED=主管驳回；PROCESSING=财务已发起打款，处理中；DONE=打款完成（PawCoin 订单为系统即时退币，也直接置 DONE）';
COMMENT ON COLUMN refund_requests.approver_admin_id IS '第二段审批的主管，关联 admin_accounts 表；不得与提交人、打款人相同';
COMMENT ON COLUMN refund_requests.payer_admin_id IS '执行打款的财务，关联 admin_accounts 表；不得与提交人、审批人相同。PawCoin 即时退币为系统执行，此处留空';
COMMENT ON COLUMN refund_requests.order_amount IS '订单成交金额快照，IDR 最小单位（Rp）';
COMMENT ON COLUMN refund_requests.channel_fee IS '出款渠道手续费，IDR 最小单位（BCA 银行转账 0；OVO 2500；GoPay 2500）。由后端按渠道权威计算，前端不可传';
COMMENT ON COLUMN refund_requests.net_amount IS '用户实际到手净额，IDR 最小单位 = 订单金额 − 渠道手续费；用户选定收款渠道后由后端算出并快照';
COMMENT ON COLUMN refund_requests.payout_channel IS '用户选择的收款渠道：BCA=银行转账（免手续费）；OVO=OVO 电子钱包；GOPAY=GoPay 电子钱包';
COMMENT ON COLUMN refund_requests.payout_account IS '【PII】【加密存储】用户的收款账号（银行卡号 / 电子钱包号），AES-GCM 密文（base64）落库，绝不进日志';
COMMENT ON COLUMN refund_requests.account_holder_name IS '【PII】【加密存储】收款账户的开户人姓名，AES-GCM 密文（base64）落库，绝不进日志';
COMMENT ON COLUMN refund_requests.created_at IS '退款申请创建时间（UTC）';
COMMENT ON COLUMN refund_requests.updated_at IS '最后更新时间（UTC）';
COMMENT ON COLUMN refund_requests.approval_note IS '主管审批通过时填写的备注（通过时必填）';
COMMENT ON COLUMN refund_requests.reject_reason IS '主管驳回退款申请的理由（驳回时必填）';
COMMENT ON COLUMN refund_requests.payment_proof IS '打款凭证号（出款接口返回的 disbursementRef），非敏感信息，可用于对账留痕';
COMMENT ON COLUMN refund_requests.approved_at IS '主管审批通过的时刻（UTC）';
COMMENT ON COLUMN refund_requests.rejected_at IS '主管驳回的时刻（UTC）';
COMMENT ON COLUMN refund_requests.paid_at IS '财务打款完成的时刻（UTC）';


-- ========== ledger_entries ==========
COMMENT ON TABLE ledger_entries IS '总账分录表（双分录记账）：平台一切资金变动的唯一事实源，只增不改不删；写错了只能补一条反向分录，绝不修改旧行';
COMMENT ON COLUMN ledger_entries.id IS '主键';
COMMENT ON COLUMN ledger_entries.entry_group IS '分录组号（UUID）：同一笔资金事件产生的多条分录共享同一个组号，组内借方合计 = 贷方合计，恒平衡';
COMMENT ON COLUMN ledger_entries.account IS '记账科目：CASH_IN=平台收到的现金；FLOAT_LIABILITY=欠用户的 PawCoin 浮存负债（是用户钱包余额的账面镜像，对账基准）；VET_PAYABLE=应付兽医；VET_PAID=已付兽医；PLATFORM_REVENUE=平台确认收入；REFUND_OUT=退款流出；FORFEITURE=作废/沉没（用户注销时放弃的余额，刻意独立于平台收入，不污染营收统计）';
COMMENT ON COLUMN ledger_entries.direction IS '借贷方向：DEBIT=借方；CREDIT=贷方。同一组内 Σ借方 = Σ贷方';
COMMENT ON COLUMN ledger_entries.amount IS '分录金额，IDR 最小单位（PawCoin 同单位，1 koin = Rp1）。恒为正数——增减方向由 direction 表达，不用正负号';
COMMENT ON COLUMN ledger_entries.user_id IS '这条分录归属的用户，关联 users 表；平台侧科目（如现金、收入）的分录可为空';
COMMENT ON COLUMN ledger_entries.ref_type IS '业务来源类型，取值随调用方：PAYMENT_INTENT=充值到账；VET_CONSULT=问诊消费；AI_UNLOCK=AI 详情解锁消费；ID_HD=身份证高清图消费；refund_request=退款出款；account_deletion=注销余额作废';
COMMENT ON COLUMN ledger_entries.ref_id IS '业务来源单据的 id，配合 ref_type 定位（如支付意图 id / 订单 id / 退款单 id）；无对应单据时为空';
COMMENT ON COLUMN ledger_entries.idempotency_key IS '幂等键：同一笔资金事件重复提交时靠它去重，保证不重复记账';
COMMENT ON COLUMN ledger_entries.created_at IS '分录记账时间（UTC）；本表无更新时间——只增不改';


-- ========== pawcoin_transactions ==========
COMMENT ON TABLE pawcoin_transactions IS 'PawCoin 用户流水表：用户钱包每变动一次记一条，供 App 的余额/流水页展示；充值失败不写本表（只有成功才落）';
COMMENT ON COLUMN pawcoin_transactions.id IS '主键';
COMMENT ON COLUMN pawcoin_transactions.user_id IS '流水归属用户，关联 users 表';
COMMENT ON COLUMN pawcoin_transactions.delta IS '本次变动量，单位 koin（1 koin = Rp1）。正数=进账（充值/退回/赠送），负数=出账（消费）';
COMMENT ON COLUMN pawcoin_transactions.type IS '流水类型：TOPUP=充值到账；SPEND=消费扣减；REFUND=退款退回；BONUS=平台赠送（仅用于未交付订单转 PawCoin 的场景）';
COMMENT ON COLUMN pawcoin_transactions.ref_type IS '业务来源类型，取值随调用方：PAYMENT_INTENT=充值到账；VET_CONSULT=问诊消费；AI_UNLOCK=AI 详情解锁；ID_HD=身份证高清图；可为空';
COMMENT ON COLUMN pawcoin_transactions.ref_id IS '业务来源单据的 id，配合 ref_type 定位；可为空';
COMMENT ON COLUMN pawcoin_transactions.entry_group IS '对应的总账分录组号，用于把这条用户流水勾稽回总账（ledger_entries.entry_group）';
COMMENT ON COLUMN pawcoin_transactions.created_at IS '流水发生时间（UTC）';


-- ========== pawcoin_wallets ==========
COMMENT ON TABLE pawcoin_wallets IS 'PawCoin 钱包表：一个用户一个钱包，记录当前余额。余额无有效期，永不过期';
COMMENT ON COLUMN pawcoin_wallets.id IS '主键';
COMMENT ON COLUMN pawcoin_wallets.user_id IS '钱包所属用户，关联 users 表；全表唯一（一人一钱包）';
COMMENT ON COLUMN pawcoin_wallets.balance IS '当前余额，单位 koin（1 koin = Rp1）；数据库层强制不得为负';
COMMENT ON COLUMN pawcoin_wallets.version IS '乐观锁版本号（系统内部用，防并发改余额出错）';
COMMENT ON COLUMN pawcoin_wallets.updated_at IS '余额最后变动时间（UTC）';


-- ========== pawcoin_config ==========
COMMENT ON TABLE pawcoin_config IS 'PawCoin 运营配置表：固定只有一行（id=1），由管理后台修改，改动记入配置变更日志';
COMMENT ON COLUMN pawcoin_config.id IS '主键，恒为 1（单行配置表，数据库层强制）';
COMMENT ON COLUMN pawcoin_config.premium_rate IS '退款转 PawCoin 的溢价百分比（0-50，整数，如 10 表示多给 10%）；仅用于「服务未交付且用户选择转成 PawCoin」的场景';
COMMENT ON COLUMN pawcoin_config.topup_paused IS '是否暂停充值：true=暂停，用户无法再充值 PawCoin（浮存规模到达门槛时的运营开关）；false=正常开放';
COMMENT ON COLUMN pawcoin_config.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN pawcoin_config.updated_at IS '最后修改时间（UTC）';


-- ========== pawcoin_topup_tiers ==========
COMMENT ON TABLE pawcoin_topup_tiers IS 'PawCoin 充值档位表：App 充值页展示的固定金额档位，可由管理后台增删/启停；系统强制至少保留 1 个启用档位';
COMMENT ON COLUMN pawcoin_topup_tiers.id IS '主键';
COMMENT ON COLUMN pawcoin_topup_tiers.tier_key IS '档位对外标识（如 10k / 25k / 50k / 100k），全表唯一，前端按它识别档位';
COMMENT ON COLUMN pawcoin_topup_tiers.amount_idr IS '该档充值金额，IDR 最小单位（如 10000 = Rp10.000）；到账 koin 与之 1:1 相等';
COMMENT ON COLUMN pawcoin_topup_tiers.enabled IS '是否启用：true=在 App 充值页展示；false=下架不展示（全部档位不可同时下架）';
COMMENT ON COLUMN pawcoin_topup_tiers.sort_order IS '展示排序序号，数字越小越靠前';
COMMENT ON COLUMN pawcoin_topup_tiers.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN pawcoin_topup_tiers.updated_at IS '最后修改时间（UTC）';


-- ========== vet_settlements ==========
COMMENT ON TABLE vet_settlements IS '兽医月度结算表：每月 1 日（雅加达时间）自动汇总上月已完成问诊订单，一个兽医一个月一行，供财务对账打款';
COMMENT ON COLUMN vet_settlements.id IS '主键';
COMMENT ON COLUMN vet_settlements.vet_id IS '结算对象兽医，关联 vets 表；(兽医, 月份) 组合唯一，重跑不会重复生成';
COMMENT ON COLUMN vet_settlements.period IS '结算月份，格式 YYYY-MM，按雅加达时间 WIB 计（刻意不用 UTC，对应印尼「每月 1 号」的本地语义）';
COMMENT ON COLUMN vet_settlements.order_count IS '该月计入结算的已完成问诊订单笔数';
COMMENT ON COLUMN vet_settlements.gross_amount IS '该月成交额合计（用户付的总额），IDR 最小单位（Rp）';
COMMENT ON COLUMN vet_settlements.payout_amount IS '该月兽医到手合计（按分成比例计算后的金额快照之和），IDR 最小单位（Rp）；这才是实际要打给兽医的钱';
COMMENT ON COLUMN vet_settlements.status IS '财务对账状态：PENDING_FINANCE=已生成待财务打款；PAID=财务已确认打款；ARCHIVED=已归档。兽医 App 端只看到两态（PENDING_FINANCE 显示为待结算，PAID/ARCHIVED 显示为已结算）';
COMMENT ON COLUMN vet_settlements.generated_at IS '该月结算单由定时任务生成的时刻（UTC）';
COMMENT ON COLUMN vet_settlements.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN vet_settlements.updated_at IS '最后更新时间（UTC）';
COMMENT ON COLUMN vet_settlements.payment_proof IS '打款凭证（银行参考号 / 说明文字 / 凭证图片链接），由财务确认打款时回填';
COMMENT ON COLUMN vet_settlements.paid_at IS '财务确认打款的时刻（UTC）';
COMMENT ON COLUMN vet_settlements.archived_at IS '归档的时刻（UTC）';
COMMENT ON COLUMN vet_settlements.settled_by IS '最后执行确认打款/归档操作的管理员，关联 admin_accounts 表';


-- ========== pricing_config ==========
COMMENT ON TABLE pricing_config IS '平台定价配置表：固定只有一行（id=1），由管理后台修改。改价只影响之后的新订单——历史订单已把价格快照存在自己身上，不受影响';
COMMENT ON COLUMN pricing_config.id IS '主键，恒为 1（单行配置表，数据库层强制）';
COMMENT ON COLUMN pricing_config.vet_consult_price IS '兽医单次问诊价格，IDR 最小单位（默认 50000 = Rp50.000）';
COMMENT ON COLUMN pricing_config.vet_share_rate IS '兽医分成百分比（0-100 的整数，默认 60 表示 60%）；兽医到手 = 问诊价 × 该比例 ÷ 100';
COMMENT ON COLUMN pricing_config.ai_unlock_price IS 'AI 分诊详情付费解锁价格，IDR 最小单位（默认 10000 = Rp10.000）';
COMMENT ON COLUMN pricing_config.id_hd_download_price IS '宠物身份证高清图下载价格，IDR 最小单位（默认 5000 = Rp5.000）';
COMMENT ON COLUMN pricing_config.monthly_free_quota IS '每位用户每月免费解锁 AI 分诊详情的次数（0-35，默认 1）';
COMMENT ON COLUMN pricing_config.created_at IS '创建时间（UTC）';
COMMENT ON COLUMN pricing_config.updated_at IS '最后修改时间（UTC）';

-- ========== content_posts ==========
COMMENT ON TABLE content_posts IS '内容帖子表：用户在社区发布的三类内容（日常分享/成长时刻/专业科普）的主表，Feed 流、内容详情、成长档案时间线都读这张表';
COMMENT ON COLUMN content_posts.id IS '主键，自增内部 id（不对外暴露）';
COMMENT ON COLUMN content_posts.author_id IS '发帖作者，关联 users 表 id（种子内容的作者是虚拟账号）';
COMMENT ON COLUMN content_posts.type IS '内容类型：DAILY=日常分享；GROWTH_MOMENT=成长日历快乐时刻（必须绑定一个宠物档案，会进该宠物的成长时间线）；KNOWLEDGE=专业科普。App 上的「全部」只是浏览筛选，不是可发布的类型';
COMMENT ON COLUMN content_posts.pet_id IS '这条内容关联的宠物档案，关联 pet_profiles 表 id；只有 GROWTH_MOMENT 有值，日常/科普为空';
COMMENT ON COLUMN content_posts.text IS '帖子正文，最长 1000 字，可为空（纯图帖）【PII】用户自由输入，可能含个人信息';
COMMENT ON COLUMN content_posts.image_urls IS '帖子配图列表（JSON 数组，最多 9 张）。存的是公开桶的完整 CDN URL（不是 objectKey，前端可直接展示）';
COMMENT ON COLUMN content_posts.danger_level IS '关联问诊结论的危险等级：GREEN=绿色（可自行观察）/YELLOW=黄色（建议就医）/RED=红色（紧急就医）。仅成长日历存档问诊结果时可能有值，普通发布为空';
COMMENT ON COLUMN content_posts.status IS '内容可见性状态：PUBLISHED=已发布对所有人可见；UNDER_REVIEW=审核挂起（高风险或审核服务降级、或被举报触发 P0 预处置），已存库但只有作者自己看得到，等运营判定；AUTHOR_DEACTIVATED=作者已注销，内容对他人隐藏但保留。公开口径 = status=PUBLISHED 且 deleted_at 为空';
COMMENT ON COLUMN content_posts.deleted_at IS '软删时刻（UTC）；非空=已删除（作者自行删除/运营下架/注销级联），不物理删行；为空=未删除';
COMMENT ON COLUMN content_posts.created_at IS '发布时刻（UTC），决定 Feed 流和「我的发布」的排序';
COMMENT ON COLUMN content_posts.updated_at IS '最后更新时刻（UTC）';
COMMENT ON COLUMN content_posts.event_date IS '成长日历的「事件日期」（用户自选那天发生的事）；仅 GROWTH_MOMENT 有值，决定在宠物档案时间线上的位置，与发布时间 created_at 解耦';
COMMENT ON COLUMN content_posts.moderation_risk_score IS '第三方内容审核给出的风险分，范围 0.000–1.000（越高越可能违规）。低风险直接发布时可能不落值；审核服务降级时为空';
COMMENT ON COLUMN content_posts.review_reason IS '进人工审核队列的原因：RISK_HIGH=风险分过高；DEGRADED_FAILCLOSED=第三方审核服务不可用，保守挂起（宁可错拦不放过）；REPORT_P0=被举报命中最高级阈值触发自动预处置。未挂起时为空';
COMMENT ON COLUMN content_posts.content_version IS '内容版本号，正文每改一次 +1。审核结果绑定提交时的版本，版本对不上说明内容已被改过、该审核结论作废';
COMMENT ON COLUMN content_posts.report_hidden_at IS '因举报触发自动预处置、转为「仅作者可见待判」的时刻（UTC）；同时是 2 小时处置时限的计时起点；为空=没被举报预处置过';

-- ========== comments ==========
COMMENT ON TABLE comments IS '内容评论表：帖子下的评论与回复，最多两级（评论 + 对评论的回复，不再往下嵌套）';
COMMENT ON COLUMN comments.id IS '主键，自增内部 id';
COMMENT ON COLUMN comments.post_id IS '所属帖子，关联 content_posts 表 id';
COMMENT ON COLUMN comments.parent_id IS '父评论，关联 comments 表自身 id；为空=一级评论，非空=对某条一级评论的回复（二级）';
COMMENT ON COLUMN comments.author_id IS '评论作者，关联 users 表 id';
COMMENT ON COLUMN comments.body IS '评论正文，最长 1000 字【PII】用户自由输入，可能含个人信息';
COMMENT ON COLUMN comments.deleted_at IS '软删时刻（UTC）；非空=已删除（作者删除/帖子级联/运营下架/注销级联），不物理删行';
COMMENT ON COLUMN comments.created_at IS '评论时刻（UTC）';
COMMENT ON COLUMN comments.updated_at IS '最后更新时刻（UTC）';
COMMENT ON COLUMN comments.moderation_status IS '评论对他人的可见性状态：VISIBLE=正常通过、所有人可见（默认）；UNDER_REVIEW=第三方审核服务降级导致的保守挂起，仅作者可见、不打标签，等人工判定；TAKEN_DOWN=运营巡查判违规下架，仅作者可见并显示「违规下架」标签；REJECTED=挂起后被运营拒绝或超时丢弃，仅作者可见（终态）；AUTHOR_DEACTIVATED=作者已注销，对他人隐藏但内容保留';
COMMENT ON COLUMN comments.content_version IS '评论内容版本号，正文每改一次 +1，用于作废陈旧的审核结论。当前 App 没有编辑评论功能，所以实际恒为 1（预留契约）';

-- ========== content_likes ==========
COMMENT ON TABLE content_likes IS '内容点赞关系表：一行=某用户赞了某帖子。取消点赞是直接删行（不软删）；点赞数实时 COUNT 统计，不做缓存';
COMMENT ON COLUMN content_likes.id IS '主键，自增内部 id';
COMMENT ON COLUMN content_likes.post_id IS '被点赞的帖子，关联 content_posts 表 id';
COMMENT ON COLUMN content_likes.user_id IS '点赞的用户，关联 users 表 id；(post_id, user_id) 唯一，同一人对同一帖只能赞一次';
COMMENT ON COLUMN content_likes.created_at IS '点赞时刻（UTC）';

-- ========== content_reports ==========
COMMENT ON TABLE content_reports IS '内容举报工单表：用户举报违规帖子后生成的工单，进运营人工审核队列。仅支持举报帖子（不支持举报评论/用户）；举报本身不会自动下架内容，须运营处置';
COMMENT ON COLUMN content_reports.id IS '主键，自增内部 id';
COMMENT ON COLUMN content_reports.post_id IS '被举报的帖子，关联 content_posts 表 id';
COMMENT ON COLUMN content_reports.reporter_id IS '举报人，关联 users 表 id；(post_id, reporter_id) 唯一，同一人对同一帖重复举报会被幂等吞掉只留一条';
COMMENT ON COLUMN content_reports.reason_type IS '举报理由（用户单选其一）：ILLEGAL=违法违规；MISINFO=虚假信息；INAPPROPRIATE=不当内容；HARASSMENT=骚扰；OTHER=其他';
COMMENT ON COLUMN content_reports.status IS '工单状态：PENDING=待运营处理（默认）；RESOLVED=已处理（判定违规、内容已下架）；DISMISSED=已驳回（判定不违规、内容不动）。仅运营人工流转，无自动下架';
COMMENT ON COLUMN content_reports.handled_by IS '处理该工单的运营人员 id（无外键约束）：运营账号绑定了 App 账号时存 users.id，否则存后台账号 admin_accounts.id；未处理时为空';
COMMENT ON COLUMN content_reports.handled_at IS '运营处理时刻（UTC）；未处理时为空';
COMMENT ON COLUMN content_reports.created_at IS '举报提交时刻（UTC）';
COMMENT ON COLUMN content_reports.updated_at IS '最后更新时刻（UTC）';

-- ========== seed_content_hashes ==========
COMMENT ON TABLE seed_content_hashes IS '冷启动种子内容去重表：运营用虚拟账号批量灌入社区种子内容（如 DAILY 日常分享）时，记录每条内容的指纹，防止跨批次重复发布同一条内容';
COMMENT ON COLUMN seed_content_hashes.content_hash IS '主键，内容指纹：对「内容类型 + 正文 + 排序后的图片列表」做 sha256 得到的 64 位十六进制字符串。已存在=这条内容之前发过，本次跳过';
COMMENT ON COLUMN seed_content_hashes.post_id IS '该指纹首次发布生成的帖子，关联 content_posts 表 id';
COMMENT ON COLUMN seed_content_hashes.author_id IS '发布该种子内容的虚拟账号，关联 users 表 id';
COMMENT ON COLUMN seed_content_hashes.created_at IS '首次发布并登记指纹的时刻（UTC）';

-- ========== name_moderation_records ==========
COMMENT ON TABLE name_moderation_records IS '名称审核记录表：用户昵称、宠物名字提交后的异步审核流水。名称有自己独立的审核状态机和人工队列（不走帖子的 manual_review_queue）；判定违规会把名称重置成系统默认编码名并推送通知';
COMMENT ON COLUMN name_moderation_records.id IS '主键，自增内部 id';
COMMENT ON COLUMN name_moderation_records.target_type IS '审核对象类型：NICKNAME=用户昵称；PET_NAME=宠物名字';
COMMENT ON COLUMN name_moderation_records.target_ref_id IS '审核对象的内部 id（无外键约束）：昵称时关联 users 表 id，宠物名时关联 pet_profiles 表 id。仅内部使用，绝不对外暴露';
COMMENT ON COLUMN name_moderation_records.revision IS '该对象的审核版本号，用户每改一次名 +1。用于作废旧提交：有更高版本提交时，旧记录转 SUPERSEDED';
COMMENT ON COLUMN name_moderation_records.submitted_value IS '本次送审的名称原文（审核证据）【PII】仅存本列，严禁写入任何业务日志';
COMMENT ON COLUMN name_moderation_records.status IS '审核状态：SCORING=已建记录、正在调第三方评分；AUTO_PASSED=低风险自动通过（终态、静默不推送）；MANUAL_PENDING=进人工队列等运营处置（中高风险，或第三方降级保守入队）；RESOLVED_PASS=运营判通过（终态、静默）；RESOLVED_VIOLATION=运营判违规，名称已被重置为默认编码名并推送通知（终态）；SUPERSEDED=被更新的提交取代、作废丢弃（终态）；FAILED_TO_QUEUE=入队失败兜底，供告警排障用（正常不产生）。名称侧没有「评分直接自动判违规」的路径，违不违规一律由运营裁定';
COMMENT ON COLUMN name_moderation_records.priority IS '人工队列优先级：HIGH=风险分 ≥0.8 或命中平台强制黑名单；NORMAL=其余（含第三方降级入队），默认 NORMAL';
COMMENT ON COLUMN name_moderation_records.risk_score IS '第三方审核给出的风险分，0.000–1.000（越高越可能违规）；第三方降级或未评分时为空';
COMMENT ON COLUMN name_moderation_records.decided_by IS '人工处置该记录的运营人员，关联 admin_accounts 表 id；自动通过或未处置时为空';
COMMENT ON COLUMN name_moderation_records.decided_at IS '运营处置时刻（UTC）；未人工处置时为空';
COMMENT ON COLUMN name_moderation_records.decision_reason IS '运营判定的违规类别（仅内部记录，不透给用户）；未处置时为空';
COMMENT ON COLUMN name_moderation_records.retry_count IS '异步调用第三方审核的重试次数；重试耗尽会保守入人工队列';
COMMENT ON COLUMN name_moderation_records.submitted_at IS '名称提交送审的时刻（UTC），人工队列按此升序排队';
COMMENT ON COLUMN name_moderation_records.created_at IS '记录创建时刻（UTC）';
COMMENT ON COLUMN name_moderation_records.updated_at IS '最后更新时刻（UTC）';

-- ========== moderation_keyword_rules ==========
COMMENT ON TABLE moderation_keyword_rules IS '内容审核分层词库规则表：审核用的关键词/正则规则，分黑名单、可调中风险词、宠物场景白名单三层，运营可增删改';
COMMENT ON COLUMN moderation_keyword_rules.id IS '主键，自增内部 id';
COMMENT ON COLUMN moderation_keyword_rules.rule_kind IS '规则层级：L1_BLOCK=平台强制黑名单，命中即硬拦截；L2_ADJUSTABLE=运营可调的中风险词，命中只作风险评分加权项、不硬拦截；L3_WHITELIST=宠物场景白名单，优先级最高，命中即豁免硬拦截（例如印尼语 anjing=狗、gendut=胖，是正常宠物用词）';
COMMENT ON COLUMN moderation_keyword_rules.match_type IS '匹配方式：SUBSTRING=包含该词即命中（默认）；EXACT=完全相等才命中；REGEX=按正则匹配。大小写不敏感';
COMMENT ON COLUMN moderation_keyword_rules.pattern IS '要匹配的词或正则表达式';
COMMENT ON COLUMN moderation_keyword_rules.category IS '规则分类标签，如 DRUGS=毒品、GAMBLING=赌博、PORN=色情、POLITICS=政治、AD_SPAM=广告引流、HARASSMENT=骚扰、WEAPON=武器、PET_SAFE=宠物安全词（白名单用）等，可按需扩充';
COMMENT ON COLUMN moderation_keyword_rules.lang IS '规则适用语言：id=印尼语、en=英语、zh=中文、ALL=所有语言通用（默认 ALL）';
COMMENT ON COLUMN moderation_keyword_rules.enabled IS '规则是否启用：true=生效（默认），false=停用不参与匹配';
COMMENT ON COLUMN moderation_keyword_rules.note IS '运营备注，说明这条规则是什么意思/为什么加，仅内部查看';
COMMENT ON COLUMN moderation_keyword_rules.created_at IS '规则创建时刻（UTC）';
COMMENT ON COLUMN moderation_keyword_rules.updated_at IS '规则最后更新时刻（UTC）';

-- ========== manual_review_queue ==========
COMMENT ON TABLE manual_review_queue IS '内容人工审核队列表：没通过自动审核的帖子/评论在此挂起，等运营通过或拒绝；超 3 天未处置自动丢弃';
COMMENT ON COLUMN manual_review_queue.id IS '主键，自增内部 id（内部工具，不对外暴露）';
COMMENT ON COLUMN manual_review_queue.content_id IS '待审内容的 id（无外键约束）：content_type=CONTENT_POST 时关联 content_posts 表 id，=COMMENT 时关联 comments 表 id';
COMMENT ON COLUMN manual_review_queue.submitted_at IS '内容入队待审的时刻（UTC），队列按此升序排；也是超时丢弃的计时起点';
COMMENT ON COLUMN manual_review_queue.status IS '队列项状态：PENDING=挂起待运营处置（默认）；APPROVED=运营通过（内容转为正常发布）；REJECTED=运营拒绝（内容丢弃）；TIMED_OUT=超 3 天未处置，系统自动丢弃。只有 PENDING 能被处置，终态不可再变';
COMMENT ON COLUMN manual_review_queue.decided_by IS '处置该队列项的运营人员，关联 admin_accounts 表 id；PENDING 或系统超时丢弃时为空';
COMMENT ON COLUMN manual_review_queue.decided_at IS '处置时刻（UTC）；未处置时为空';
COMMENT ON COLUMN manual_review_queue.created_at IS '队列项创建时刻（UTC）';
COMMENT ON COLUMN manual_review_queue.updated_at IS '最后更新时刻（UTC）';
COMMENT ON COLUMN manual_review_queue.content_type IS '待审内容的种类：CONTENT_POST=帖子（默认，历史数据全是帖子）；COMMENT=评论';
COMMENT ON COLUMN manual_review_queue.content_version IS '入队时抓取的内容版本号，出审核结果时与内容当前版本比对；对不上说明内容已被改过，该审核结论作废。可为空';
COMMENT ON COLUMN manual_review_queue.priority IS '处置优先级：P0=最高（最紧急，如举报密集触发）；P1=高（默认，历史项与降级入队项归此级，避免沉底）；P2=普通。队列按 P0→P1→P2、再按入队时间排序';

-- ========== red_overage_reviews ==========
COMMENT ON TABLE red_overage_reviews IS '红色分诊超额人工复核标记表：某用户短期内产生异常多的「红色」（紧急就医）分诊结论时，运营在此做人工复核注记。一个用户一行。⚠️ 纯观测与注记用途——绝不会自动拦截、限流或封禁用户；红色数量本身实时从 triage_tasks 聚合，不存这张表';
COMMENT ON COLUMN red_overage_reviews.user_id IS '主键，被复核的用户，关联 users 表 id（一个用户只有一行复核记录）';
COMMENT ON COLUMN red_overage_reviews.status IS '复核状态：TO_VERIFY=待核查；RESOLVED=已处理完毕';
COMMENT ON COLUMN red_overage_reviews.note IS '运营复核备注（最长 255 字），仅内部查看';
COMMENT ON COLUMN red_overage_reviews.reviewed_by IS '做本次复核的运营人员，关联 admin_accounts 表 id';
COMMENT ON COLUMN red_overage_reviews.reviewed_at IS '最近一次复核操作的时刻（UTC）';
COMMENT ON COLUMN red_overage_reviews.updated_at IS '最后更新时刻（UTC）';

-- ========================================================================
-- 管理后台 / 客服工单 相关表字段中文注释
-- 覆盖：admin_accounts, admin_account_permissions, admin_audit_logs,
--       admin_settings, config_change_logs, feedback_tickets,
--       ticket_internal_notes, ticket_labels, ticket_attachments
-- 所有时间戳列一律为 UTC 时间（timestamptz）。
-- ========================================================================


-- ========== admin_accounts ==========
COMMENT ON TABLE admin_accounts IS '运营管理后台的账号表。与 App 用户表（users）、兽医账号表（vet_accounts）完全隔离，后台人员登录走飞书（Lark）OAuth，App 用户无法登录后台。';
COMMENT ON COLUMN admin_accounts.id IS '主键，后台账号的内部编号';
COMMENT ON COLUMN admin_accounts.lark_email IS '【PII】该后台人员的飞书（Lark）邮箱，既是登录身份标识，也是 Lark 登录白名单的匹配键。全表唯一';
COMMENT ON COLUMN admin_accounts.display_name IS '【PII】后台显示的人员姓名';
COMMENT ON COLUMN admin_accounts.account_type IS '账号类型：SUPER_ADMIN=超级管理员（隐式拥有全部权限，不需要单独授权，全平台上限 5 个）；STAFF=普通运营账号（只有被授权的权限码，见 admin_account_permissions）';
COMMENT ON COLUMN admin_accounts.status IS '账号状态：ACTIVE=正常可登录；DISABLED=已停用，不能登录后台';
COMMENT ON COLUMN admin_accounts.password_hash IS '【PII】超级管理员紧急账密入口的密码哈希（BCrypt，明文绝不落库）。仅系统初始化预置的超管有值；走 Lark 登录的账号为空';
COMMENT ON COLUMN admin_accounts.created_by IS '创建这个账号的超管账号编号（关联 admin_accounts.id）。系统初始化预置的首个超管为空';
COMMENT ON COLUMN admin_accounts.created_at IS '账号创建时间（UTC）';
COMMENT ON COLUMN admin_accounts.updated_at IS '账号最近一次更新时间（UTC）';


-- ========== admin_account_permissions ==========
COMMENT ON TABLE admin_account_permissions IS '后台账号的模块权限授权表。一行 = 给某个 STAFF 账号开通一个权限。超级管理员隐式全权、不会出现在本表；账号被删除时其权限行级联删除。';
COMMENT ON COLUMN admin_account_permissions.account_id IS '被授权的后台账号编号（关联 admin_accounts.id）。与 permission_code 共同构成主键';
COMMENT ON COLUMN admin_account_permissions.permission_code IS '权限码，格式为「模块.动作」全小写点分。全集：vet.view=查看兽医 / vet.create=开通兽医账号 / vet.ban=封禁兽医 / vet.reset_password=重置兽医密码 / vet.qualify=兽医资质管理 / user.view=查看用户 / user.deactivate=停用用户 / user.delete=删除用户（级联删除或匿名化，不可逆） / content.view_reports=查看举报队列 / content.takedown=下架内容或驳回举报 / content.restore=恢复已下架内容 / content.proactive_takedown=主动下架 / content.export=内容导出 / content.view_reporters=查看举报者清单 / consult.view_anomalies=查看问诊异常 / consult.handle=处理问诊异常 / consult.view_sessions=查询问诊会话 / rating.view=查看评分 / support.handle=处理与结案客服工单 / refund.submit=提交退款需求判定（客服） / refund.approve=审批退款申请（主管） / refund.payout=执行退款打款（财务） / order.view=查看咨询订单 / order.export=订单收入统计导出 / virtual_account.manage=虚拟账号与种子管理 / config.view=查看运营配置 / config.edit=编辑运营配置（定价 / PawCoin / 阈值） / settlement.view=查看兽医月结对账 / settlement.payout=月结确认打款归档 / payment.view=支付记录查询 / risk.view=红色超额风险监控与人工标记 / admin.create_account=创建后台账号与改权限（高危） / admin.deactivate=停用启用后台账号（高危） / admin.view_logs=查看审计日志（高危）';


-- ========== admin_audit_logs ==========
COMMENT ON TABLE admin_audit_logs IS '后台操作审计日志。所有后台写操作都必须在此留痕。本表为「只写不改」（append-only）防篡改设计：行与行之间用 SHA-256 哈希串成链，数据库触发器硬性拒绝任何 UPDATE / DELETE，日志永久保留、无清理任务。';
COMMENT ON COLUMN admin_audit_logs.id IS '主键，审计记录编号（同时决定哈希链的先后顺序）';
COMMENT ON COLUMN admin_audit_logs.actor_account_id IS '操作人的后台账号编号（关联 admin_accounts.id，注意不是 App 用户表 users）。系统自动发起的操作为空';
COMMENT ON COLUMN admin_audit_logs.action_type IS '操作类型代号，大写下划线的过去式动词短语，如 EMERGENCY_LOGIN_SUCCEEDED（紧急账密登录成功）。取值随后台功能持续扩充，不是固定枚举，具体清单以代码调用处为准';
COMMENT ON COLUMN admin_audit_logs.target_type IS '本次操作针对的对象类型，如 ADMIN_ACCOUNT（后台账号）、CONTENT_POST（内容帖子）等。无明确对象时为空';
COMMENT ON COLUMN admin_audit_logs.target_id IS '本次操作针对的对象标识，存的是不可枚举 token 或业务 id 字符串（不直接外露自增主键）。无明确对象时为空';
COMMENT ON COLUMN admin_audit_logs.summary IS '这次操作的人类可读摘要，给人看的一句话说明。严禁写入密码、令牌、签名链接、健康数据';
COMMENT ON COLUMN admin_audit_logs.created_at IS '操作发生时间（UTC，由应用写入，精度截断到微秒）。此值参与哈希计算，改动会导致哈希链校验失败';
COMMENT ON COLUMN admin_audit_logs.prev_hash IS '防篡改哈希链字段：上一条审计记录的 row_hash（链上第一条为 64 个 0 的创世值）。前后串联成链，任何一行被改动或删除都能被检测出来。**不可修改**';
COMMENT ON COLUMN admin_audit_logs.row_hash IS '防篡改哈希链字段：本行内容的 SHA-256 摘要（64 位十六进制），由本行各字段值加上 prev_hash 计算得出，全表唯一。用于独立复算校验日志是否被篡改。**不可修改**';


-- ========== admin_settings ==========
COMMENT ON TABLE admin_settings IS '后台系统级开关配置表。全表固定只有一行（主键恒为 1），由超级管理员切换。';
COMMENT ON COLUMN admin_settings.id IS '主键，固定为 1（数据库约束保证本表只有一行）';
COMMENT ON COLUMN admin_settings.manual_review_enabled IS '内容人工复审开关：true=自动审核拦截的内容进入人工复审队列，由运营人工裁定；false=维持默认行为，自动审核拦截即直接发布失败（默认 false）';
COMMENT ON COLUMN admin_settings.created_at IS '配置行创建时间（UTC）';
COMMENT ON COLUMN admin_settings.updated_at IS '配置最近一次修改时间（UTC）';


-- ========== config_change_logs ==========
COMMENT ON TABLE config_change_logs IS '运营配置变更流水（只增不改）。定价、PawCoin、充值档位等运行时配置每改动一个字段就记一条「改前→改后」，便于回溯谁在什么时候把价格改成了多少。另有一份记录会进审计哈希链（admin_audit_logs）。';
COMMENT ON COLUMN config_change_logs.id IS '主键，变更流水编号';
COMMENT ON COLUMN config_change_logs.config_type IS '被改的配置类别：PRICING=定价配置（兽医咨询价、兽医分成比例、AI 详情解锁价、身份证高清图下载价、每月免费解锁次数）；PAWCOIN=PawCoin 配置（退款转币溢价率、充值暂停开关）；TOPUP_TIER=充值档位配置';
COMMENT ON COLUMN config_change_logs.field IS '被改的具体字段名（充值档位类的变更这里存的是档位标识）';
COMMENT ON COLUMN config_change_logs.old_value IS '改动前的值（文本形式）。新增配置时为空';
COMMENT ON COLUMN config_change_logs.new_value IS '改动后的值（文本形式）。删除配置时为空';
COMMENT ON COLUMN config_change_logs.changed_by IS '执行改动的后台账号编号（关联 admin_accounts.id）';
COMMENT ON COLUMN config_change_logs.changed_at IS '改动发生时间（UTC）';


-- ========== feedback_tickets ==========
COMMENT ON TABLE feedback_tickets IS '客服工单主表。用户在 App 里提交投诉/反馈生成工单（OPEN），客服在后台流转处理直至结案，结案后向用户发满意度问卷（CSAT）。';
COMMENT ON COLUMN feedback_tickets.id IS '主键，工单内部编号';
COMMENT ON COLUMN feedback_tickets.ticket_token IS '工单对外标识，随机不可枚举的字符串（对外接口只用它，不外露自增编号）。全表唯一';
COMMENT ON COLUMN feedback_tickets.user_id IS '提交工单的 App 用户编号（关联 users 表）';
COMMENT ON COLUMN feedback_tickets.subject IS '工单主题，用户可不填';
COMMENT ON COLUMN feedback_tickets.body IS '工单正文，用户描述的问题内容（必填）';
COMMENT ON COLUMN feedback_tickets.contact_type IS '用户自填的联系方式渠道：EMAIL=邮箱；WHATSAPP=WhatsApp';
COMMENT ON COLUMN feedback_tickets.contact_value IS '【PII】用户自填的联系方式具体值（邮箱地址或 WhatsApp 号码）。绝不可记入日志';
COMMENT ON COLUMN feedback_tickets.need_contact_customer IS '用户是否希望客服联系他本人：true=需要联系（默认）；false=不需要，仅反馈';
COMMENT ON COLUMN feedback_tickets.contacted_customer IS '客服是否已联系过该用户：true=已联系（客服结案时置真）；false=尚未联系（默认）';
COMMENT ON COLUMN feedback_tickets.related_order_id IS '本工单关联的问诊订单编号（关联 consult_orders 表），退款类工单会用到。用户未关联订单时为空';
COMMENT ON COLUMN feedback_tickets.status IS '工单状态：OPEN=用户刚提交待处理；IN_PROGRESS=客服已接手处理中；RESOLVED=客服已解决（此时向用户发满意度问卷）；CLOSED=已闭环（用户提交了评价，或 7 天未评价系统自动关闭）';
COMMENT ON COLUMN feedback_tickets.csat_score IS '用户对本次客服服务的满意度评分，1-5 分。用户未评价时为空';
COMMENT ON COLUMN feedback_tickets.csat_comment IS '用户提交满意度评分时附带的文字评论（最多 100 字）。未填时为空';
COMMENT ON COLUMN feedback_tickets.csat_deadline IS '满意度问卷的截止时间（UTC，客服结案时间 +7 天）。超过此时间用户仍未评价，系统会静默把工单自动关闭';
COMMENT ON COLUMN feedback_tickets.cs_rating IS '【待确认】预留字段，暂未启用（建列即为空，规划中标注为后续版本使用）。推测为平台内部给客服人员本次处理质量打的分，与用户打的 csat_score 相区别';
COMMENT ON COLUMN feedback_tickets.handled_by IS '处理并结案本工单的后台客服账号编号（关联 admin_accounts.id）。尚未有人处理时为空';
COMMENT ON COLUMN feedback_tickets.resolved_at IS '客服标记「已解决」的时间（UTC）。尚未解决时为空';
COMMENT ON COLUMN feedback_tickets.created_at IS '工单提交时间（UTC）';
COMMENT ON COLUMN feedback_tickets.updated_at IS '工单最近一次更新时间（UTC）';


-- ========== ticket_internal_notes ==========
COMMENT ON TABLE ticket_internal_notes IS '客服工单的内部备注。**用户绝对不可见**（隐私契约红线，用户端接口不返回本表任何内容），仅后台客服视图可见。';
COMMENT ON COLUMN ticket_internal_notes.id IS '主键，备注编号';
COMMENT ON COLUMN ticket_internal_notes.ticket_id IS '所属工单编号（关联 feedback_tickets.id）';
COMMENT ON COLUMN ticket_internal_notes.admin_id IS '写这条备注的后台账号编号（关联 admin_accounts.id）';
COMMENT ON COLUMN ticket_internal_notes.note IS '备注内容（仅内部可见，写入后不可修改）';
COMMENT ON COLUMN ticket_internal_notes.created_at IS '备注写入时间（UTC）';


-- ========== ticket_labels ==========
COMMENT ON TABLE ticket_labels IS '客服工单的分类标签。一个工单可打多个标签，同一工单同一标签不会重复（数据库唯一约束保证）。';
COMMENT ON COLUMN ticket_labels.id IS '主键，标签行编号';
COMMENT ON COLUMN ticket_labels.ticket_id IS '所属工单编号（关联 feedback_tickets.id）';
COMMENT ON COLUMN ticket_labels.label IS '标签值：BUG=故障；FEATURE=功能建议；CONSULT_COMPLAINT=咨询投诉；REFUND=退款；CONTENT=内容问题；ACCOUNT=账号问题；PRAISE=表扬；OTHER=其他';
COMMENT ON COLUMN ticket_labels.created_at IS '标签打上的时间（UTC）';


-- ========== ticket_attachments ==========
COMMENT ON TABLE ticket_attachments IS '客服工单的图片附件（每单最多 5 张）。这里只保存文件在对象存储里的路径，不保存可直接打开的链接，展示时由后端临时现签一个带时效的链接。';
COMMENT ON COLUMN ticket_attachments.id IS '主键，附件编号';
COMMENT ON COLUMN ticket_attachments.ticket_id IS '所属工单编号（关联 feedback_tickets.id）';
COMMENT ON COLUMN ticket_attachments.object_key IS '附件在对象存储（OSS）中的文件路径（objectKey），**不是**可直接访问的签名链接';
COMMENT ON COLUMN ticket_attachments.created_at IS '附件记录创建时间（UTC）';

-- ========== vet_accounts ==========
COMMENT ON TABLE vet_accounts IS '兽医账号表：平台在线问诊兽医的登录账号主体，由运营在管理后台开户（不开放自助注册）。与用户账号体系完全隔离（用户走 Google/Apple 登录，兽医走账号密码登录）。';
COMMENT ON COLUMN vet_accounts.id IS '主键，兽医账号 ID（自增）';
COMMENT ON COLUMN vet_accounts.username IS '兽医登录账号（全表唯一，实际使用邮箱），运营开户时分配、可在后台编辑';
COMMENT ON COLUMN vet_accounts.password_hash IS '登录密码的 BCrypt 加密串，明文密码绝不落库。无「忘记密码」功能，忘记密码只能由运营在后台重置（重置后旧登录凭证立即失效）';
COMMENT ON COLUMN vet_accounts.display_name IS '兽医昵称/对外显示名，用户在问诊对话和问诊历史里看到的就是这个名字';
COMMENT ON COLUMN vet_accounts.status IS '账号状态：ACTIVE=正常，可登录、可接单；BANNED=被运营封禁，不能登录，且封禁瞬间会中断其进行中的问诊会话。默认 ACTIVE';
COMMENT ON COLUMN vet_accounts.created_at IS '账号创建时间（UTC 时区）';
COMMENT ON COLUMN vet_accounts.updated_at IS '账号最近一次修改时间（UTC 时区）';
COMMENT ON COLUMN vet_accounts.contact_phone IS '【PII】运营私下联系兽医用的手机号，仅后台可见。不是登录凭证、不参与登录校验、不写入登录令牌和日志，可为空';
COMMENT ON COLUMN vet_accounts.avatar_url IS '兽医头像图片的公开访问地址（CDN URL），由运营在管理后台上传到公开存储桶。为空时 App 端回退显示昵称首字母圆形占位图';

-- ========== vet_qualifications ==========
COMMENT ON TABLE vet_qualifications IS '兽医资质表：记录每位兽医的身份证件与执业资质材料及审核状态，与 vet_accounts 一一对应。是「能否接单」的门控依据——只有资质为「已认证」或「即将到期」的兽医才能接问诊单。';
COMMENT ON COLUMN vet_qualifications.id IS '主键，资质记录 ID（自增）';
COMMENT ON COLUMN vet_qualifications.vet_account_id IS '关联的兽医账号 ID（外键 → vet_accounts.id），一个兽医只有一条资质记录（唯一）';
COMMENT ON COLUMN vet_qualifications.ktp_no IS '【PII】兽医的印尼身份证（KTP）号码，敏感个人信息，仅后台审核可见';
COMMENT ON COLUMN vet_qualifications.ktp_photo_key IS '【PII】兽医身份证（KTP）照片在私密存储桶中的对象路径（不是可直接打开的链接，需后台临时签名才能查看）';
COMMENT ON COLUMN vet_qualifications.sipdh_no IS '【PII】SIPDH 兽医执业许可证编号（印尼兽医执业的法定许可，是能否接单的核心资质）';
COMMENT ON COLUMN vet_qualifications.sipdh_issuer IS 'SIPDH 执业许可证的签发机构名称';
COMMENT ON COLUMN vet_qualifications.sipdh_expiry IS 'SIPDH 执业许可证有效期截止日（按日判定）。到期前 30 天系统自动标记「即将到期」预警（仍可接单）；过期当日自动标记「已过期」并停止接单';
COMMENT ON COLUMN vet_qualifications.sipdh_photo_key IS '【PII】SIPDH 执业许可证照片在私密存储桶中的对象路径（需临时签名才能查看）';
COMMENT ON COLUMN vet_qualifications.degree_photo_key IS '【PII】兽医学历/学位证书照片在私密存储桶中的对象路径（需临时签名才能查看）';
COMMENT ON COLUMN vet_qualifications.profile_photo_key IS '【PII】兽医本人证件形象照在私密存储桶中的对象路径（资质留档用，与对外展示的 vet_accounts.avatar_url 头像不是一回事）';
COMMENT ON COLUMN vet_qualifications.pdhi_photo_key IS '【PII】PDHI（印尼兽医协会）会员证照片在私密存储桶中的对象路径（需临时签名才能查看）';
COMMENT ON COLUMN vet_qualifications.specialties IS '兽医擅长领域标签列表（JSON 字符串数组），可为空';
COMMENT ON COLUMN vet_qualifications.status IS '资质审核状态：PENDING_COMPLETION=待完善（默认，材料未齐）；UNDER_REVIEW=审核中（已提交待运营审核）；CERTIFIED=已认证（执业许可合法，可接单）；REJECTED=已驳回（见 reject_reason）；EXPIRING_SOON=执业证 30 天内到期（仅预警，仍可接单）；EXPIRED=执业证已过期（不可接单）。仅 CERTIFIED / EXPIRING_SOON 两态允许接单';
COMMENT ON COLUMN vet_qualifications.reject_reason IS '资质审核驳回原因（运营驳回时必填），重新认证通过后清空';
COMMENT ON COLUMN vet_qualifications.created_at IS '资质记录创建时间（UTC 时区）';
COMMENT ON COLUMN vet_qualifications.updated_at IS '资质记录最近一次修改时间（UTC 时区）';
COMMENT ON COLUMN vet_qualifications.strv_no IS '【PII】STRV 兽医注册证编号（独立于 SIPDH 执业许可，属可选留档材料，不影响接单）';
COMMENT ON COLUMN vet_qualifications.strv_issuer IS 'STRV 兽医注册证的签发机构名称';
COMMENT ON COLUMN vet_qualifications.strv_expiry IS 'STRV 兽医注册证有效期截止日。仅作留档展示，系统不对其做到期扫描、不阻断接单';
COMMENT ON COLUMN vet_qualifications.strv_photo_key IS '【PII】STRV 兽医注册证照片在私密存储桶中的对象路径（需临时签名才能查看）';

-- ========== notifications ==========
COMMENT ON TABLE notifications IS '用户通知记录表：所有发给用户的站内通知/推送每发一条写一行，是 App「通知中心」列表的读取来源。发给兽医的推送不写本表（兽医端 V1 无通知中心，只走离线推送）。通知标题/正文绝不含健康数据明文。';
COMMENT ON COLUMN notifications.id IS '主键，通知 ID（自增）';
COMMENT ON COLUMN notifications.recipient_user_id IS '收件人用户 ID（关联 users.id），即这条通知发给谁';
COMMENT ON COLUMN notifications.type IS '通知类型：VET_REPLY=兽医回复了问诊；CONSULT_CLOSED=问诊结束（引导评分）；CONTENT_LIKED=内容被点赞；CONTENT_COMMENTED=内容被评论；NEW_CONSULT_REQUEST=兽医端收到新问诊请求；PET_BIRTHDAY=宠物生日提醒；COMPANION_ANNIVERSARY=陪伴纪念日；MILESTONE_NODE=成长里程碑节点；CONTENT_REMOVED=内容因违规被下架（通知作者，无跳转、无申诉入口）；REPORT_REVIEWED=举报已处理（对举报人的模糊闭环，不透露处置结果）；CONTENT_REVIEW_APPROVED=人工审核通过；CONTENT_REVIEW_REJECTED=人工审核未通过/超时丢弃；REFUND_REJECTED=退款申请未通过（不含金额/账号等敏感信息）；TICKET_RESOLVED=客服工单已结案；CSAT_SURVEY=满意度问卷邀请；IDENTITY_REQUIRE_MODIFY=身份信息需修改。其中 TICKET_RESOLVED / CSAT_SURVEY / IDENTITY_REQUIRE_MODIFY 目前为预留类型，尚未实际发出';
COMMENT ON COLUMN notifications.title IS '通知标题（App 通知中心与系统推送横幅上显示的标题），绝不含健康数据明文';
COMMENT ON COLUMN notifications.body IS '通知正文摘要，绝不含健康数据明文';
COMMENT ON COLUMN notifications.deep_link_type IS '点击通知后要跳转的目标页面类型（客户端据此映射到具体页面路由），通常与 type 同名，可为空表示无跳转';
COMMENT ON COLUMN notifications.deep_link_token IS '对外暴露的随机不可猜测标识串，用于把这条通知标记为已读（不使用可被顺序猜测的自增 ID）';
COMMENT ON COLUMN notifications.target_ref IS '跳转目标资源的内部标识（如宠物档案 ID、问诊 ID 等），仅服务端内部回查用，客户端跳转依据此字段而非随机 token，不对外原样泄露';
COMMENT ON COLUMN notifications.is_read IS '是否已读：true=用户已读，false=未读（未读条数会体现为 App 首页红点角标）。默认 false';
COMMENT ON COLUMN notifications.created_at IS '通知产生时间（UTC 时区），通知中心按此倒序排列';
COMMENT ON COLUMN notifications.read_at IS '用户标记已读的时间（UTC 时区），未读时为空';

-- ========== scheduled_push_marks ==========
COMMENT ON TABLE scheduled_push_marks IS '定时推送去重标记表：记录「某个宠物档案的某个节点已经推过了」，防止生日/纪念日/里程碑这类每日扫描的定时推送重复打扰用户。每天扫描后逐条投递前先查此表，(宠物档案, 推送种类, 节点标识) 三者唯一，是「同一节点只推一次」的唯一判定依据。';
COMMENT ON COLUMN scheduled_push_marks.id IS '主键，去重标记 ID（自增）';
COMMENT ON COLUMN scheduled_push_marks.pet_profile_id IS '关联的宠物档案 ID（外键概念上指向 pet_profiles.id），即这条推送是围绕哪只宠物发的';
COMMENT ON COLUMN scheduled_push_marks.push_kind IS '定时推送种类：PET_BIRTHDAY=宠物生日提醒；COMPANION_ANNIVERSARY=陪伴纪念日；MILESTONE_NODE=成长里程碑节点';
COMMENT ON COLUMN scheduled_push_marks.node_key IS '节点标识，含义随 push_kind 变化：生日=年份（如 2026，按年去重）；陪伴纪念日=陪伴天数节点（30/100/365）；里程碑=里程碑节点 ID（如 FIRST_BIRTHDAY）';
COMMENT ON COLUMN scheduled_push_marks.pushed_at IS '该节点推送投递时间（UTC 时区）';

-- ========== triage_tasks ==========
COMMENT ON TABLE triage_tasks IS 'AI 智能分诊任务表：用户提交宠物症状描述和照片后，由 AI（Gemini）异步给出危险分级和建议，本表是这条异步流程的状态机与结果存档。含健康数据，日志中严禁明文输出。';
COMMENT ON COLUMN triage_tasks.id IS '主键，分诊任务 ID（自增）';
COMMENT ON COLUMN triage_tasks.user_id IS '发起分诊的用户 ID（关联 users.id）';
COMMENT ON COLUMN triage_tasks.pet_id IS '关联的宠物档案 ID（关联 pet_profiles.id），用于把分诊结果存进该宠物的档案，可为空（未绑定宠物时）';
COMMENT ON COLUMN triage_tasks.status IS '任务处理状态：PENDING=已受理待处理；PROCESSING=处理中（正在调用 AI）；DONE=已完成、结果就绪；FAILED=重试超过 3 次仍失败（前端显示降级提示）';
COMMENT ON COLUMN triage_tasks.danger_level IS '分诊危险分级（最终结果）：GREEN=绿色，风险低，居家观察即可；YELLOW=黄色，建议尽快就医；RED=红色，紧急、需立即就医。⚠️ 此值是 AI 结果经平台安全规则层「只升不降」复核后的最终值，不是 AI 的原始裁决——安全规则层只会把级别往更危险方向抬，绝不下调。风控后果：红色态零变现——红色分诊的详细建议一律免费直接放行，绝不因为未付费/未解锁而遮挡。任务未完成时为空';
COMMENT ON COLUMN triage_tasks.symptom_text IS '【PII】用户填写的宠物症状描述原文，属健康数据，严禁在日志中明文出现';
COMMENT ON COLUMN triage_tasks.image_object_keys IS '【PII】用户上传的症状照片（最多 3 张）在私密存储桶中的对象路径列表（JSON 数组）。只存对象路径，绝不存可直接打开的签名链接';
COMMENT ON COLUMN triage_tasks.gemini_raw IS '【PII】AI（Gemini）返回的原始响应 JSON，用于存档与审计追溯，含健康数据';
COMMENT ON COLUMN triage_tasks.parsed_result IS '【PII】解析整理后的分诊结果 JSON：危险分级 + 观察建议 + 用药参考 + 免责声明，是 App 分诊结果页的展示内容，含健康数据';
COMMENT ON COLUMN triage_tasks.retry_count IS 'AI 调用失败后的重试次数，累计超过 3 次即置为 FAILED。默认 0';
COMMENT ON COLUMN triage_tasks.idempotency_key IS '幂等键（全表唯一）：同一次提交重复请求时用它识别并复用已有任务，避免重复建单，可为空';
COMMENT ON COLUMN triage_tasks.created_at IS '任务创建（用户提交）时间（UTC 时区）';
COMMENT ON COLUMN triage_tasks.updated_at IS '任务最近一次状态变更时间（UTC 时区）';
COMMENT ON COLUMN triage_tasks.response_locale IS 'AI 回复使用的语言，提交时按客户端语言归一为 id（印尼语）或 en（英语），默认 en。绝不用中文回复';
COMMENT ON COLUMN triage_tasks.unlock_source IS '详细建议（用药参考等付费内容）的解锁来源：LOCKED=默认锁定待解锁（分诊成功生成时的初始值）；FREE_QUOTA=用当月免费额度解锁；PAID=付费解锁。为空表示任务尚未成功出结果（待处理/失败任务无可解锁内容）。一经写成 FREE_QUOTA/PAID 便不可再改。注意：红色分诊的详细建议永远免费放行，与本字段无关';
COMMENT ON COLUMN triage_tasks.unlock_channel IS '付费解锁的支付渠道：QRIS=印尼二维码现金支付；PAWCOIN=站内 PawCoin 余额抵扣。仅当 unlock_source=PAID 时有值，其余情况恒为空（DANA 渠道已于 2026-07 取消）';

-- ========== schema_meta ==========
COMMENT ON TABLE schema_meta IS '数据库基线元数据表：项目自建的最小占位表（非 Flyway 自带表），建于第一版基线迁移，用于验证数据库迁移链路生效。仅存 baseline=1.1 一行，不承载任何业务数据，运营无需关注。';
COMMENT ON COLUMN schema_meta.key IS '元数据键名（主键），当前仅有 baseline 一条';
COMMENT ON COLUMN schema_meta.value IS '元数据取值，baseline 对应的值为基线版本号 1.1';

