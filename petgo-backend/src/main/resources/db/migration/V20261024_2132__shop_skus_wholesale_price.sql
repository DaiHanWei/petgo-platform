-- V1.4.1 商家平台对接（tailtopia-contract.md 第 2 节）：SKU 批发价，ops 商品编辑页可填。
-- 门店进货按它计价；返佣差价 = 下单单价 − 批发价。NULL = 不对门店供货（商家平台不展示该规格）。
-- IDR 最小单位整型，同 price / cost_price。
ALTER TABLE shop_skus
    ADD COLUMN wholesale_price BIGINT NULL
        CONSTRAINT ck_shop_skus_wholesale_price CHECK (wholesale_price >= 0);
