-- 种子内容物种归属（V1.1.6 Story 14.1 · AB-3H）
--
-- 🔴 **要解决的缺口**：算法要 join 作者的宠物档案来推导内容物种，但**虚拟账号创建时
--    只填昵称+头像、不建宠物档案** ⇒ **全部种子内容的物种推导结果都是空**；
--    而 Tips/科普类主要由虚拟号发布 ⇒ 算法的「教育类」槽位几乎完全无法做物种个性化。
--
-- 🛡 用两处**最低成本**的改动补它 —— 不逐条打标、不引入 AI 识别。

-- 触点①：虚拟账号的「账号物种定位」。
--
-- 🔴 **存量不回填**（AC2）：NULL 在读时按 GENERAL 解释。
--    "统一置为 GENERAL"这件事因此不需要任何数据作业 —— 而一条 UPDATE 语句的代价
--    不只是跑一次，它还让"这个号到底配过没有"永远分不出来（配成 GENERAL 与从未配过同形）。
alter table users add column account_species varchar(20);

comment on column users.account_species is
    '虚拟账号的账号物种定位（CAT/DOG/OTHER/GENERAL）。仅对 account_type=VIRTUAL 有意义；NULL 在读时按 GENERAL 解释（存量零回填）。真实账号恒 NULL——它们的物种走作者宠物档案推导。';

-- 触点④：行级物种覆写。
--
-- 🛡 **稀疏列、无需回填**：真实用户内容恒为空（走 join 推导），
--    只有运营在内容列表上手工改过的那几条才有值。
--
-- ⚠️ A-5 给过另一个选项（独立的「内容 × 物种」覆写关联表）。选了加列：
--    覆写是**内容自己的一个属性**、一对一、且要在内容列表里被大量读到 ——
--    独立表会让那个列表每行多一次 join，而它换来的只是"不动内容表主结构"这一个好处。
alter table content_posts add column species_override varchar(20);

comment on column content_posts.species_override is
    '行级物种覆写（V1.1.6 Story 14.1）。推导优先级：本列 > 作者账号物种定位（仅虚拟账号）> 作者宠物档案 > 无。稀疏列，真实用户内容恒 NULL。';

-- 按物种筛选虚拟账号（AC2）。只索引虚拟账号 —— 真实账号这一列恒空。
create index ix_users_account_species on users (account_species)
    where account_species is not null;
