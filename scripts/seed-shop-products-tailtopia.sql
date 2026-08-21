-- ============================================================================
-- Tailtopia · 门店在库商品 → 电商商品列表（测试数据）
--
-- 数据来源：运营导出的《Stok Produk On Hand》
--           Stok_Produk_Tailtopia_20260810_163734(1).xlsx（Cabang: Utama，2026-08-10 16:37）
--           89 个在库 SKU，Grand Total 578 件 / Rp 20.225.700
--
-- 🔴🔴 这【不是】Flyway 迁移，**绝不能放进 db/migration** —— 放进去就会在生产上跑。
--     它是手动执行的脚本，只给本地 / staging 用。
--
-- 用法：
--   docker exec -i petgo-pg psql -U petgo -d petgo -v ON_ERROR_STOP=1 \
--       < scripts/seed-shop-products-tailtopia.sql
--
-- 幂等：可重复执行（按 public_token 冲突跳过）。
-- 清理：DELETE FROM shop_products WHERE public_token LIKE 'ttk%';（级联带走 SKU / 库存 / 流水）
--
-- 🔴 商品会莫名其妙全部变成「未上架」？不是本脚本的问题 ——
--    Epic1ChainIntegrationTest:59 和 AdminShopListingEndpointIntegrationTest:65 的 @BeforeEach 里
--    有一条【不带 WHERE 的】 UPDATE shop_products SET is_active = false，
--    这两个测试类只要跑在本地 petgo 库（而不是独立的 Testcontainers 库）上，
--    就会把全库商品连同本脚本灌的种子数据一起清掉在售态。恢复：
--      UPDATE shop_products SET is_active = true
--      WHERE public_token LIKE 'ttk%' OR public_token LIKE 'demo-%';
--
-- ⚠️ 本脚本只灌【商品 + SKU + 库存 + 采购入库流水】。
--    配送范围 shipping_zones / 满额包邮 shipping_settings 在 scripts/seed-shop-demo.sql，
--    **不配那个就一单也发不出去** —— 空库上请两个都跑。
--
-- ── 转换过程中做的判断（都可以直接改，改完重跑前先按上面的清理语句删干净）─────
--
-- 1. 品类：ProductCategory 枚举只有四个固定值（FR-94 ③，禁新增），Excel 有六类，映射为
--        Food                     → MAKANAN
--        Treats & Snacks          → CAMILAN
--        Healthcare & Supplements → OBAT_VITAMIN
--        Accessories / Bowls & Feeders / Toys → PERAWATAN
--    🔴 最后一条是**将就**：碗、玩具、地垫本质不是「洗护」，只是四个桶里没有更近的。
--       要正经收纳这 13 个商品，得先决策是否给 ProductCategory 追加一个值（只能追加在末尾）。
--
-- 2. 合并规格：名字只差重量的行合并成一个商品的多个 SKU（Majes HP Recipe 四条 0.5/1.5kg、
--    Pedigree Beef 1.5/3kg）。89 行 → 84 商品 / 89 SKU。
--
-- 3. 商品名 60 字符上限（shop_products.name VARCHAR(60)）：Majes 系列 12 条超长，
--    做了缩写；同系列没超长的兄弟品也一起改成同一写法，免得列表里半长半短。
--    原始全名保留在 detail_html 里，没有丢。
--
-- 4. species / age_stage / body_size：**只按商品名里明写的判**（Dog/Cat/Puppy/Small…），
--    名字没写的取 UNIVERSAL；另外 Cesar / Pedigree / Perro / Royal Canin → DOG，
--    Life Cat → CAT（品牌本身是单一物种线）。Prama、Wanpy Creamy Treat 名字没写物种，
--    留 UNIVERSAL —— 猜错会被 FR-107 硬过滤掉，宁可不过滤。
--
-- 5. 退货规则：MAKANAN / CAMILAN → NO_RETURN_AFTER_OPEN；OBAT_VITAMIN → NON_RETURNABLE；
--    PERAWATAN（碗/玩具/地垫）→ RETURNABLE。Excel 里没有这一列，是按品类给的默认。
--
-- 6. 🔴 feeding_guide 一律留空 —— Excel 里没有每日建议喂量，而它是 FR-109 粮量见底预估的
--    唯一计算依据，**编不得**。后果：这 33 个 MAKANAN 商品永远不会触发补货提醒，
--    后台商品页会显示那条黄色警告，**这是预期，不是 bug**。真实喂量归 DEP-6（Rendy）。
--    要验 Epic 6 复购链路，请用 seed-shop-demo.sql 里带喂量的 demo-mkn-* 三条。
--
-- 7. 🔒 进货价：Excel 只有售价（Nilai Stok = 库存 × 售价，不是成本），所以
--    shop_skus.cost_price 留空。但 inventory_movements 的 CHECK 要求入库单必填单价
--    （ck_inventory_movements_inbound_required），那里填的是 **售价 ÷ 2 的占位值**。
--    🔴 因此 AB-13A 毛利读数在这批数据上是假的。没有这行入库流水，退货质检通过时会抛
--    「该 SKU 尚无采购入库记录」，整条退货链在验收时会卡死 —— 两害相权取的这个。
--
-- 8. main_image_key 是 OSS objectKey（seed/tailtopia/<门店SKU>.jpg），**不是 URL**，
--    展示侧由 ShopImageUrlResolver 拼成 OSS_CDN_BASE_URL + "/" + objectKey。
--    🔴 本脚本【不负责图片对象本身】—— 有没有图取决于桶里那个 key 上有没有对象，与本脚本无关。
--
--    2026-08-19 补图：从运营《Tailtopia_比价表_20260819.xlsx》的「电商商品链接」列
--    （28 个 Tokopedia 商详页）抓到 24 张真实主图，文件名与这里的 key 逐一对应，
--    因此**补图不需要改任何一行 SQL**，只要把对象传上公开桶：
--        python3 scripts/upload-shop-images-oss.py --dry-run   # 先看计划
--        python3 scripts/upload-shop-images-oss.py --verify    # 传完回读 CDN 校验
--    图片在仓库外：~/Desktop/petgo-shop-images/oss/（清单见同目录 MANIFEST.csv）。
--    余下 60 个商品仍是空 key 位（表里本就没有链接，或链接只到品牌官方店首页），
--    图位继续走占位图 —— **这是预期**，要真图请运营从后台上传。
--
--    ⚠️ 抓到的是**商家侧挂牌图**：部分带第三方店铺水印（sukapets / MY BOSS PETSHOP /
--    POCAYO / MOOMOOO PETSHOP 等）与快递 logo，且多规格合并挂牌的商品（Cesar 全口味、
--    Prama、Wanpy 系列）主图是整个系列的合集图，不是该 SKU 单品图。
--    对外正式开卖前建议换成自拍图；明细逐条标在 MANIFEST.csv 的 note 列。
--
-- 9. 库存 = Excel 的 Stok On Hand 原值。低库存阈值默认 5，源数据里天然有一批 ≤5 的，
--    低库存文案能验到；**没有 0 库存的商品**，要验售罄态请用 demo-sku-obt-02a。
--
-- 10. 《比价表》比 8/10 那份库存导出多出来的「线上低价 / 线上高价 / 线上参考价 / 价差 %」
--     （63 行有值）**没有落库** —— 现有 schema 里没有承载竞品价的字段，
--     shop_skus.cost_price 是进货价，语义不同，不能挪用。要做定价看板得先加表，属新需求。
--     两份表的 89 个 SKU 码 / 售价 / 库存已逐条比对，**完全一致**，所以目录部分无需重灌。
-- ============================================================================

\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------------
-- 🔒 安全闸：不在生产库上跑
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF current_database() = 'petgo' AND
       coalesce(current_setting('petgo.allow_demo_seed', true), '') <> 'yes' THEN
        RAISE NOTICE '当前库是 % —— 本地库同名，继续执行。生产库请勿运行本脚本。', current_database();
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 1. 商品（84 条）
-- ---------------------------------------------------------------------------
INSERT INTO shop_products
    (public_token, name, brand, category, main_image_key, species, body_size, age_stage,
     detail_html, feeding_guide, shelf_life_note, return_policy, sort_weight, is_active)
VALUES
    -- ── CAMILAN
    ('ttk11e0e3f2758bb89b4d0', 'Perro Pouch Dog Food Chewy Stick Beef Steak', 'Perro', 'CAMILAN',
     'seed/tailtopia/r-trt-prr-001.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Perro Pouch Dog Food Chewy Stick Beef Steak</p><p>Merek: Perro</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-PRR-001</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk471bc7c47fe56ae5e25', 'Perro Pouch Dog Food Chewy Stick Grilled Lamb', 'Perro', 'CAMILAN',
     'seed/tailtopia/r-trt-prr-002.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Perro Pouch Dog Food Chewy Stick Grilled Lamb</p><p>Merek: Perro</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-PRR-002</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk4220b35f5e09ed1ea12', 'Perro Pouch Dog Food Chewy Stick Sweet Mango', 'Perro', 'CAMILAN',
     'seed/tailtopia/r-trt-prr-003.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Perro Pouch Dog Food Chewy Stick Sweet Mango</p><p>Merek: Perro</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-PRR-003</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk07a11bfc3b079c4154f', 'Perro Snack Carnivore Crunch Chicken', 'Perro', 'CAMILAN',
     'seed/tailtopia/r-trt-prr-004.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Perro Snack Carnivore Crunch Chicken</p><p>Merek: Perro</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-PRR-004</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk1927568986a5c0d1912', 'Prama Blueberry', 'Prama', 'CAMILAN',
     'seed/tailtopia/r-trt-prm-001.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Prama Blueberry</p><p>Merek: Prama</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-PRM-001</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttkc754d02b8e98f642ed2', 'Prama Chicken Pate', 'Prama', 'CAMILAN',
     'seed/tailtopia/r-trt-prm-002.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Prama Chicken Pate</p><p>Merek: Prama</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-PRM-002</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk993a6c6620486552f27', 'Prama Grilled Beef', 'Prama', 'CAMILAN',
     'seed/tailtopia/r-trt-prm-003.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Prama Grilled Beef</p><p>Merek: Prama</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-PRM-003</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk1a424dbc9e2c76a5c20', 'Prama Mango', 'Prama', 'CAMILAN',
     'seed/tailtopia/r-trt-prm-004.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Prama Mango</p><p>Merek: Prama</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-PRM-004</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttkd750ddb5a7d46981b3a', 'Prama Melon', 'Prama', 'CAMILAN',
     'seed/tailtopia/r-trt-prm-005.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Prama Melon</p><p>Merek: Prama</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-PRM-005</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk8134c4e71c5d23668fd', 'Prama Milk', 'Prama', 'CAMILAN',
     'seed/tailtopia/r-trt-prm-000.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Prama Milk</p><p>Merek: Prama</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-PRM-000</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttkeb55a65e4e7dded7c2d', 'Prama Salami', 'Prama', 'CAMILAN',
     'seed/tailtopia/r-trt-prm-006.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Prama Salami</p><p>Merek: Prama</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-PRM-006</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk9670ff496c2096960d7', 'Prama Salmon', 'Prama', 'CAMILAN',
     'seed/tailtopia/r-trt-prm-007.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Prama Salmon</p><p>Merek: Prama</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-PRM-007</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk90ee064381374c90006', 'Prama Smoked Bacon', 'Prama', 'CAMILAN',
     'seed/tailtopia/r-trt-prm-008.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Prama Smoked Bacon</p><p>Merek: Prama</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-PRM-008</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk7028d82e386a1fad4c9', 'WANPY CREAMY TREAT CHICKEN', 'Wanpy', 'CAMILAN',
     'seed/tailtopia/r-trt-wnp-010.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>WANPY CREAMY TREAT CHICKEN</p><p>Merek: Wanpy</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-WNP-010</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk6a5f39f06834fe5a130', 'WANPY CREAMY TREAT TUNA', 'Wanpy', 'CAMILAN',
     'seed/tailtopia/r-trt-wnp-009.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>WANPY CREAMY TREAT TUNA</p><p>Merek: Wanpy</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-WNP-009</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttke342020da2344572ff5', 'WANPY CREAMY TREAT TUNA & CODFISH', 'Wanpy', 'CAMILAN',
     'seed/tailtopia/r-trt-wnp-013.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>WANPY CREAMY TREAT TUNA & CODFISH</p><p>Merek: Wanpy</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-WNP-013</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk45bd0d49d8460548f5d', 'WANPY CREAMY TREAT TUNA & SALMON', 'Wanpy', 'CAMILAN',
     'seed/tailtopia/r-trt-wnp-012.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>WANPY CREAMY TREAT TUNA & SALMON</p><p>Merek: Wanpy</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-WNP-012</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttkbc636c00744ca3fa8ad', 'WANPY CREAMY TREAT TUNA & SHRIMP', 'Wanpy', 'CAMILAN',
     'seed/tailtopia/r-trt-wnp-011.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>WANPY CREAMY TREAT TUNA & SHRIMP</p><p>Merek: Wanpy</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-WNP-011</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk6048dd21b3ef5854aea', 'WANPY SOFT OVEN ROASTED BEEF JERKY SLICES FOR DOG', 'Wanpy', 'CAMILAN',
     'seed/tailtopia/r-trt-wnp-007.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>WANPY SOFT OVEN ROASTED BEEF JERKY SLICES FOR DOG</p><p>Merek: Wanpy</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-WNP-007</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttkac1cb28be8e02d36e88', 'WANPY SOFT OVEN ROASTED SALMON STICKS FOR DOG', 'Wanpy', 'CAMILAN',
     'seed/tailtopia/r-trt-wnp-008.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>WANPY SOFT OVEN ROASTED SALMON STICKS FOR DOG</p><p>Merek: Wanpy</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-WNP-008</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttkb425fda4d89dc2e4807', 'Wanpy Chicken Jerky & Codfish Hearts for Cat', 'Wanpy', 'CAMILAN',
     'seed/tailtopia/r-trt-wnp-001.jpg', 'CAT', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Wanpy Chicken Jerky & Codfish Hearts for Cat</p><p>Merek: Wanpy</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-WNP-001</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk378eb93ce57fc44a777', 'Wanpy Freeze Dried Chicken & Fruits for Dog', 'Wanpy', 'CAMILAN',
     'seed/tailtopia/r-trt-wnp-002.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Wanpy Freeze Dried Chicken & Fruits for Dog</p><p>Merek: Wanpy</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-WNP-002</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk2fd0da887a846b4469e', 'Wanpy Freeze Dried Duck Breast for Dog', 'Wanpy', 'CAMILAN',
     'seed/tailtopia/r-trt-wnp-003.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Wanpy Freeze Dried Duck Breast for Dog</p><p>Merek: Wanpy</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-WNP-003</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttkf51d632904c1157c2ea', 'Wanpy Oven Roasted Chicken Jerky & Codfish Sushi for Dog', 'Wanpy', 'CAMILAN',
     'seed/tailtopia/r-trt-wnp-004.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Wanpy Oven Roasted Chicken Jerky & Codfish Sushi for Dog</p><p>Merek: Wanpy</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-WNP-004</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk02fbb3f95d26b9e81c5', 'Wanpy Oven Roasted Chicken Jerky Chips for Dog', 'Wanpy', 'CAMILAN',
     'seed/tailtopia/r-trt-wnp-005.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Wanpy Oven Roasted Chicken Jerky Chips for Dog</p><p>Merek: Wanpy</p><p>Kategori toko: Treats & Snacks</p><p>Kode SKU toko: R-TRT-WNP-005</p>', NULL,
     'Simpan di tempat kering dan sejuk. Setelah dibuka, habiskan segera. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    -- ── MAKANAN
    ('ttkf993739550da6e34df5', 'CESAR BEEF & LIVER', 'Cesar', 'MAKANAN',
     'seed/tailtopia/r-fod-csr-002.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>CESAR BEEF & LIVER</p><p>Merek: Cesar</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-CSR-002</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk631e3ba208cf4c18111', 'CESAR CHICKEN', 'Cesar', 'MAKANAN',
     'seed/tailtopia/r-fod-csr-003.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>CESAR CHICKEN</p><p>Merek: Cesar</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-CSR-003</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk78a1727fbf07ddea7b2', 'CESAR CHICKEN & VEGETABLES', 'Cesar', 'MAKANAN',
     'seed/tailtopia/r-fod-csr-001.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>CESAR CHICKEN & VEGETABLES</p><p>Merek: Cesar</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-CSR-001</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttkff067c1fa4b4db56ef9', 'KITCHEN FLAVOR DOG FOOD PATE DELIGHT SALMON & CHICKEN BEAUTY', 'Kitchen Flavor', 'MAKANAN',
     'seed/tailtopia/r-fod-ktf-004.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>KITCHEN FLAVOR DOG FOOD PATE DELIGHT SALMON & CHICKEN BEAUTY</p><p>Merek: Kitchen Flavor</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-KTF-004</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk25726a90aa3325c2426', 'Kitchen Flavor Dog Food Antarctic Krill Small Breed Puppy', 'Kitchen Flavor', 'MAKANAN',
     'seed/tailtopia/r-fod-ktf-001.jpg', 'DOG', 'SMALL', 'PUPPY',
     '<p>Kitchen Flavor Dog Food Antarctic Krill Small Breed Puppy</p><p>Merek: Kitchen Flavor</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-KTF-001</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttkbfb783dc7ca6d1bf08e', 'Kitchen Flavor Dog Food Salmon Beauty All Life Stages', 'Kitchen Flavor', 'MAKANAN',
     'seed/tailtopia/r-fod-ktf-003.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Kitchen Flavor Dog Food Salmon Beauty All Life Stages</p><p>Merek: Kitchen Flavor</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-KTF-003</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk1b77b2c84874a45ea25', 'LIFE CAT CREAMY CHICKEN & LIVER', 'Life Cat', 'MAKANAN',
     'seed/tailtopia/r-fod-lfc-004.jpg', 'CAT', 'UNIVERSAL', 'UNIVERSAL',
     '<p>LIFE CAT CREAMY CHICKEN & LIVER</p><p>Merek: Life Cat</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-LFC-004</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttkbf92165f3b37cbfdb6a', 'LIFE CAT CREAMY KATSUO BONITO', 'Life Cat', 'MAKANAN',
     'seed/tailtopia/r-fod-lfc-001.jpg', 'CAT', 'UNIVERSAL', 'UNIVERSAL',
     '<p>LIFE CAT CREAMY KATSUO BONITO</p><p>Merek: Life Cat</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-LFC-001</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttkeba05311ab26ab7ab2e', 'LIFE CAT CREAMY SALMON BELLY', 'Life Cat', 'MAKANAN',
     'seed/tailtopia/r-fod-lfc-003.jpg', 'CAT', 'UNIVERSAL', 'UNIVERSAL',
     '<p>LIFE CAT CREAMY SALMON BELLY</p><p>Merek: Life Cat</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-LFC-003</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttka29a426c7209fbc8e55', 'LIFE CAT CREAMY TUNA MAGURO', 'Life Cat', 'MAKANAN',
     'seed/tailtopia/r-fod-lfc-002.jpg', 'CAT', 'UNIVERSAL', 'UNIVERSAL',
     '<p>LIFE CAT CREAMY TUNA MAGURO</p><p>Merek: Life Cat</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-LFC-002</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk4e038c294987298e8e7', 'Majes Cat Immunity Pack Blood Support - Squab & Foie Gras', 'Majes', 'MAKANAN',
     'seed/tailtopia/r-fod-mjs-001.jpg', 'CAT', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Majes Cat Immunity Pack Blood Support - Squab & Foie Gras</p><p>Merek: Majes</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-MJS-001</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttkd1f93d39bfe3c11b5ce', 'Majes Cat Immunity Pack Coat Health - Goat Milk & Salmon', 'Majes', 'MAKANAN',
     'seed/tailtopia/r-fod-mjs-002.jpg', 'CAT', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Majes Cat Immunity Pack Coat Health - Goat Milk & Salmon</p><p>Merek: Majes</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-MJS-002</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk224b9c94e4c6e9c04fc', 'Majes Cat Immunity Pack Digestive Urinary - Chicken', 'Majes', 'MAKANAN',
     'seed/tailtopia/r-fod-mjs-003.jpg', 'CAT', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Majes Cat Immunity Pack Digestive Urinary - Chicken</p><p>Merek: Majes</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-MJS-003</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttke3d6e126ebbd6fab2f6', 'Majes Cat Immunity Pack Tear Stain - Duck & Pear', 'Majes', 'MAKANAN',
     'seed/tailtopia/r-fod-mjs-004.jpg', 'CAT', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Majes Cat Immunity Pack Tear Stain - Duck & Pear</p><p>Merek: Majes</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-MJS-004</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk266a9e0f241c9eb37c3', 'Majes Cat Nutri Gravy Skin & Coat - Tuna & Krill', 'Majes', 'MAKANAN',
     'seed/tailtopia/r-fod-mjs-005.jpg', 'CAT', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Majes Cat Nutri Gravy Skin & Coat - Tuna & Krill</p><p>Merek: Majes</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-MJS-005</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk290bfa8fc43bb58ed7c', 'Majes Cat Nutri Gravy Urinary Digestive - Chicken & Krill', 'Majes', 'MAKANAN',
     'seed/tailtopia/r-fod-mjs-006.jpg', 'CAT', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Majes Cat Nutri Gravy Urinary Digestive - Chicken & Krill</p><p>Merek: Majes</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-MJS-006</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttkc55cb60f197492f4c6a', 'Majes Dog HP Recipe Bone & Joint - Beef Feast', 'Majes', 'MAKANAN',
     'seed/tailtopia/r-fod-mjs-007.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Majes Dog HP Recipe Bone & Joint - Beef Feast</p><p>Merek: Majes</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-MJS-007 / R-FOD-MJS-008</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk3d3e84a41ca5365c79e', 'Majes Dog HP Recipe Digestive - Chicken Feast', 'Majes', 'MAKANAN',
     'seed/tailtopia/r-fod-mjs-009.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Majes Dog HP Recipe Digestive - Chicken Feast</p><p>Merek: Majes</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-MJS-009 / R-FOD-MJS-010</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk4f493b5b39541107d18', 'Majes Dog HP Recipe Puppy Growth & Immune - Goat Milk', 'Majes', 'MAKANAN',
     'seed/tailtopia/r-fod-mjs-011.jpg', 'DOG', 'UNIVERSAL', 'PUPPY',
     '<p>Majes Dog HP Recipe Puppy Growth & Immune - Goat Milk</p><p>Merek: Majes</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-MJS-011 / R-FOD-MJS-012</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk57714744e072698776b', 'Majes Dog HP Recipe Skin & Coat - Oceanic Salmon Feast', 'Majes', 'MAKANAN',
     'seed/tailtopia/r-fod-mjs-013.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Majes Dog HP Recipe Skin & Coat - Oceanic Salmon Feast</p><p>Merek: Majes</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-MJS-013 / R-FOD-MJS-014</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttkd2bb206b04f7add5cfe', 'Majes Dog Immunity Pack Blood Support - Squab & Foie Gras', 'Majes', 'MAKANAN',
     'seed/tailtopia/r-fod-mjs-015.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Majes Dog Immunity Pack Blood Support - Squab & Foie Gras</p><p>Merek: Majes</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-MJS-015</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttkde3dfbc87c7f89908ca', 'Majes Dog Immunity Pack Coat Health - Goat Milk & Salmon', 'Majes', 'MAKANAN',
     'seed/tailtopia/r-fod-mjs-016.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Majes Dog Immunity Pack Coat Health - Goat Milk & Salmon</p><p>Merek: Majes</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-MJS-016</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttkb97b022752f584a7723', 'Majes Dog Immunity Pack Digestive - Goat Milk & Chicken', 'Majes', 'MAKANAN',
     'seed/tailtopia/r-fod-mjs-017.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Majes Dog Immunity Pack Digestive - Goat Milk & Chicken</p><p>Merek: Majes</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-MJS-017</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk7eeeb61bc725c7a7965', 'Majes Dog Immunity Pack Tear Stain - Duck & Pear', 'Majes', 'MAKANAN',
     'seed/tailtopia/r-fod-mjs-018.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Majes Dog Immunity Pack Tear Stain - Duck & Pear</p><p>Merek: Majes</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-MJS-018</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk89c72b571654518f837', 'Majes Dog Nutri Gravy Joint & Immune - Chicken & Beef', 'Majes', 'MAKANAN',
     'seed/tailtopia/r-fod-mjs-019.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Majes Dog Nutri Gravy Joint & Immune - Chicken & Beef</p><p>Merek: Majes</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-MJS-019</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttkf2aacc782051dae3af2', 'Majes Dog Nutri Gravy Skin & Coat - Chicken & Tuna', 'Majes', 'MAKANAN',
     'seed/tailtopia/r-fod-mjs-020.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Majes Dog Nutri Gravy Skin & Coat - Chicken & Tuna</p><p>Merek: Majes</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-MJS-020</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk0029ef1822296055986', 'Majes Dog Nutri Meat Topper - Tuna', 'Majes', 'MAKANAN',
     'seed/tailtopia/r-fod-mjs-021.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Majes Dog Nutri Meat Topper - Tuna</p><p>Merek: Majes</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-MJS-021</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk48205896ef043923ccb', 'Pedigree Beef', 'Pedigree', 'MAKANAN',
     'seed/tailtopia/r-fod-pdg-001.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Pedigree Beef</p><p>Merek: Pedigree</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-PDG-001 / R-FOD-PDG-002</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttkc0e43179562da6ad3e5', 'PERRO WET DOG FOOD BEEF MINCED', 'Perro', 'MAKANAN',
     'seed/tailtopia/r-fod-prr-001.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>PERRO WET DOG FOOD BEEF MINCED</p><p>Merek: Perro</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-PRR-001</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttke505bbf0c713f01adb7', 'PERRO WET DOG FOOD CHICKEN CHUCKED', 'Perro', 'MAKANAN',
     'seed/tailtopia/r-fod-prr-003.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>PERRO WET DOG FOOD CHICKEN CHUCKED</p><p>Merek: Perro</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-PRR-003</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttk8002c24029cce074509', 'PERRO WET DOG FOOD CHICKEN MINCED', 'Perro', 'MAKANAN',
     'seed/tailtopia/r-fod-prr-002.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>PERRO WET DOG FOOD CHICKEN MINCED</p><p>Merek: Perro</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-PRR-002</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttkc87c17af6b77bf7838f', 'PERRO WET DOG FOOD LAMB MINCED', 'Perro', 'MAKANAN',
     'seed/tailtopia/r-fod-prr-004.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>PERRO WET DOG FOOD LAMB MINCED</p><p>Merek: Perro</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-PRR-004</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    ('ttkd9553c5e3fe7fb9a76f', 'Royal Canin X-Small Adult', 'Royal Canin', 'MAKANAN',
     'seed/tailtopia/r-fod-ryc-002.jpg', 'DOG', 'SMALL', 'ADULT',
     '<p>Royal Canin X-Small Adult</p><p>Merek: Royal Canin</p><p>Kategori toko: Food</p><p>Kode SKU toko: R-FOD-RYC-002</p>', NULL,
     'Simpan di tempat kering dan sejuk, hindari sinar matahari langsung. Lihat tanggal kedaluwarsa pada kemasan.', 'NO_RETURN_AFTER_OPEN', 0, true),
    -- ── OBAT_VITAMIN
    ('ttkfa51dcd7678656db539', 'SEBACARE OBAT SPRAY JAMUR', 'SEBACARE', 'OBAT_VITAMIN',
     'seed/tailtopia/r-hcs-sbc-011.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>SEBACARE OBAT SPRAY JAMUR</p><p>Merek: SEBACARE</p><p>Kategori toko: Healthcare & Supplements</p><p>Kode SKU toko: R-HCS-SBC-011</p>', NULL,
     'Simpan pada suhu ruang, jauhkan dari jangkauan anak. Lihat tanggal kedaluwarsa pada kemasan.', 'NON_RETURNABLE', 0, true),
    ('ttk38e0eca8184dc8ce647', 'SEBACARE OBAT SPRAY KUTU', 'SEBACARE', 'OBAT_VITAMIN',
     'seed/tailtopia/r-hcs-sbc-012.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>SEBACARE OBAT SPRAY KUTU</p><p>Merek: SEBACARE</p><p>Kategori toko: Healthcare & Supplements</p><p>Kode SKU toko: R-HCS-SBC-012</p>', NULL,
     'Simpan pada suhu ruang, jauhkan dari jangkauan anak. Lihat tanggal kedaluwarsa pada kemasan.', 'NON_RETURNABLE', 0, true),
    ('ttk05cf76b480a682f2f8f', 'SEBACARE OBAT SPRAY LUKA', 'SEBACARE', 'OBAT_VITAMIN',
     'seed/tailtopia/r-hcs-sbc-013.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>SEBACARE OBAT SPRAY LUKA</p><p>Merek: SEBACARE</p><p>Kategori toko: Healthcare & Supplements</p><p>Kode SKU toko: R-HCS-SBC-013</p>', NULL,
     'Simpan pada suhu ruang, jauhkan dari jangkauan anak. Lihat tanggal kedaluwarsa pada kemasan.', 'NON_RETURNABLE', 0, true),
    ('ttkc18bbb3837b00d9c638', 'SEBACARE OBAT TETES CACING', 'SEBACARE', 'OBAT_VITAMIN',
     'seed/tailtopia/r-hcs-sbc-003.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>SEBACARE OBAT TETES CACING</p><p>Merek: SEBACARE</p><p>Kategori toko: Healthcare & Supplements</p><p>Kode SKU toko: R-HCS-SBC-003</p>', NULL,
     'Simpan pada suhu ruang, jauhkan dari jangkauan anak. Lihat tanggal kedaluwarsa pada kemasan.', 'NON_RETURNABLE', 0, true),
    ('ttkb87c422eb49994315ba', 'SEBACARE OBAT TETES DEMAM', 'SEBACARE', 'OBAT_VITAMIN',
     'seed/tailtopia/r-hcs-sbc-005.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>SEBACARE OBAT TETES DEMAM</p><p>Merek: SEBACARE</p><p>Kategori toko: Healthcare & Supplements</p><p>Kode SKU toko: R-HCS-SBC-005</p>', NULL,
     'Simpan pada suhu ruang, jauhkan dari jangkauan anak. Lihat tanggal kedaluwarsa pada kemasan.', 'NON_RETURNABLE', 0, true),
    ('ttk37479aa5f847594515e', 'SEBACARE OBAT TETES FLU', 'SEBACARE', 'OBAT_VITAMIN',
     'seed/tailtopia/r-hcs-sbc-001.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>SEBACARE OBAT TETES FLU</p><p>Merek: SEBACARE</p><p>Kategori toko: Healthcare & Supplements</p><p>Kode SKU toko: R-HCS-SBC-001</p>', NULL,
     'Simpan pada suhu ruang, jauhkan dari jangkauan anak. Lihat tanggal kedaluwarsa pada kemasan.', 'NON_RETURNABLE', 0, true),
    ('ttkd0a7d69e1d2dc353134', 'SEBACARE OBAT TETES MATA', 'SEBACARE', 'OBAT_VITAMIN',
     'seed/tailtopia/r-hcs-sbc-002.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>SEBACARE OBAT TETES MATA</p><p>Merek: SEBACARE</p><p>Kategori toko: Healthcare & Supplements</p><p>Kode SKU toko: R-HCS-SBC-002</p>', NULL,
     'Simpan pada suhu ruang, jauhkan dari jangkauan anak. Lihat tanggal kedaluwarsa pada kemasan.', 'NON_RETURNABLE', 0, true),
    ('ttk0ca13dede857212202a', 'SEBACARE OBAT TETES MUNTAH', 'SEBACARE', 'OBAT_VITAMIN',
     'seed/tailtopia/r-hcs-sbc-008.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>SEBACARE OBAT TETES MUNTAH</p><p>Merek: SEBACARE</p><p>Kategori toko: Healthcare & Supplements</p><p>Kode SKU toko: R-HCS-SBC-008</p>', NULL,
     'Simpan pada suhu ruang, jauhkan dari jangkauan anak. Lihat tanggal kedaluwarsa pada kemasan.', 'NON_RETURNABLE', 0, true),
    ('ttk16bd918556036b2826a', 'SEBACARE OBAT TETES PENURUNAN BIRAHI', 'SEBACARE', 'OBAT_VITAMIN',
     'seed/tailtopia/r-hcs-sbc-009.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>SEBACARE OBAT TETES PENURUNAN BIRAHI</p><p>Merek: SEBACARE</p><p>Kategori toko: Healthcare & Supplements</p><p>Kode SKU toko: R-HCS-SBC-009</p>', NULL,
     'Simpan pada suhu ruang, jauhkan dari jangkauan anak. Lihat tanggal kedaluwarsa pada kemasan.', 'NON_RETURNABLE', 0, true),
    ('ttkbe7c818dda84c0abaf0', 'SEBACARE OBAT TETES RECOVERY', 'SEBACARE', 'OBAT_VITAMIN',
     'seed/tailtopia/r-hcs-sbc-010.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>SEBACARE OBAT TETES RECOVERY</p><p>Merek: SEBACARE</p><p>Kategori toko: Healthcare & Supplements</p><p>Kode SKU toko: R-HCS-SBC-010</p>', NULL,
     'Simpan pada suhu ruang, jauhkan dari jangkauan anak. Lihat tanggal kedaluwarsa pada kemasan.', 'NON_RETURNABLE', 0, true),
    ('ttk821ab14e96552a823dc', 'SEBACARE OBAT TETES SARIAWAN', 'SEBACARE', 'OBAT_VITAMIN',
     'seed/tailtopia/r-hcs-sbc-007.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>SEBACARE OBAT TETES SARIAWAN</p><p>Merek: SEBACARE</p><p>Kategori toko: Healthcare & Supplements</p><p>Kode SKU toko: R-HCS-SBC-007</p>', NULL,
     'Simpan pada suhu ruang, jauhkan dari jangkauan anak. Lihat tanggal kedaluwarsa pada kemasan.', 'NON_RETURNABLE', 0, true),
    ('ttka37958e9654e9d85dfc', 'SEBACARE OBAT TETES TELINGA', 'SEBACARE', 'OBAT_VITAMIN',
     'seed/tailtopia/r-hcs-sbc-004.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>SEBACARE OBAT TETES TELINGA</p><p>Merek: SEBACARE</p><p>Kategori toko: Healthcare & Supplements</p><p>Kode SKU toko: R-HCS-SBC-004</p>', NULL,
     'Simpan pada suhu ruang, jauhkan dari jangkauan anak. Lihat tanggal kedaluwarsa pada kemasan.', 'NON_RETURNABLE', 0, true),
    ('ttk2e2b9885da20dcb4ce3', 'SEBACARE OBAT TETES URINARY', 'SEBACARE', 'OBAT_VITAMIN',
     'seed/tailtopia/r-hcs-sbc-006.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>SEBACARE OBAT TETES URINARY</p><p>Merek: SEBACARE</p><p>Kategori toko: Healthcare & Supplements</p><p>Kode SKU toko: R-HCS-SBC-006</p>', NULL,
     'Simpan pada suhu ruang, jauhkan dari jangkauan anak. Lihat tanggal kedaluwarsa pada kemasan.', 'NON_RETURNABLE', 0, true),
    -- ── PERAWATAN
    ('ttk37efa353cf1863f4c11', 'Boneka Gigitan Hewan MK-7', 'No Brand', 'PERAWATAN',
     'seed/tailtopia/r-toy-nob-001.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Boneka Gigitan Hewan MK-7</p><p>Merek: No Brand</p><p>Kategori toko: Toys</p><p>Kode SKU toko: R-TOY-NOB-001</p>', NULL,
     'Tidak ada masa simpan. Simpan di tempat kering. Cuci bersih sebelum dan sesudah dipakai.', 'RETURNABLE', 0, true),
    ('ttk8ff1385230e029eca52', 'Boneka Paws MK-4', 'No Brand', 'PERAWATAN',
     'seed/tailtopia/r-toy-nob-002.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Boneka Paws MK-4</p><p>Merek: No Brand</p><p>Kategori toko: Toys</p><p>Kode SKU toko: R-TOY-NOB-002</p>', NULL,
     'Tidak ada masa simpan. Simpan di tempat kering. Cuci bersih sebelum dan sesudah dipakai.', 'RETURNABLE', 0, true),
    ('ttk6b2f85db28eb6c0a340', 'Boneka Tupai', 'No Brand', 'PERAWATAN',
     'seed/tailtopia/r-toy-nob-003.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Boneka Tupai</p><p>Merek: No Brand</p><p>Kategori toko: Toys</p><p>Kode SKU toko: R-TOY-NOB-003</p>', NULL,
     'Tidak ada masa simpan. Simpan di tempat kering. Cuci bersih sebelum dan sesudah dipakai.', 'RETURNABLE', 0, true),
    ('ttk27cba3664db66ebc07e', 'Bowl Stainless PJ-49', 'No Brand', 'PERAWATAN',
     'seed/tailtopia/r-bwf-nob-001.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Bowl Stainless PJ-49</p><p>Merek: No Brand</p><p>Kategori toko: Bowls & Feeders</p><p>Kode SKU toko: R-BWF-NOB-001</p>', NULL,
     'Tidak ada masa simpan. Simpan di tempat kering. Cuci bersih sebelum dan sesudah dipakai.', 'RETURNABLE', 0, true),
    ('ttkf82c76e1296a6f9fe2a', 'Interaktif pet toy PJ-039', 'No Brand', 'PERAWATAN',
     'seed/tailtopia/r-toy-nob-004.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Interaktif pet toy PJ-039</p><p>Merek: No Brand</p><p>Kategori toko: Toys</p><p>Kode SKU toko: R-TOY-NOB-004</p>', NULL,
     'Tidak ada masa simpan. Simpan di tempat kering. Cuci bersih sebelum dan sesudah dipakai.', 'RETURNABLE', 0, true),
    ('ttkeb9698517ad48434b15', 'Keset Anabul- Cat', 'No Brand', 'PERAWATAN',
     'seed/tailtopia/r-acc-nob-001.jpg', 'CAT', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Keset Anabul- Cat</p><p>Merek: No Brand</p><p>Kategori toko: Accessories</p><p>Kode SKU toko: R-ACC-NOB-001</p>', NULL,
     'Tidak ada masa simpan. Simpan di tempat kering. Cuci bersih sebelum dan sesudah dipakai.', 'RETURNABLE', 0, true),
    ('ttk0593afeb9849442a422', 'Keset Anabul-doggy', 'No Brand', 'PERAWATAN',
     'seed/tailtopia/r-acc-nob-002.jpg', 'DOG', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Keset Anabul-doggy</p><p>Merek: No Brand</p><p>Kategori toko: Accessories</p><p>Kode SKU toko: R-ACC-NOB-002</p>', NULL,
     'Tidak ada masa simpan. Simpan di tempat kering. Cuci bersih sebelum dan sesudah dipakai.', 'RETURNABLE', 0, true),
    ('ttk469ec894dee9b5d0ca1', 'Pet Auto interaktif Feeder PJ-41', 'No Brand', 'PERAWATAN',
     'seed/tailtopia/r-toy-nob-005.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Pet Auto interaktif Feeder PJ-41</p><p>Merek: No Brand</p><p>Kategori toko: Toys</p><p>Kode SKU toko: R-TOY-NOB-005</p>', NULL,
     'Tidak ada masa simpan. Simpan di tempat kering. Cuci bersih sebelum dan sesudah dipakai.', 'RETURNABLE', 0, true),
    ('ttk09174843e7bb329c2a0', 'Pet Bowl 1 set PJ 63', 'No Brand', 'PERAWATAN',
     'seed/tailtopia/r-bwf-nob-002.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Pet Bowl 1 set PJ 63</p><p>Merek: No Brand</p><p>Kategori toko: Bowls & Feeders</p><p>Kode SKU toko: R-BWF-NOB-002</p>', NULL,
     'Tidak ada masa simpan. Simpan di tempat kering. Cuci bersih sebelum dan sesudah dipakai.', 'RETURNABLE', 0, true),
    ('ttk5c681be5350f172a385', 'Pet Bowl Cake PJ-46', 'No Brand', 'PERAWATAN',
     'seed/tailtopia/r-bwf-nob-003.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Pet Bowl Cake PJ-46</p><p>Merek: No Brand</p><p>Kategori toko: Bowls & Feeders</p><p>Kode SKU toko: R-BWF-NOB-003</p>', NULL,
     'Tidak ada masa simpan. Simpan di tempat kering. Cuci bersih sebelum dan sesudah dipakai.', 'RETURNABLE', 0, true),
    ('ttked78087cb9c8abe5815', 'Pet Bowl Rabbit PJ-53', 'No Brand', 'PERAWATAN',
     'seed/tailtopia/r-bwf-nob-006.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Pet Bowl Rabbit PJ-53</p><p>Merek: No Brand</p><p>Kategori toko: Bowls & Feeders</p><p>Kode SKU toko: R-BWF-NOB-006</p>', NULL,
     'Tidak ada masa simpan. Simpan di tempat kering. Cuci bersih sebelum dan sesudah dipakai.', 'RETURNABLE', 0, true),
    ('ttkeb1ebceaaaa142982cc', 'Pet Bowl double set PJ 64', 'No Brand', 'PERAWATAN',
     'seed/tailtopia/r-bwf-nob-004.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Pet Bowl double set PJ 64</p><p>Merek: No Brand</p><p>Kategori toko: Bowls & Feeders</p><p>Kode SKU toko: R-BWF-NOB-004</p>', NULL,
     'Tidak ada masa simpan. Simpan di tempat kering. Cuci bersih sebelum dan sesudah dipakai.', 'RETURNABLE', 0, true),
    ('ttkf29b9c3c2ec192740cc', 'Pet Bowl foot PJ-59', 'No Brand', 'PERAWATAN',
     'seed/tailtopia/r-bwf-nob-005.jpg', 'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL',
     '<p>Pet Bowl foot PJ-59</p><p>Merek: No Brand</p><p>Kategori toko: Bowls & Feeders</p><p>Kode SKU toko: R-BWF-NOB-005</p>', NULL,
     'Tidak ada masa simpan. Simpan di tempat kering. Cuci bersih sebelum dan sesudah dipakai.', 'RETURNABLE', 0, true)
ON CONFLICT (public_token) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. SKU（89 条）—— 价格是最小币种单位（IDR 无小数）；cost_price 留空，见头注 7
--    (商品 token, SKU token, 规格名, 售价, 净含量克, 门店 SKU 码)
-- ---------------------------------------------------------------------------
INSERT INTO shop_skus (public_token, product_id, spec_name, price, net_weight_g, cost_price, return_policy)
SELECT v.tok, p.id, v.spec, v.price, v.net_g, NULL, NULL
FROM (VALUES
    ('ttk11e0e3f2758bb89b4d0', 'tsk11e0e3f2758bb89b4d0', '70 gr', 18000, 70::BIGINT),  -- R-TRT-PRR-001  Perro Pouch Dog Food Chewy Stick Beef Steak
    ('ttk471bc7c47fe56ae5e25', 'tsk471bc7c47fe56ae5e25', '70 gr', 18000, 70::BIGINT),  -- R-TRT-PRR-002  Perro Pouch Dog Food Chewy Stick Grilled Lamb
    ('ttk4220b35f5e09ed1ea12', 'tsk4220b35f5e09ed1ea12', '70 gr', 18000, 70::BIGINT),  -- R-TRT-PRR-003  Perro Pouch Dog Food Chewy Stick Sweet Mango
    ('ttk07a11bfc3b079c4154f', 'tsk07a11bfc3b079c4154f', '30 gr', 21500, 30::BIGINT),  -- R-TRT-PRR-004  Perro Snack Carnivore Crunch Chicken
    ('ttk1927568986a5c0d1912', 'tsk1927568986a5c0d1912', '70 gr', 29000, 70::BIGINT),  -- R-TRT-PRM-001  Prama Blueberry
    ('ttkc754d02b8e98f642ed2', 'tskc754d02b8e98f642ed2', '70 gr', 29000, 70::BIGINT),  -- R-TRT-PRM-002  Prama Chicken Pate
    ('ttk993a6c6620486552f27', 'tsk993a6c6620486552f27', '70 gr', 29000, 70::BIGINT),  -- R-TRT-PRM-003  Prama Grilled Beef
    ('ttk1a424dbc9e2c76a5c20', 'tsk1a424dbc9e2c76a5c20', '70 gr', 29000, 70::BIGINT),  -- R-TRT-PRM-004  Prama Mango
    ('ttkd750ddb5a7d46981b3a', 'tskd750ddb5a7d46981b3a', '70 gr', 29000, 70::BIGINT),  -- R-TRT-PRM-005  Prama Melon
    ('ttk8134c4e71c5d23668fd', 'tsk8134c4e71c5d23668fd', '70 gr', 29000, 70::BIGINT),  -- R-TRT-PRM-000  Prama Milk
    ('ttkeb55a65e4e7dded7c2d', 'tskeb55a65e4e7dded7c2d', '70 gr', 29000, 70::BIGINT),  -- R-TRT-PRM-006  Prama Salami
    ('ttk9670ff496c2096960d7', 'tsk9670ff496c2096960d7', '70 gr', 29000, 70::BIGINT),  -- R-TRT-PRM-007  Prama Salmon
    ('ttk90ee064381374c90006', 'tsk90ee064381374c90006', '70 gr', 29000, 70::BIGINT),  -- R-TRT-PRM-008  Prama Smoked Bacon
    ('ttk7028d82e386a1fad4c9', 'tsk7028d82e386a1fad4c9', '70 gr', 17500, 70::BIGINT),  -- R-TRT-WNP-010  WANPY CREAMY TREAT CHICKEN
    ('ttk6a5f39f06834fe5a130', 'tsk6a5f39f06834fe5a130', '70 gr', 17500, 70::BIGINT),  -- R-TRT-WNP-009  WANPY CREAMY TREAT TUNA
    ('ttke342020da2344572ff5', 'tske342020da2344572ff5', '70 gr', 17500, 70::BIGINT),  -- R-TRT-WNP-013  WANPY CREAMY TREAT TUNA & CODFISH
    ('ttk45bd0d49d8460548f5d', 'tsk45bd0d49d8460548f5d', '70 gr', 17500, 70::BIGINT),  -- R-TRT-WNP-012  WANPY CREAMY TREAT TUNA & SALMON
    ('ttkbc636c00744ca3fa8ad', 'tskbc636c00744ca3fa8ad', '70 gr', 17500, 70::BIGINT),  -- R-TRT-WNP-011  WANPY CREAMY TREAT TUNA & SHRIMP
    ('ttk6048dd21b3ef5854aea', 'tsk6048dd21b3ef5854aea', '100 gr', 34500, 100::BIGINT),  -- R-TRT-WNP-007  WANPY SOFT OVEN ROASTED BEEF JERKY SLICES FOR DOG
    ('ttkac1cb28be8e02d36e88', 'tskac1cb28be8e02d36e88', '100 gr', 26000, 100::BIGINT),  -- R-TRT-WNP-008  WANPY SOFT OVEN ROASTED SALMON STICKS FOR DOG
    ('ttkb425fda4d89dc2e4807', 'tskb425fda4d89dc2e4807', '80 gr', 23000, 80::BIGINT),  -- R-TRT-WNP-001  Wanpy Chicken Jerky & Codfish Hearts for Cat
    ('ttk378eb93ce57fc44a777', 'tsk378eb93ce57fc44a777', '40 gr', 34500, 40::BIGINT),  -- R-TRT-WNP-002  Wanpy Freeze Dried Chicken & Fruits for Dog
    ('ttk2fd0da887a846b4469e', 'tsk2fd0da887a846b4469e', '40 gr', 34500, 40::BIGINT),  -- R-TRT-WNP-003  Wanpy Freeze Dried Duck Breast for Dog
    ('ttkf51d632904c1157c2ea', 'tskf51d632904c1157c2ea', '100 gr', 26000, 100::BIGINT),  -- R-TRT-WNP-004  Wanpy Oven Roasted Chicken Jerky & Codfish Sushi for Dog
    ('ttk02fbb3f95d26b9e81c5', 'tsk02fbb3f95d26b9e81c5', '100 gr', 26000, 100::BIGINT),  -- R-TRT-WNP-005  Wanpy Oven Roasted Chicken Jerky Chips for Dog
    ('ttkf993739550da6e34df5', 'tskf993739550da6e34df5', '100 gr', 20700, 100::BIGINT),  -- R-FOD-CSR-002  CESAR BEEF & LIVER
    ('ttk631e3ba208cf4c18111', 'tsk631e3ba208cf4c18111', '100 gr', 20700, 100::BIGINT),  -- R-FOD-CSR-003  CESAR CHICKEN
    ('ttk78a1727fbf07ddea7b2', 'tsk78a1727fbf07ddea7b2', '100 gr', 20700, 100::BIGINT),  -- R-FOD-CSR-001  CESAR CHICKEN & VEGETABLES
    ('ttkff067c1fa4b4db56ef9', 'tskff067c1fa4b4db56ef9', '90 gr', 15900, 90::BIGINT),  -- R-FOD-KTF-004  KITCHEN FLAVOR DOG FOOD PATE DELIGHT SALMON & CHICKEN BEAUTY
    ('ttk25726a90aa3325c2426', 'tsk25726a90aa3325c2426', '1.5 kg', 117500, 1500::BIGINT),  -- R-FOD-KTF-001  Kitchen Flavor Dog Food Antarctic Krill Small Breed Puppy
    ('ttkbfb783dc7ca6d1bf08e', 'tskbfb783dc7ca6d1bf08e', '1.5 kg', 116500, 1500::BIGINT),  -- R-FOD-KTF-003  Kitchen Flavor Dog Food Salmon Beauty All Life Stages
    ('ttk1b77b2c84874a45ea25', 'tsk1b77b2c84874a45ea25', '15 gr x 2 pcs', 7200, 30::BIGINT),  -- R-FOD-LFC-004  LIFE CAT CREAMY CHICKEN & LIVER
    ('ttkbf92165f3b37cbfdb6a', 'tskbf92165f3b37cbfdb6a', '15 gr x 2 pcs', 7200, 30::BIGINT),  -- R-FOD-LFC-001  LIFE CAT CREAMY KATSUO BONITO
    ('ttkeba05311ab26ab7ab2e', 'tskeba05311ab26ab7ab2e', '15 gr x 2 pcs', 7200, 30::BIGINT),  -- R-FOD-LFC-003  LIFE CAT CREAMY SALMON BELLY
    ('ttka29a426c7209fbc8e55', 'tska29a426c7209fbc8e55', '15 gr x 2 pcs', 7200, 30::BIGINT),  -- R-FOD-LFC-002  LIFE CAT CREAMY TUNA MAGURO
    ('ttk4e038c294987298e8e7', 'tsk4e038c294987298e8e7', '100 g', 23000, 100::BIGINT),  -- R-FOD-MJS-001  Majes Cat Immunity Pack Blood Support - Squab & Foie Gras
    ('ttkd1f93d39bfe3c11b5ce', 'tskd1f93d39bfe3c11b5ce', '100 g', 23000, 100::BIGINT),  -- R-FOD-MJS-002  Majes Cat Immunity Pack Coat Health - Goat Milk & Salmon
    ('ttk224b9c94e4c6e9c04fc', 'tsk224b9c94e4c6e9c04fc', '100 g', 23000, 100::BIGINT),  -- R-FOD-MJS-003  Majes Cat Immunity Pack Digestive Urinary - Chicken
    ('ttke3d6e126ebbd6fab2f6', 'tske3d6e126ebbd6fab2f6', '100 g', 23000, 100::BIGINT),  -- R-FOD-MJS-004  Majes Cat Immunity Pack Tear Stain - Duck & Pear
    ('ttk266a9e0f241c9eb37c3', 'tsk266a9e0f241c9eb37c3', '85 g', 17500, 85::BIGINT),  -- R-FOD-MJS-005  Majes Cat Nutri Gravy Skin & Coat - Tuna & Krill
    ('ttk290bfa8fc43bb58ed7c', 'tsk290bfa8fc43bb58ed7c', '85 g', 17500, 85::BIGINT),  -- R-FOD-MJS-006  Majes Cat Nutri Gravy Urinary Digestive - Chicken & Krill
    ('ttkc55cb60f197492f4c6a', 'tskc55cb60f197492f4c6a', '0.5 kg', 66000, 500::BIGINT),  -- R-FOD-MJS-007  Majes Dog HP Recipe Bone & Joint - Beef Feast
    ('ttkc55cb60f197492f4c6a', 'tskad2c546014366578dce', '1.5 kg', 161500, 1500::BIGINT),  -- R-FOD-MJS-008  Majes Dog HP Recipe Bone & Joint - Beef Feast
    ('ttk3d3e84a41ca5365c79e', 'tsk3d3e84a41ca5365c79e', '0.5 kg', 66000, 500::BIGINT),  -- R-FOD-MJS-009  Majes Dog HP Recipe Digestive - Chicken Feast
    ('ttk3d3e84a41ca5365c79e', 'tska6f946125b03cfbe2ef', '1.5 kg', 161500, 1500::BIGINT),  -- R-FOD-MJS-010  Majes Dog HP Recipe Digestive - Chicken Feast
    ('ttk4f493b5b39541107d18', 'tsk4f493b5b39541107d18', '0.5 kg', 66000, 500::BIGINT),  -- R-FOD-MJS-011  Majes Dog HP Recipe Puppy Growth & Immune - Goat Milk
    ('ttk4f493b5b39541107d18', 'tsk444b8222b1acb26d988', '1.5 kg', 161500, 1500::BIGINT),  -- R-FOD-MJS-012  Majes Dog HP Recipe Puppy Growth & Immune - Goat Milk
    ('ttk57714744e072698776b', 'tsk57714744e072698776b', '0.5 kg', 66000, 500::BIGINT),  -- R-FOD-MJS-013  Majes Dog HP Recipe Skin & Coat - Oceanic Salmon Feast
    ('ttk57714744e072698776b', 'tskd233a8e2dd3aa8a46aa', '1.5 kg', 161500, 1500::BIGINT),  -- R-FOD-MJS-014  Majes Dog HP Recipe Skin & Coat - Oceanic Salmon Feast
    ('ttkd2bb206b04f7add5cfe', 'tskd2bb206b04f7add5cfe', '100 g', 23000, 100::BIGINT),  -- R-FOD-MJS-015  Majes Dog Immunity Pack Blood Support - Squab & Foie Gras
    ('ttkde3dfbc87c7f89908ca', 'tskde3dfbc87c7f89908ca', '100 g', 23000, 100::BIGINT),  -- R-FOD-MJS-016  Majes Dog Immunity Pack Coat Health - Goat Milk & Salmon
    ('ttkb97b022752f584a7723', 'tskb97b022752f584a7723', '100 g', 23000, 100::BIGINT),  -- R-FOD-MJS-017  Majes Dog Immunity Pack Digestive - Goat Milk & Chicken
    ('ttk7eeeb61bc725c7a7965', 'tsk7eeeb61bc725c7a7965', '100 g', 23000, 100::BIGINT),  -- R-FOD-MJS-018  Majes Dog Immunity Pack Tear Stain - Duck & Pear
    ('ttk89c72b571654518f837', 'tsk89c72b571654518f837', '170 g', 32500, 170::BIGINT),  -- R-FOD-MJS-019  Majes Dog Nutri Gravy Joint & Immune - Chicken & Beef
    ('ttkf2aacc782051dae3af2', 'tskf2aacc782051dae3af2', '170 g', 32500, 170::BIGINT),  -- R-FOD-MJS-020  Majes Dog Nutri Gravy Skin & Coat - Chicken & Tuna
    ('ttk0029ef1822296055986', 'tsk0029ef1822296055986', '80 g', 50500, 80::BIGINT),  -- R-FOD-MJS-021  Majes Dog Nutri Meat Topper - Tuna
    ('ttk48205896ef043923ccb', 'tsk48205896ef043923ccb', '1.5 kg', 87000, 1500::BIGINT),  -- R-FOD-PDG-001  Pedigree Beef
    ('ttk48205896ef043923ccb', 'tsk77b75f776255b3665a2', '3 kg', 159500, 3000::BIGINT),  -- R-FOD-PDG-002  Pedigree Beef
    ('ttkc0e43179562da6ad3e5', 'tskc0e43179562da6ad3e5', '375 gr', 17500, 375::BIGINT),  -- R-FOD-PRR-001  PERRO WET DOG FOOD BEEF MINCED
    ('ttke505bbf0c713f01adb7', 'tske505bbf0c713f01adb7', '375 gr', 17500, 375::BIGINT),  -- R-FOD-PRR-003  PERRO WET DOG FOOD CHICKEN CHUCKED
    ('ttk8002c24029cce074509', 'tsk8002c24029cce074509', '375 gr', 17500, 375::BIGINT),  -- R-FOD-PRR-002  PERRO WET DOG FOOD CHICKEN MINCED
    ('ttkc87c17af6b77bf7838f', 'tskc87c17af6b77bf7838f', '375 gr', 17500, 375::BIGINT),  -- R-FOD-PRR-004  PERRO WET DOG FOOD LAMB MINCED
    ('ttkd9553c5e3fe7fb9a76f', 'tskd9553c5e3fe7fb9a76f', '500 gr', 86500, 500::BIGINT),  -- R-FOD-RYC-002  Royal Canin X-Small Adult
    ('ttkfa51dcd7678656db539', 'tskfa51dcd7678656db539', '30 ml', 26000, NULL::BIGINT),  -- R-HCS-SBC-011  SEBACARE OBAT SPRAY JAMUR
    ('ttk38e0eca8184dc8ce647', 'tsk38e0eca8184dc8ce647', '30 ml', 26000, NULL::BIGINT),  -- R-HCS-SBC-012  SEBACARE OBAT SPRAY KUTU
    ('ttk05cf76b480a682f2f8f', 'tsk05cf76b480a682f2f8f', '30 ml', 26000, NULL::BIGINT),  -- R-HCS-SBC-013  SEBACARE OBAT SPRAY LUKA
    ('ttkc18bbb3837b00d9c638', 'tskc18bbb3837b00d9c638', '10 ml', 22000, NULL::BIGINT),  -- R-HCS-SBC-003  SEBACARE OBAT TETES CACING
    ('ttkb87c422eb49994315ba', 'tskb87c422eb49994315ba', '10 ml', 22000, NULL::BIGINT),  -- R-HCS-SBC-005  SEBACARE OBAT TETES DEMAM
    ('ttk37479aa5f847594515e', 'tsk37479aa5f847594515e', '10 ml', 22000, NULL::BIGINT),  -- R-HCS-SBC-001  SEBACARE OBAT TETES FLU
    ('ttkd0a7d69e1d2dc353134', 'tskd0a7d69e1d2dc353134', '10 ml', 22000, NULL::BIGINT),  -- R-HCS-SBC-002  SEBACARE OBAT TETES MATA
    ('ttk0ca13dede857212202a', 'tsk0ca13dede857212202a', '10 ml', 22000, NULL::BIGINT),  -- R-HCS-SBC-008  SEBACARE OBAT TETES MUNTAH
    ('ttk16bd918556036b2826a', 'tsk16bd918556036b2826a', '10 ml', 22000, NULL::BIGINT),  -- R-HCS-SBC-009  SEBACARE OBAT TETES PENURUNAN BIRAHI
    ('ttkbe7c818dda84c0abaf0', 'tskbe7c818dda84c0abaf0', '10 ml', 22000, NULL::BIGINT),  -- R-HCS-SBC-010  SEBACARE OBAT TETES RECOVERY
    ('ttk821ab14e96552a823dc', 'tsk821ab14e96552a823dc', '10 ml', 22000, NULL::BIGINT),  -- R-HCS-SBC-007  SEBACARE OBAT TETES SARIAWAN
    ('ttka37958e9654e9d85dfc', 'tska37958e9654e9d85dfc', '10 ml', 22000, NULL::BIGINT),  -- R-HCS-SBC-004  SEBACARE OBAT TETES TELINGA
    ('ttk2e2b9885da20dcb4ce3', 'tsk2e2b9885da20dcb4ce3', '10 ml', 22000, NULL::BIGINT),  -- R-HCS-SBC-006  SEBACARE OBAT TETES URINARY
    ('ttk37efa353cf1863f4c11', 'tsk37efa353cf1863f4c11', '1 pcs', 18000, NULL::BIGINT),  -- R-TOY-NOB-001  Boneka Gigitan Hewan MK-7
    ('ttk8ff1385230e029eca52', 'tsk8ff1385230e029eca52', '1 pcs', 16000, NULL::BIGINT),  -- R-TOY-NOB-002  Boneka Paws MK-4
    ('ttk6b2f85db28eb6c0a340', 'tsk6b2f85db28eb6c0a340', '1 pcs', 46700, NULL::BIGINT),  -- R-TOY-NOB-003  Boneka Tupai
    ('ttk27cba3664db66ebc07e', 'tsk27cba3664db66ebc07e', '1 pcs', 62500, NULL::BIGINT),  -- R-BWF-NOB-001  Bowl Stainless PJ-49
    ('ttkf82c76e1296a6f9fe2a', 'tskf82c76e1296a6f9fe2a', '1 pcs', 54400, NULL::BIGINT),  -- R-TOY-NOB-004  Interaktif pet toy PJ-039
    ('ttkeb9698517ad48434b15', 'tskeb9698517ad48434b15', '1 pcs', 32000, NULL::BIGINT),  -- R-ACC-NOB-001  Keset Anabul- Cat
    ('ttk0593afeb9849442a422', 'tsk0593afeb9849442a422', '1 pcs', 32000, NULL::BIGINT),  -- R-ACC-NOB-002  Keset Anabul-doggy
    ('ttk469ec894dee9b5d0ca1', 'tsk469ec894dee9b5d0ca1', '1 pcs', 58500, NULL::BIGINT),  -- R-TOY-NOB-005  Pet Auto interaktif Feeder PJ-41
    ('ttk09174843e7bb329c2a0', 'tsk09174843e7bb329c2a0', '1 pcs', 66000, NULL::BIGINT),  -- R-BWF-NOB-002  Pet Bowl 1 set PJ 63
    ('ttk5c681be5350f172a385', 'tsk5c681be5350f172a385', '1 pcs', 38500, NULL::BIGINT),  -- R-BWF-NOB-003  Pet Bowl Cake PJ-46
    ('ttked78087cb9c8abe5815', 'tsked78087cb9c8abe5815', '1 pcs', 32500, NULL::BIGINT),  -- R-BWF-NOB-006  Pet Bowl Rabbit PJ-53
    ('ttkeb1ebceaaaa142982cc', 'tskeb1ebceaaaa142982cc', '1 pcs', 120000, NULL::BIGINT),  -- R-BWF-NOB-004  Pet Bowl double set PJ 64
    ('ttkf29b9c3c2ec192740cc', 'tskf29b9c3c2ec192740cc', '1 pcs', 52000, NULL::BIGINT)  -- R-BWF-NOB-005  Pet Bowl foot PJ-59
) AS v(ptok, tok, spec, price, net_g)
JOIN shop_products p ON p.public_token = v.ptok
ON CONFLICT (public_token) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. 库存：取 Excel 的 Stok On Hand 原值（locked 一律 0）
-- ---------------------------------------------------------------------------
INSERT INTO sku_inventory (sku_id, actual, locked)
SELECT k.id, v.actual, 0
FROM (VALUES
    ('tsk11e0e3f2758bb89b4d0', 2),
    ('tsk471bc7c47fe56ae5e25', 1),
    ('tsk4220b35f5e09ed1ea12', 2),
    ('tsk07a11bfc3b079c4154f', 4),
    ('tsk1927568986a5c0d1912', 3),
    ('tskc754d02b8e98f642ed2', 3),
    ('tsk993a6c6620486552f27', 4),
    ('tsk1a424dbc9e2c76a5c20', 3),
    ('tskd750ddb5a7d46981b3a', 4),
    ('tsk8134c4e71c5d23668fd', 3),
    ('tskeb55a65e4e7dded7c2d', 4),
    ('tsk9670ff496c2096960d7', 4),
    ('tsk90ee064381374c90006', 3),
    ('tsk7028d82e386a1fad4c9', 2),
    ('tsk6a5f39f06834fe5a130', 3),
    ('tske342020da2344572ff5', 2),
    ('tsk45bd0d49d8460548f5d', 2),
    ('tskbc636c00744ca3fa8ad', 2),
    ('tsk6048dd21b3ef5854aea', 3),
    ('tskac1cb28be8e02d36e88', 2),
    ('tskb425fda4d89dc2e4807', 2),
    ('tsk378eb93ce57fc44a777', 4),
    ('tsk2fd0da887a846b4469e', 3),
    ('tskf51d632904c1157c2ea', 3),
    ('tsk02fbb3f95d26b9e81c5', 5),
    ('tskf993739550da6e34df5', 6),
    ('tsk631e3ba208cf4c18111', 6),
    ('tsk78a1727fbf07ddea7b2', 6),
    ('tskff067c1fa4b4db56ef9', 6),
    ('tsk25726a90aa3325c2426', 3),
    ('tskbfb783dc7ca6d1bf08e', 1),
    ('tsk1b77b2c84874a45ea25', 5),
    ('tskbf92165f3b37cbfdb6a', 5),
    ('tskeba05311ab26ab7ab2e', 5),
    ('tska29a426c7209fbc8e55', 5),
    ('tsk4e038c294987298e8e7', 6),
    ('tskd1f93d39bfe3c11b5ce', 6),
    ('tsk224b9c94e4c6e9c04fc', 6),
    ('tske3d6e126ebbd6fab2f6', 6),
    ('tsk266a9e0f241c9eb37c3', 4),
    ('tsk290bfa8fc43bb58ed7c', 4),
    ('tskc55cb60f197492f4c6a', 3),
    ('tskad2c546014366578dce', 3),
    ('tsk3d3e84a41ca5365c79e', 3),
    ('tska6f946125b03cfbe2ef', 3),
    ('tsk4f493b5b39541107d18', 3),
    ('tsk444b8222b1acb26d988', 2),
    ('tsk57714744e072698776b', 3),
    ('tskd233a8e2dd3aa8a46aa', 3),
    ('tskd2bb206b04f7add5cfe', 6),
    ('tskde3dfbc87c7f89908ca', 6),
    ('tskb97b022752f584a7723', 6),
    ('tsk7eeeb61bc725c7a7965', 6),
    ('tsk89c72b571654518f837', 4),
    ('tskf2aacc782051dae3af2', 4),
    ('tsk0029ef1822296055986', 4),
    ('tsk48205896ef043923ccb', 2),
    ('tsk77b75f776255b3665a2', 3),
    ('tskc0e43179562da6ad3e5', 3),
    ('tske505bbf0c713f01adb7', 1),
    ('tsk8002c24029cce074509', 1),
    ('tskc87c17af6b77bf7838f', 1),
    ('tskd9553c5e3fe7fb9a76f', 4),
    ('tskfa51dcd7678656db539', 10),
    ('tsk38e0eca8184dc8ce647', 10),
    ('tsk05cf76b480a682f2f8f', 10),
    ('tskc18bbb3837b00d9c638', 10),
    ('tskb87c422eb49994315ba', 10),
    ('tsk37479aa5f847594515e', 10),
    ('tskd0a7d69e1d2dc353134', 10),
    ('tsk0ca13dede857212202a', 10),
    ('tsk16bd918556036b2826a', 10),
    ('tskbe7c818dda84c0abaf0', 10),
    ('tsk821ab14e96552a823dc', 10),
    ('tska37958e9654e9d85dfc', 10),
    ('tsk2e2b9885da20dcb4ce3', 10),
    ('tsk37efa353cf1863f4c11', 18),
    ('tsk8ff1385230e029eca52', 19),
    ('tsk6b2f85db28eb6c0a340', 6),
    ('tsk27cba3664db66ebc07e', 10),
    ('tskf82c76e1296a6f9fe2a', 10),
    ('tskeb9698517ad48434b15', 50),
    ('tsk0593afeb9849442a422', 49),
    ('tsk469ec894dee9b5d0ca1', 10),
    ('tsk09174843e7bb329c2a0', 10),
    ('tsk5c681be5350f172a385', 10),
    ('tsked78087cb9c8abe5815', 10),
    ('tskeb1ebceaaaa142982cc', 10),
    ('tskf29b9c3c2ec192740cc', 9)
) AS v(tok, actual)
JOIN shop_skus k ON k.public_token = v.tok
WHERE NOT EXISTS (SELECT 1 FROM sku_inventory i WHERE i.sku_id = k.id);

-- ---------------------------------------------------------------------------
-- 4. 采购入库流水
--
-- 🔴 不能只 INSERT sku_inventory 就完事：退货质检通过时要把货以「退货入库批次」入库，
--    而入库要回查该 SKU 的采购单价 —— 没有任何 PURCHASE_INBOUND 记录的 SKU 会直接抛
--    「该 SKU 尚无采购入库记录」，事务回滚，退货单永远卡在 INSPECTING。
--
-- ⚠️ cost_price 是 **售价 ÷ 2 的占位值**（Excel 没有成本，见头注 7）。
-- ---------------------------------------------------------------------------
INSERT INTO inventory_movements
    (sku_id, movement_type, qty_delta, actual_before, actual_after,
     reason, purchase_no, supplier, cost_price, inbound_date, operator_account_id)
SELECT k.id, 'PURCHASE_INBOUND', i.actual, 0, i.actual,
       '门店在库盘点导入（Stok Produk On Hand 2026-08-10）', 'STOK-20260810', 'Tailtopia Cabang Utama',
       k.price / 2, DATE '2026-08-10',
       COALESCE((SELECT id FROM admin_accounts
                 WHERE account_type = 'SUPER_ADMIN' AND status = 'ACTIVE'
                 ORDER BY id LIMIT 1), 1)
FROM shop_skus k
JOIN sku_inventory i ON i.sku_id = k.id
WHERE k.public_token LIKE 'tsk%'
  AND i.actual > 0
  AND NOT EXISTS (SELECT 1 FROM inventory_movements m
                  WHERE m.sku_id = k.id AND m.movement_type = 'PURCHASE_INBOUND');

-- ---------------------------------------------------------------------------
-- 5. 核对（期望值来自 Excel：84 商品 / 89 SKU / 578 件 / Rp 20.225.700）
-- ---------------------------------------------------------------------------
SELECT '商品（应为 84）' AS what, count(*)::text AS n FROM shop_products WHERE public_token LIKE 'ttk%'
UNION ALL SELECT 'SKU（应为 89）', count(*)::text FROM shop_skus WHERE public_token LIKE 'tsk%'
UNION ALL SELECT '已上架商品（应为 84）', count(*)::text FROM shop_products
       WHERE public_token LIKE 'ttk%' AND is_active
UNION ALL SELECT '库存总件数（应为 578）', COALESCE(sum(i.actual),0)::text
       FROM sku_inventory i JOIN shop_skus k ON k.id = i.sku_id WHERE k.public_token LIKE 'tsk%'
UNION ALL SELECT '库存市值（应为 20225700）', COALESCE(sum(i.actual * k.price),0)::text
       FROM sku_inventory i JOIN shop_skus k ON k.id = i.sku_id WHERE k.public_token LIKE 'tsk%'
UNION ALL SELECT '低库存 SKU（actual<=5）', count(*)::text
       FROM sku_inventory i JOIN shop_skus k ON k.id = i.sku_id
       WHERE k.public_token LIKE 'tsk%' AND i.actual - i.locked BETWEEN 1 AND 5
UNION ALL SELECT '有采购入库流水的 SKU（应为 89）', count(DISTINCT m.sku_id)::text
       FROM inventory_movements m JOIN shop_skus k ON k.id = m.sku_id
       WHERE k.public_token LIKE 'tsk%' AND m.movement_type = 'PURCHASE_INBOUND'
UNION ALL SELECT '⚠️ 缺喂量的 MAKANAN（预期全部，见头注 6）', count(*)::text FROM shop_products
       WHERE public_token LIKE 'ttk%' AND category = 'MAKANAN' AND feeding_guide IS NULL;

SELECT category AS "品类", count(*) AS "商品数" FROM shop_products
WHERE public_token LIKE 'ttk%' GROUP BY category ORDER BY category;

