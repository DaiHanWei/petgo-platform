# Department Data Summary

# Department Data Needs

**Prepared by:** Albar \(Product\)
**Date:** 11 Nov 2026
**Departments:** Ecommerce, Content Operations, Marketing, Product
**Purpose:** the data each department needs, with a definition for each item, for the dev team building the data AI that teams will ask for data\.
**Sources:**

- Documents from the departments: Content Ops Weekly Report \(26 Oct – 8 Nov 2026\), Albar's OKR Self\-Report \(Nov–Dec 2026\), Weekly PostHog Report \(5–11 Oct 2026\), Cohort Drop\-off Analysis \(16 Oct 2026\)

- Personal chats with each department \(Ecommerce, Content Operations, Marketing\)

- Lark group "Tailtopia Data AI"

---

## 0\. Common rules \(apply to every item\)

|Rule|Definition|
|---|---|
|Time zone|Asia/Jakarta \(WIB\)\. A "day" is a WIB calendar day\. A "week" is Monday–Sunday\.|
|Production data only|Exclude staging app versions, emulators, TestFlight, sideloaded apps, non\-production server events, internal staff, and admin, virtual or test accounts\.|
|User counts|Always count unique users\. Daily unique counts can't be added up across days\.|
|Payments and orders|Count only transactions and orders with status PAID\.|

---

## 1\. Shared data \(needed by more than one department\)

|Code|Data|Definition|Needed by|
|---|---|---|---|
|S1|DAU|DAU = unique users with the `Application Opened` event on that day\. Also: weekly total = sum of the daily DAU; average = weekly total ÷ number of days; highest and lowest daily DAU; growth = \(this week − last week\) ÷ last week; average DAU Monday–Friday vs Saturday–Sunday\.|Content Ops, Ecommerce, Product|
|S2|Installs|Unique users with their first `Application Installed` in the period\. Reported as overall installs, Android installs and iOS installs \(Android \+ iOS = overall\)\.|Marketing, Product|
|S3|Registrations and registration rate|Registrations = unique users with their first `signup_succeeded` in the period\. Registration rate = registrations ÷ installs \(S2\), same period\.|Marketing, Product|
|S4|Ecommerce transactions \(% of DAU\)|Each day: unique users with a paid Toko order ÷ DAU \(S1\)\. For a month: the average of the daily values\.|Ecommerce, Product|
|S5|Pop\-up, banner and push notification results|Pop\-ups and banners, for each one: times shown, taps, tap rate \(taps ÷ times shown\); for those that promote Toko, also paid Toko orders within 7 days of a tap\. Push notifications: number sent in the period; for each one: recipients, number opened, open rate \(opened ÷ recipients\)\. Ecommerce needs the ones that promote Toko; Content Ops needs all of them\.|Ecommerce, Content Ops|
|S6|Revenue by type \(IDR\)|Total of PAID transactions minus refunds, split by type: Toko order, KTP unlock, Slot rent, Tailsonality, PawCoin top\-up, AI consultation, vet consultation, other paid features\. Toko revenue = the Toko order part\. In\-app revenue = every type except Toko\. Total company revenue = Toko \+ in\-app\.|Ecommerce \(Toko\), Content Ops \(in\-app\)|

**Similar names, different metrics\.** Keep these separate:

- **Pet profile coverage** \(Content Ops \#3, ÷ all registered users\) vs **% of installs that create a pet profile** \(Product \#1, ÷ the month's installs\)\.

- **D1 / D3 / D7 retention** \(Marketing \#1–3\) vs **new\-user W1 retention** \(Product \#5\) vs **returning users** \(Content Ops \#1\)\.

---

## 2\. Ecommerce \(Toko / shop\)

Scope: Toko \(the in\-app shop\) only\. Other in\-app payments \(KTP, Tailsonality, PawCoin, consultations\) are not part of Ecommerce\.
Also uses shared data: **S1, S4, S5 \(Toko only\), S6 \(Toko revenue\)**\.

|\#|Data|Definition|
|---|---|---|
|1|Paid Toko orders|Number of Toko orders with status PAID, counted by payment date|
|2|Toko GMV \(IDR\)|Total value of paid Toko orders, including shipping, after discounts\.|
|3|Average ecommerce spend per buyer \(IDR\)|Toko GMV ÷ unique users with at least one paid Toko order, same period|
|4|Unique users opening Toko|Unique users who viewed the Toko tab \(`$screen` = `toko_page`\) in the period|
|5|Add\-to\-cart → paid order conversion|Unique users who paid for a Toko order within 7 days of an add\-to\-cart \(`toko_add_to_cart_tapped`\) ÷ unique users who added to cart in the period|
|6|Toko analytics funnel|Unique users at each step, in order, within 7 days: Toko tab viewed \(`toko_page`\) → product shown in the list \(`toko_product_shown`\) → product detail viewed \(`toko_product_detail_viewed`\) → buy now or add to cart \(`toko_buy_now_tapped` / `toko_add_to_cart_tapped`\) → order submitted \(`toko_order_submitted`\) → pay tapped \(`toko_order_pay_tapped`\) → paid\. Conversion = users at a step ÷ users at the previous step\.|
|7|Cart contents|Total for the whole period, used to see how long products stay in carts and why they don't convert\. For each product added to cart \(`toko_add_to_cart_tapped`\) in the period: which product, by which user, and when\. Time in cart = from add\-to\-cart until the product is bought or removed; if it is still in the cart, until the end of the period\. Each product is marked bought, removed or still in cart\. Totals for the period: users who added to cart, products per cart, and total cart value \(price × quantity\)\.|
|8|Top 3 products with high potential|The 3 products with the most product\-detail views \+ add\-to\-cart taps in the period|
|9|Sold\-out products with high relevance|Products with 0 stock, ranked by actual sales \(paid units\) \+ product\-detail views in the last 30 days|
|10|Toko coupons|Number of coupons issued and number used|

## 3\. Content Operations

Also uses shared data: **S1, S5 \(all\), S6 \(in\-app revenue\)**\.

|\#|Data|Definition|
|---|---|---|
|1|New vs returning users|New = users who registered that day\. Returning = DAU \(S1\) − new users on the same day\.|
|2|Total registered users|All registered accounts up to the date, excluding admin, virtual and test accounts|
|3|Pet profile coverage|Users with at least one pet profile ÷ total registered users, up to the date|
|4|ID card coverage|For each card type \(KTP, Student Card, Passport, KITAS, SIM\): users who own at least one card of that type ÷ total registered users\. Also: users who own at least one card of any type\.|
|5|Tailsonality coverage|Users who completed Tailsonality ÷ total registered users|
|6|In\-app transactions per day|Number of PAID transactions each day for each type: KTP unlock, Slot rent, Tailsonality|
|7|Content published|Posts published by official accounts \(e\.g\. TailUs\) in the period|
|8|Weekly top 3 posts|Real users only\. The 3 posts by real users with the most likes \+ comments received during the week\. Posts by official, seeded/virtual, admin and test accounts are excluded\.|

## 4\. Marketing

Also uses shared data: **S2, S3**\.

|\#|Data|Definition|
|---|---|---|
|1|D1 retention|Counted from install\. Of the users who installed the app on day X \(first `Application Installed`\), the % who open it again \(`Application Opened`\) on day X\+1\. For a week: the average across that week's daily install groups, weighted by group size\.|
|2|D3 retention|Same as D1, but the user opens the app again on day X\+3\. Only users who installed at least 3 days ago are counted\.|
|3|D7 retention|Same as D1, but the user opens the app again on day X\+7\. Only users who installed at least 7 days ago are counted\.|

## 5\. Product

Also uses shared data: **S1, S2, S3 \(registration rate, monthly\), S4**\.

|\#|Data|Definition|
|---|---|---|
|1|% of installs that create a pet profile|Unique users with `pet_profile_create_submitted` ÷ unique users with `Application Installed` \(S2\), same month|
|2|Toko payment success rate|Paid Toko orders \(Ecommerce \#1\) ÷ submitted Toko orders \(`toko_order_submitted`\)|
|3|Paid user rate \(% of DAU\), excluding ecommerce|Each day: unique users with a paid unlock \(KTP, Tailsonality and other non\-Toko features\) ÷ DAU \(S1\)\. For a month: the average of the daily values\.|
|4|Average KTP \+ Tailsonality spend per paying user \(IDR\)|\(KTP revenue \+ Tailsonality revenue, from S6\) ÷ unique users who paid for KTP or Tailsonality, same month|
|5|New\-user W1 retention|% of users who complete onboarding and open the app again \(`Application Opened`\) on any day 7–13 after onboarding|

