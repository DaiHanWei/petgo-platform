#!/bin/bash
# V1.4.0 第 2/3 批上机验收 —— 为 dev 用户(id=1) 造出 7 屏所需数据。
# 下单/支付/收货走真实 API（保证不变量），仅「发货」与「日期回溯」用 SQL（无管理员口令）。
set -uo pipefail
API=http://localhost:8081
PG() { docker exec petgo-pg psql -U petgo -d petgo -tAq -c "$1"; }
j() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d$1)" 2>/dev/null; }

echo "== 0. 清掉上一轮 user1 的订单"
PG "delete from shop_orders where user_id=1;" >/dev/null
PG "delete from repurchase_triggers where user_id=1;" >/dev/null
PG "delete from pawcoin_transactions where user_id=1;" >/dev/null

echo "== 1. 登录"
TOK=$(curl -s -X POST $API/api/v1/auth/google -H 'Content-Type: application/json' \
  -d '{"idToken":"dev-stub-id-token"}' | j "['accessToken']")
[ -z "$TOK" ] && { echo "登录失败"; exit 1; }
AUTH="Authorization: Bearer $TOK"; echo "   ok"

echo "== 2. 宠物档案（Miko · 猫 · 3.4kg → Whiskas 1-4kg 档 = 55g/天）"
curl -s -X POST $API/api/v1/pet-profiles -H "$AUTH" -H 'Content-Type: application/json' -d '{
  "petType":"CAT","name":"Miko","breed":"Domestik","birthday":"2025-12-19",
  "intro":"Suka tidur di jendela","weightKg":3.4,"neuterStatus":"NEUTERED"}' -o /dev/null -w '   HTTP %{http_code}（409=已存在，可接受）\n'
PET_ID=$(PG "select id from pet_profiles where owner_id=1")
echo "   pet_profile_id=$PET_ID"

echo "== 3. PawCoin：余额 30 万，单笔封顶 5 万 → 每单必为 MIXED（设计稿的两段拆分）"
PG "INSERT INTO pawcoin_wallets (user_id,balance,version,updated_at) VALUES (1,300000,0,now())
    ON CONFLICT (user_id) DO UPDATE SET balance=300000;" >/dev/null
PG "UPDATE shop_pawcoin_rules SET max_coin_per_order=50000 WHERE id=1;" >/dev/null
echo "   balance=$(PG "select balance from pawcoin_wallets where user_id=1") cap=$(PG "select max_coin_per_order from shop_pawcoin_rules where id=1")"

echo "== 4. 收货地址"
ADDR=$(curl -s -H "$AUTH" $API/api/v1/me/shipping-addresses | j "[0]['token']")
if [ -z "$ADDR" ]; then
  ADDR=$(curl -s -X POST $API/api/v1/me/shipping-addresses -H "$AUTH" -H 'Content-Type: application/json' -d '{
    "receiverName":"Aurel Pratiwi","receiverPhone":"081234567890","provinsi":"DKI Jakarta",
    "kotaKabupaten":"Jakarta Selatan","kecamatan":"Kebayoran Baru",
    "addressLine":"Jl. Senopati No. 12 Blok B","kodePos":"12190","label":"Rumah"}' | j "['token']")
fi
echo "   address=$ADDR"

place() { # $1=skuToken $2=qty  -> orderToken
  for it in $(curl -s -H "$AUTH" $API/api/v1/me/cart | python3 -c "
import sys,json
for i in json.load(sys.stdin).get('items',[]): print(i.get('skuToken',''))" 2>/dev/null); do
    [ -n "$it" ] && curl -s -X DELETE "$API/api/v1/me/cart/items/$it" -H "$AUTH" -o /dev/null
  done
  curl -s -X POST "$API/api/v1/me/cart/items?skuToken=$1&qty=$2" -H "$AUTH" -o /dev/null
  curl -s -X POST $API/api/v1/me/shop-orders -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"addressToken\":\"$ADDR\"}" | j "['orderToken']"
}

paid() { # $1=orderToken $2=idemKey  -- 发起支付 + stub 网关回调结清现金段
  local intent
  intent=$(curl -s -X POST $API/api/v1/me/shop-orders/$1/pay -H "$AUTH" -H "Idempotency-Key: $2" | j "['paymentIntentToken']")
  if [ -n "$intent" ] && [ "$intent" != "None" ]; then
    curl -s -X POST $API/pay/callback -H 'Content-Type: application/x-www-form-urlencoded' \
      --data "order_id=$intent&transaction_id=stub-$intent&transaction_status=settlement" -o /dev/null
  fi
  echo "   $1 -> $(PG "select status from shop_orders where public_token='$1'")"
}

echo "== 5. 订单 A · 待支付（Whiskas 1.2kg x2）"
OA=$(place demo-sku-mkn-02a 2); echo "   A=$OA"

echo "== 6. 订单 B · 已发货（Shampoo Anti Kutu）"
OB=$(place demo-sku-prw-01a 1); paid $OB seed-b

echo "== 7. 订单 C · 已签收可退（Shampoo，RETURNABLE）"
OC=$(place demo-sku-prw-01a 1); paid $OC seed-c

echo "== 8. 订单 D · 复购触发源（Whiskas 1.2kg x1）"
OD=$(place demo-sku-mkn-02a 1); paid $OD seed-d

echo "== 9. 发货（SQL：无管理员口令）"
PG "
UPDATE shop_orders SET status='SHIPPED', shipped_at=now()-interval '2 day' WHERE public_token='$OB';
INSERT INTO shipments (shop_order_id,carrier,tracking_no,carrier_cost,status,shipped_at)
SELECT id,'JNE','JNE0093281746',15000,'SHIPPED',now()-interval '2 day' FROM shop_orders WHERE public_token='$OB';
UPDATE shop_orders SET status='SHIPPED', shipped_at=now()-interval '4 day' WHERE public_token='$OC';
INSERT INTO shipments (shop_order_id,carrier,tracking_no,carrier_cost,status,shipped_at)
SELECT id,'SICEPAT','SC7741920038',15000,'SHIPPED',now()-interval '4 day' FROM shop_orders WHERE public_token='$OC';
UPDATE shop_orders SET status='SHIPPED', shipped_at=now()-interval '18 day' WHERE public_token='$OD';
INSERT INTO shipments (shop_order_id,carrier,tracking_no,carrier_cost,status,shipped_at)
SELECT id,'ANTERAJA','AJ5520117743',15000,'SHIPPED',now()-interval '18 day' FROM shop_orders WHERE public_token='$OD';
" >/dev/null

echo "== 10. 用户确认收货（真实接口 -> DELIVERED + 开退货窗口）"
for t in $OC $OD; do
  curl -s -X POST $API/api/v1/me/shop-orders/$t/confirm-receipt -H "$AUTH" -o /dev/null -w "   $t confirm HTTP %{http_code}\n"
done

echo "== 11. D 单送达日回溯 16 天（1200g / 55g每天 = 21 天 -> 耗尽 = 今天+5，落进 7 天触发窗口）"
PG "UPDATE shop_orders SET delivered_at=now()-interval '16 day' WHERE public_token='$OD';
    UPDATE shipments SET delivered_at=now()-interval '16 day'
      WHERE shop_order_id=(select id from shop_orders where public_token='$OD');" >/dev/null

echo "== 12. 复购触发记录"
PG "INSERT INTO repurchase_triggers (user_id,pet_profile_id,sku_id,source_order_id,trigger_type,status,
      estimated_depletion_date,created_at,updated_at)
    SELECT 1,$PET_ID,s.id,o.id,'FOOD_LOW','ACTIVE',(current_date+5),now(),now()
    FROM shop_skus s, shop_orders o
    WHERE s.public_token='demo-sku-mkn-02a' AND o.public_token='$OD';" >/dev/null

echo "== 13. 核对"
echo "-- 订单（token | 状态 | 渠道 | 币 | 现金 | 总额）"
PG "select public_token||' | '||status||' | '||coalesce(pay_channel,'-')||' | '||coalesce(coin_amount,0)||' | '||coalesce(cash_amount,0)||' | '||total_amount from shop_orders where user_id=1 order by id"
echo "-- 触发卡 API"; curl -s -H "$AUTH" $API/api/v1/me/shop/repurchase-cards | python3 -m json.tool 2>/dev/null | head -30
echo "-- 退货资格（C 单）"; curl -s -H "$AUTH" $API/api/v1/me/shop-orders/$OC/return-eligibility | head -c 400; echo
echo
echo "TOKENS: A_待支付=$OA  B_已发货=$OB  C_可退=$OC  D_复购源=$OD"
