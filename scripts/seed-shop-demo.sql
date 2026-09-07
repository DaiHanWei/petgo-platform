-- ============================================================================
-- V1.4.0 电商 · staging 验收用演示数据
--
-- 🔴🔴 这【不是】Flyway 迁移，**绝不能放进 db/migration** —— 放进去就会在生产上跑。
--     它是一个手动执行的脚本，只给 staging / 本地 用。
--
-- 为什么需要它：staging 是从生产完整克隆的，而电商是全新模块 ——
-- 克隆过来的库里**一件商品都没有**。没有商品，验收清单 B 节的 10 个页面
-- 一个都点不动（Toko 空、加不了购物车、下不了单、发不了货、退不了货）。
-- 真实商品数据归 DEP-6（Rendy），但**验收不该等它** —— 这份演示数据先把路铺通。
--
-- 覆盖到：四个品类各有商品 · MAKANAN 带结构化喂量（Epic 6 复购引擎的唯一输入）
--         · 三种退货规则各有样本 · 有货/低库存/售罄三态各有样本 · 配送范围与运费
--
-- 用法：
--   docker exec -i <pg容器> psql -U petgo -d petgo_stag -v ON_ERROR_STOP=1 < scripts/seed-shop-demo.sql
--
-- 幂等：可重复执行（按 public_token 冲突跳过）。
-- 清理：DELETE FROM shop_products WHERE public_token LIKE 'demo-%';（级联带走 SKU 与库存）
-- ============================================================================

\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------------
-- 🔒 安全闸：不在生产库上跑
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF current_database() = 'petgo' AND
       coalesce(current_setting('petgo.allow_demo_seed', true), '') <> 'yes' THEN
        RAISE EXCEPTION
            '拒绝执行：当前库是 % —— 这是演示数据脚本，不该进生产。'
            '确实要在本地 petgo 库上跑，请先 SET petgo.allow_demo_seed = ''yes'';',
            current_database();
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 1. 配送范围与运费（🔴 不配这个，一单都发不出去）
-- ---------------------------------------------------------------------------
INSERT INTO shipping_zones (kecamatan, kota_kabupaten, provinsi, fee, active) VALUES
    ('Kebayoran Baru',  'Jakarta Selatan', 'DKI Jakarta',  15000, true),
    ('Tebet',           'Jakarta Selatan', 'DKI Jakarta',  15000, true),
    ('Menteng',         'Jakarta Pusat',   'DKI Jakarta',  18000, true),
    ('Cilandak',        'Jakarta Selatan', 'DKI Jakarta',  15000, true),
    ('Serpong',         'Tangerang Selatan', 'Banten',     25000, true)
ON CONFLICT DO NOTHING;

-- 满额包邮门槛（0 = 不包邮；这里给个能被演示单跨过的值，方便验「包邮」那一档文案）
INSERT INTO shipping_settings (id, free_shipping_threshold) VALUES (1, 300000)
ON CONFLICT (id) DO UPDATE SET free_shipping_threshold = EXCLUDED.free_shipping_threshold;

-- ---------------------------------------------------------------------------
-- 2. 商品 + SKU + 库存
--
-- ⚠️ main_image_key 是 OSS objectKey，不是 URL。这里填的是占位 key ——
--    验收时图位会是空的/占位图，**这是预期**，不是 bug。
--    要看真图请让运营从后台上传，或把 key 换成 staging OSS 里真实存在的对象。
-- ---------------------------------------------------------------------------
WITH p AS (
    INSERT INTO shop_products
        (public_token, name, brand, category, main_image_key, species, body_size, age_stage,
         detail_html, feeding_guide, shelf_life_note, return_policy, sort_weight, is_active)
    VALUES
    -- ── MAKANAN（🔴 带结构化喂量 —— Epic 6 粮量见底预估的唯一计算依据）
    ('demo-mkn-01', 'Royal Canin Adult Dog', 'Royal Canin', 'MAKANAN', 'demo/mkn-01.jpg',
     'DOG', 'MEDIUM', 'ADULT', '<p>Makanan kering untuk anjing dewasa.</p>',
     '[{"weightMinKg":1,"weightMaxKg":5,"gramsPerDay":80},
       {"weightMinKg":5,"weightMaxKg":10,"gramsPerDay":140},
       {"weightMinKg":10,"weightMaxKg":25,"gramsPerDay":260}]'::jsonb,
     'Simpan di tempat kering, 18 bulan.', 'NO_RETURN_AFTER_OPEN', 100, true),

    ('demo-mkn-02', 'Whiskas Adult Cat', 'Whiskas', 'MAKANAN', 'demo/mkn-02.jpg',
     'CAT', 'UNIVERSAL', 'ADULT', '<p>Makanan kering untuk kucing dewasa.</p>',
     '[{"weightMinKg":1,"weightMaxKg":4,"gramsPerDay":55},
       {"weightMinKg":4,"weightMaxKg":8,"gramsPerDay":95}]'::jsonb,
     'Simpan di tempat kering, 18 bulan.', 'NO_RETURN_AFTER_OPEN', 90, true),

    ('demo-mkn-03', 'Pro Plan Puppy', 'Pro Plan', 'MAKANAN', 'demo/mkn-03.jpg',
     'DOG', 'SMALL', 'PUPPY', '<p>Makanan untuk anak anjing.</p>',
     '[{"weightMinKg":1,"weightMaxKg":3,"gramsPerDay":70},
       {"weightMinKg":3,"weightMaxKg":6,"gramsPerDay":120}]'::jsonb,
     'Simpan di tempat kering, 12 bulan.', 'NO_RETURN_AFTER_OPEN', 80, true),

    -- ── OBAT_VITAMIN（🔴 FR-110 品类跳转的落点就是这一档）
    ('demo-obt-01', 'Drontal Plus Obat Cacing', 'Bayer', 'OBAT_VITAMIN', 'demo/obt-01.jpg',
     'DOG', 'UNIVERSAL', 'UNIVERSAL', '<p>Obat cacing untuk anjing.</p>', NULL,
     'Simpan di suhu ruang, 24 bulan.', 'NON_RETURNABLE', 70, true),

    ('demo-obt-02', 'Vitamin Bulu & Kulit', 'NutriPet', 'OBAT_VITAMIN', 'demo/obt-02.jpg',
     'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL', '<p>Suplemen harian.</p>', NULL,
     'Simpan di suhu ruang, 24 bulan.', 'NO_RETURN_AFTER_OPEN', 60, true),

    -- ── CAMILAN
    ('demo-cml-01', 'Dentastix Snack', 'Pedigree', 'CAMILAN', 'demo/cml-01.jpg',
     'DOG', 'UNIVERSAL', 'ADULT', '<p>Camilan pembersih gigi.</p>', NULL,
     'Simpan di tempat kering, 12 bulan.', 'NO_RETURN_AFTER_OPEN', 50, true),

    -- ── PERAWATAN（可退，用来验「可退」这一档）
    ('demo-prw-01', 'Shampoo Anti Kutu', 'PetClean', 'PERAWATAN', 'demo/prw-01.jpg',
     'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL', '<p>Sampo anti kutu.</p>', NULL,
     'Simpan di suhu ruang, 24 bulan.', 'RETURNABLE', 40, true),

    ('demo-prw-02', 'Sisir Grooming Stainless', 'PetClean', 'PERAWATAN', 'demo/prw-02.jpg',
     'UNIVERSAL', 'UNIVERSAL', 'UNIVERSAL', '<p>Sisir grooming.</p>', NULL,
     'Tidak ada masa simpan.', 'RETURNABLE', 30, true)
    ON CONFLICT (public_token) DO NOTHING
    RETURNING id, public_token
)
INSERT INTO shop_skus (public_token, product_id, spec_name, price, net_weight_g, cost_price, return_policy)
SELECT s.tok, p.id, s.spec, s.price, s.net_g, s.cost, NULL
FROM p JOIN (VALUES
    -- (商品 token, SKU token, 规格, 售价, 净含量克, 进货价)
    ('demo-mkn-01','demo-sku-mkn-01a','3 kg',  185000, 3000, 128000),
    ('demo-mkn-01','demo-sku-mkn-01b','8 kg',  420000, 8000, 295000),
    ('demo-mkn-02','demo-sku-mkn-02a','1.2 kg', 78000, 1200,  52000),
    ('demo-mkn-02','demo-sku-mkn-02b','3 kg',  168000, 3000, 118000),
    ('demo-mkn-03','demo-sku-mkn-03a','2.5 kg',215000, 2500, 155000),
    ('demo-obt-01','demo-sku-obt-01a','1 tablet',35000,   NULL, 21000),
    ('demo-obt-01','demo-sku-obt-01b','6 tablet',180000,  NULL,112000),
    ('demo-obt-02','demo-sku-obt-02a','60 kapsul',95000,  NULL, 61000),
    ('demo-cml-01','demo-sku-cml-01a','7 stick', 42000,  180,  26000),
    ('demo-prw-01','demo-sku-prw-01a','250 ml',  68000,  NULL, 41000),
    ('demo-prw-02','demo-sku-prw-02a','Standar', 55000,  NULL, 32000)
) AS s(ptok, tok, spec, price, net_g, cost) ON s.ptok = p.public_token
ON CONFLICT (public_token) DO NOTHING;

-- 库存：🔴 有意造出【有货 / 低库存 / 售罄】三态，三种空态文案才都验得到。
--       低库存阈值默认 5（petgo.shop.low-stock-threshold）。
INSERT INTO sku_inventory (sku_id, actual, locked)
SELECT k.id, v.actual, 0
FROM shop_skus k JOIN (VALUES
    ('demo-sku-mkn-01a', 120), ('demo-sku-mkn-01b',  60),
    ('demo-sku-mkn-02a',  80), ('demo-sku-mkn-02b',   3),   -- ← 低库存
    ('demo-sku-mkn-03a',  45),
    ('demo-sku-obt-01a', 200), ('demo-sku-obt-01b',  30),
    ('demo-sku-obt-02a',   0),                              -- ← 售罄（🔴 售罄不自动下架）
    ('demo-sku-cml-01a', 150),
    ('demo-sku-prw-01a',  70), ('demo-sku-prw-02a',   2)    -- ← 低库存
) AS v(tok, actual) ON v.tok = k.public_token
WHERE NOT EXISTS (SELECT 1 FROM sku_inventory i WHERE i.sku_id = k.id);

-- ---------------------------------------------------------------------------
-- 2b. 采购入库流水
--
-- 🔴 **不能只 INSERT sku_inventory 就完事**（2026-08-18 本地全流程验收撞到的）：
--    退货质检通过时要把货以「退货入库批次」入库，而入库要回查该 SKU 的**采购单价** ——
--    没有任何 PURCHASE_INBOUND 记录的 SKU 会直接抛「该 SKU 尚无采购入库记录」，
--    事务回滚、退货单永远卡在 INSPECTING。
--    换句话说：少了这一段，**整条退货链在 staging 上根本走不完**。
--
-- ⚠️ operator_account_id 必须是**真实存在**的 ACTIVE 超管。
--    🔴 2026-08-19 修：原先写的是 `COALESCE(..., 1)` —— 「没有就退回 1」。
--    那个假设在**干净库上不成立**（全新 staging 克隆 / 刚迁移完的本地库都可能一个管理员都没有），
--    结果是外键报错 `operator_account_id_fkey`，信息里完全看不出「你得先建管理员」。
--    现改为前置断言：缺管理员就直接给出可执行的提示，而不是抛一个要人反查的约束错。
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM admin_accounts
                   WHERE account_type = 'SUPER_ADMIN' AND status = 'ACTIVE') THEN
        RAISE WARNING '%', E'\n'
            '⚠️  没有 ACTIVE 的 SUPER_ADMIN → 跳过采购入库流水。\n'
            '    商品 / SKU / 库存照常灌入，但【退货质检会卡在 INSPECTING】，\n'
            '    因为质检入库要回查该 SKU 的采购单价。\n'
            '    建好超管账号后重跑本脚本即可补上（脚本幂等）。';
    END IF;
END $$;

INSERT INTO inventory_movements
    (sku_id, movement_type, qty_delta, actual_before, actual_after,
     reason, purchase_no, supplier, cost_price, inbound_date, operator_account_id)
SELECT k.id, 'PURCHASE_INBOUND', i.actual, 0, i.actual,
       '演示数据初始入库', 'DEMO-PO-001', 'Demo Supplier',
       COALESCE(k.cost_price, k.price / 2), CURRENT_DATE,
       (SELECT id FROM admin_accounts
        WHERE account_type = 'SUPER_ADMIN' AND status = 'ACTIVE'
        ORDER BY id LIMIT 1)
FROM shop_skus k
JOIN sku_inventory i ON i.sku_id = k.id
WHERE k.public_token LIKE 'demo-%'
  AND i.actual > 0
  -- 🔴 没有超管就整段不插（上面的 WARNING 已说明后果）。
  --    切勿退回一个写死的 id —— 那会变成一个要人反查的外键报错。
  AND EXISTS (SELECT 1 FROM admin_accounts
              WHERE account_type = 'SUPER_ADMIN' AND status = 'ACTIVE')
  AND NOT EXISTS (SELECT 1 FROM inventory_movements m
                  WHERE m.sku_id = k.id AND m.movement_type = 'PURCHASE_INBOUND');

-- ---------------------------------------------------------------------------
-- 3. 核对
-- ---------------------------------------------------------------------------
SELECT '商品' AS what, count(*) AS n FROM shop_products WHERE public_token LIKE 'demo-%'
UNION ALL SELECT 'SKU', count(*) FROM shop_skus WHERE public_token LIKE 'demo-%'
UNION ALL SELECT '有库存记录的 SKU',
       count(*) FROM sku_inventory i JOIN shop_skus k ON k.id = i.sku_id
       WHERE k.public_token LIKE 'demo-%'
UNION ALL SELECT '售罄 SKU（应为 1）',
       count(*) FROM sku_inventory i JOIN shop_skus k ON k.id = i.sku_id
       WHERE k.public_token LIKE 'demo-%' AND i.actual - i.locked <= 0
UNION ALL SELECT '低库存 SKU（应为 2）',
       count(*) FROM sku_inventory i JOIN shop_skus k ON k.id = i.sku_id
       WHERE k.public_token LIKE 'demo-%' AND i.actual - i.locked BETWEEN 1 AND 5
UNION ALL SELECT '带喂量的 MAKANAN（应为 3）',
       count(*) FROM shop_products
       WHERE public_token LIKE 'demo-%' AND category = 'MAKANAN' AND feeding_guide IS NOT NULL
UNION ALL SELECT '配送 Kecamatan', count(*) FROM shipping_zones WHERE active
UNION ALL SELECT '🔴 有采购入库记录的 SKU（缺了退货质检会卡死）',
       count(DISTINCT m.sku_id) FROM inventory_movements m JOIN shop_skus k ON k.id = m.sku_id
       WHERE k.public_token LIKE 'demo-%' AND m.movement_type = 'PURCHASE_INBOUND';
