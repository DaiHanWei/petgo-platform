-- V1.3.0 shop-v2 Story 4-1（SHOP-FR-04 · AD-S6 · SD-6）：购物车行选择位。时间戳版本号（决策 E7）。
--
-- 全栈上下此前都没有「行选择」这个概念：CheckoutService.placeOrder 无条件遍历 cart.lines()，
-- 建行、锁库存、清车全在同一个循环里。「只买两件」在服务端根本没有入口，
-- 所以 App 只能整车下单，误加的东西必须先删掉才能买别的。
--
-- 🔴 **DEFAULT TRUE 不是随便挑的默认值，是老版本兼容的全部依据**（SHOP-NFR-04）：
--    线上老版本 App 从不调选择端点，它看到的每一行都必须是选中态，
--    预览金额、运费、抵扣、订单行、库存锁定、清车范围都与本次改动前逐项一致。
--    存量行也因此全部回填为 TRUE —— 没有人「取消过勾选」，默认全选才是对既有事实的如实表达。
--
-- 🔴 勾选位**不参与失效判定**：勾了一个已售罄的行不是错误，它只是不会被计入选中合计
--    （失效判定读时进行、不落库，见 CartService.view）。
ALTER TABLE shop_cart_items
    ADD COLUMN selected BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN shop_cart_items.selected IS
    '是否勾选结算（Story 4-1 / SHOP-FR-04）。DEFAULT TRUE = 老版本行为：从不调选择端点的客户端全程整车结算。仅「selected=true 且读时未失效」的行进入结算、下单、锁库存与清车范围。';
