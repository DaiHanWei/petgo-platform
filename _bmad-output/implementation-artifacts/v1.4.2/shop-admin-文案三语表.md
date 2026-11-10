# V1.4.2 电商后台 · 文案三语表（定稿）

> 从 `planning-artifacts/v1.4.2/电商/ui-admin-1-4-2-shop-integrated-v1.html` 末节抽取，2026-11-06。**以本表为定稿文案**，写进 `messages_zh_CN.properties` / `messages_en.properties` / `messages_id.properties`（`messages.properties` 基础文件同步）。表中标「不可复用」的同字词条要新建 key；(b) 列的线上词条按表改写或废弃。

来源：admin_screens1/2/3.py、admin_lib.py（只取屏内渲染文案，不含 caption）；对照 origin/stag 的 messages_zh_CN / en / id.properties。

已有词条原样出现的不再列（如 状态、操作、编辑、停用、保存、全部、查询、待发货、已发货、退款中、已取消、PawCoin 段、现金段、QRIS 段、订单行、部分取消、整单取消并退款、联系用户后继续、处置原因（会一并告知用户）、标记缺货、缺货说明、标记整单已送达、标记该包裹送达、可配送区域、免运门槛、退货收件地址、保存门槛、上架商品、SKU 总数、规格与价格、新增/编辑规格、新增 Banner、Banner 图（单张，≤10MB）、生效中、未上架、队列已清空 🎉、从左侧队列选一条开始处理、「{0}」还有 {1} 条 →、对账页说明、分享奖励卡全部原有字段、运营配置确认标题 / 新旧值行 / 开关 等）。例外：字面已存在但本版含义不同的，单独列出并在「参考词条」注明「不可复用」。

变量统一写成 {占位}；印尼语沿用后台 messages_id 的中性书面语（需要人称时用 Anda），金额 Rp + 点分千位，小数用逗号（2,4:1）。

专有名词不译：PawCoin、Biteship、QRIS、BCA、OVO、GoPay、Toko、Tailsonality、Kecamatan、EXP（沿用 admin.v140.users.tab.growth）。飞书统一译 Lark（沿用 admin.flash.account.emailRebound）。

App 端同版本稿（ui-1-4-2-shop-integrated-v1.html）已定的印尼语用词一并对齐：Kupon、Diskon belanja、Gratis ongkir、Kupon pengguna baru、Klaim kupon、kode kupon、minimum belanja、Asuransi pengiriman、Kode pos、Ulasan、Pengiriman bermasalah、Menunggu dijemput、rekening penerima、Nama pemilik rekening、Pilih cara pengembalian dana、dibuat ulang。


## 侧栏

| 屏号 | 位置 | 中文 | English | Bahasa Indonesia | 参考词条 |
|---|---|---|---|---|---|
| 全部 | 🛍 商城菜单项（新，放「订单履约」前） | 订单 | Orders | Pesanan | admin.common.order（zh 已有，但菜单需新 key，如 admin.nav.shopOrderList） |
| 全部 | 🛍 商城菜单项（新，放「退货审核」后）；E 页面标题 / 抽屉标题 | 退款打款 | Refund payouts | Pencairan refund | admin.refundDetail.section.payout（Pencairan）、admin.audit.action.REFUND_PAYOUT_RECORDED |
| 全部 | 🛍 商城菜单项（新，放「顶部 Banner」后）；A 页面标题；A11 用户详情页签 | 优惠券 | Coupons | Kupon | App 1.4.2 稿「Kupon」 |

## A 优惠券

| 屏号 | 位置 | 中文 | English | Bahasa Indonesia | 参考词条 |
|---|---|---|---|---|---|
| A1 | 页头说明 | 券的一切参数由运营配置，App 端不写死任何一张券。已开始发放的券只能改总量（只增不减）与停用。 | All coupon settings are configured here by ops; the app hard-codes no coupon. Once a coupon has started issuing, you can only increase its total quantity or disable it. | Semua parameter kupon diatur oleh tim operasional di sini; aplikasi tidak menanamkan kupon apa pun secara tetap. Kupon yang sudah mulai diterbitkan hanya bisa ditambah kuota totalnya atau dinonaktifkan. | — |
| A1 | 页头按钮 | ＋ 新建优惠券 | + New coupon | + Kupon baru | admin.config.tier.newButton |
| A1/A3 | 筛选项 / 列头 / 表单字段 | 发放方式 | Issue method | Cara penerbitan | — |
| A1 | 摘要格 | 占用中 | Reserved | Dicadangkan | — |
| A1 | 摘要格 | 本月带来订单金额 | Order value from coupons this month | Nilai pesanan dari kupon bulan ini | admin.common.amount |
| A1/A3 | 列头 / 表单字段 | 券名称 | Coupon name | Nama kupon | — |
| A1 | 列头 | 面额 / 门槛 | Discount / Min. spend | Potongan / Min. belanja | App 稿「minimum belanja」 |
| A1/A3 | 列头 / 表单字段 | 发放期 | Issue period | Periode penerbitan | — |
| A1/A3 | 列头小字 / 表单字段 | 使用有效期 | Validity | Masa berlaku | — |
| A1 | 列头 | 已发 / 总量 | Issued / Total | Diterbitkan / Total | — |
| A1/A11 | 列头 / 用户券状态 | 已使用 | Used | Terpakai | — |
| A1/A3 | 类型值 / 券类型选项 | 满减 | Spend discount | Diskon belanja | App 稿「Diskon belanja」 |
| A1/A3 | 类型值 / 券类型选项 | 免运 | Free shipping | Gratis ongkir | admin.shop.shipping.section.threshold（gratis ongkir） |
| A1 | 面额单元格（满减） | Rp {discount} / 满 Rp {minSpend} | Rp {discount} / min. Rp {minSpend} | Rp {discount} / min. belanja Rp {minSpend} | — |
| A1 | 面额单元格（免运） | 最高减 Rp {cap} / 满 Rp {minSpend} | Up to Rp {cap} off / min. Rp {minSpend} | Maks. Rp {cap} / min. belanja Rp {minSpend} | App 稿「Potongan ongkir maksimal」 |
| A1/A3 | 发放方式值 / 选项 | 新人自动发放 | Auto-issued to new users | Otomatis untuk pengguna baru | App 稿「Kupon pengguna baru」 |
| A1/A3 | 发放方式值 / 选项 | 用户领取 | Claimed by users | Diklaim pengguna | App 稿「Klaim kupon」 |
| A1/A5/A11 | 发放方式值 / 字段名 / 获得方式值 | 兑换码 | Coupon code | Kode kupon | App 稿「Masukkan kode kupon」 |
| A1/A3 | 有效期小字 / 使用有效期单选 | 领取后 {days} 天内有效 | Valid for {days} days after claiming | Berlaku {days} hari setelah diklaim | — |
| A1/A7 | 总量单元格值 | 不限 | Unlimited | Tanpa batas | admin.common.none（zh 同字但译 Any/Semua，不可复用） |
| A1/A3 | 券名小字 / 适用范围选项 | 指定品类：{category} | Category: {category} | Kategori: {category} | admin.shop.col.category |
| A1/A5 | 券名小字 / 兑换码单选 | 通用码 | Shared code | Kode bersama | — |
| A2 | 空态 | 还没有优惠券。新人券、领取券、兑换码都要先在这里建券才会出现在 App。 | No coupons yet. New-user coupons, claimable coupons and coupon codes only appear in the app after they are created here. | Belum ada kupon. Kupon pengguna baru, kupon klaim, dan kode kupon baru muncul di aplikasi setelah dibuat di sini. | admin.shop.banners.empty 句式 |
| A3 | 抽屉标题 | 新建优惠券 | New coupon | Kupon baru | admin.config.tier.newTitle |
| A3 | 表单字段 | 券类型 | Coupon type | Jenis kupon | admin.roles.col.type（Jenis） |
| A3 | 表单字段 | 减免金额 | Discount amount | Nilai potongan | — |
| A3/A4 | 表单字段 | 门槛金额 | Minimum spend | Minimum belanja | App 稿 |
| A3 | 发放期下提示 | 能领取、能兑换、新人券能发放的时间 | When the coupon can be claimed, redeemed, or (for new-user coupons) issued | Periode kupon bisa diklaim, ditukar, atau (untuk kupon pengguna baru) diberikan | — |
| A3 | 使用有效期单选 | 固定起止日期 | Fixed start and end dates | Tanggal mulai dan berakhir tetap | — |
| A3 | 表单字段 | 发放总量 | Total quantity | Kuota total | — |
| A3 | 表单字段 | 每人限领 | Limit per user | Batas per pengguna | — |
| A3 | 表单字段 | 适用范围 | Applies to | Berlaku untuk | — |
| A3 | 适用范围选项 | 全场 | All products | Semua produk | — |
| A4/A6 | 表单字段（免运券） | 最高减免金额 | Maximum discount | Potongan maksimal | App 稿「Potongan ongkir maksimal」 |
| A4 | 最高减免下提示 | 减免额取本单最低快递运费与此上限中较小者 | The discount is the lower of the order's cheapest courier fee and this cap | Potongan diambil dari nilai terkecil antara ongkir kurir termurah pesanan dan batas ini | — |
| A4 | 适用范围下提示 | 本单全部商品都在范围内才可用 | Usable only when every product in the order is in scope | Hanya bisa dipakai bila semua produk dalam pesanan termasuk cakupan | — |
| A5 | 通用码说明 | 所有用户输同一个码；发放总量 = 全部用户合计可兑换张数 | All users enter the same code; total quantity = coupons redeemable by all users combined | Semua pengguna memasukkan kode yang sama; kuota total = jumlah kupon yang bisa ditukar seluruh pengguna | — |
| A5 | 兑换码单选 | 批量生成一次性码 | Generate single-use codes in bulk | Buat kode sekali pakai secara massal | — |
| A5 | 一次性码说明 | 每个码只能兑换一张，生成数量即发放总量；生成后可导出 | Each code redeems one coupon; the number generated is the total quantity. Codes can be exported once generated. | Setiap kode hanya untuk satu kupon; jumlah yang dibuat = kuota total. Kode bisa diekspor setelah dibuat. | — |
| A6 | 校验错误 | 同一时间只能有一张新人券在发放期内：「{name}」发放期 {start} – {end} 与本券重叠。 | Only one new-user coupon can be in its issue period at a time: "{name}" ({start} – {end}) overlaps this coupon. | Hanya satu kupon pengguna baru yang boleh berada dalam periode penerbitan pada saat yang sama: periode "{name}" ({start} – {end}) tumpang tindih dengan kupon ini. | admin.err.pins.overlap（tumpang tindih） |
| A6 | 校验错误 | 最高减免金额必填。 | Maximum discount is required. | Potongan maksimal wajib diisi. | admin.shop.inventory.purchase.hint（wajib diisi） |
| A7 | 确认弹窗标题 | 确认保存这张券？ | Save this coupon? | Simpan kupon ini? | — |
| A7 | 确认弹窗警示 | 面额 ≥ 门槛：用户等于免费拿走商品 | Discount ≥ minimum spend: users effectively get the products for free | Potongan ≥ minimum belanja: pengguna praktis mendapat produk gratis | — |
| A7 | 确认弹窗正文 | 减免金额 {discount} ≥ 门槛 {minSpend}。 | Discount {discount} ≥ minimum spend {minSpend}. | Potongan {discount} ≥ minimum belanja {minSpend}. | — |
| A7 | 确认弹窗警示 | 发放总量不限，且每人不限领 | Unlimited total quantity and no per-user limit | Kuota total tanpa batas dan tanpa batas per pengguna | — |
| A7 | 确认弹窗正文 | 确认这是有意配置后再保存。 | Save only if this is intended. | Simpan hanya jika memang disengaja. | admin.config.appVersion.confirm.minDownNote |
| A7 | 确认按钮 | 仍要保存 | Save anyway | Tetap simpan | — |
| A8 | 抽屉标题 | 编辑优惠券 | Edit coupon | Ubah kupon | admin.shop.form.titleEdit（Ubah） |
| A8 | 抽屉顶部警示 | 这张券已开始发放：只能增加发放总量或停用，面额、门槛、适用范围不可改。 | This coupon has started issuing: you can only increase the total quantity or disable it. Discount, minimum spend and scope cannot be changed. | Kupon ini sudah mulai diterbitkan: hanya kuota total yang bisa ditambah, atau kupon dinonaktifkan. Potongan, minimum belanja, dan cakupan tidak bisa diubah. | — |
| A8 | 发放总量下提示 | 当前 {total}，已发 {issued}；只能增加 | Currently {total}, {issued} issued; can only be increased | Saat ini {total}, sudah diterbitkan {issued}; hanya bisa ditambah | — |
| A9 | 确认弹窗标题 | 停用「{name}」？ | Disable "{name}"? | Nonaktifkan "{name}"? | admin.v130.shopShipping.confirmDisable |
| A9 | 确认弹窗正文 | 停用后不能再领取或兑换。用户已领取、未使用的 {count} 张怎么处理： | Once disabled, it can no longer be claimed or redeemed. What should happen to the {count} coupons users have claimed but not used? | Setelah dinonaktifkan, kupon tidak bisa diklaim atau ditukar lagi. Bagaimana dengan {count} kupon yang sudah diklaim pengguna tetapi belum dipakai? | — |
| A9 | 单选 | 继续可用 | Keep usable | Tetap bisa dipakai | — |
| A9 | 单选说明 | 用户手里的券到期前照常可用 | Coupons users already hold stay usable until they expire | Kupon yang sudah dimiliki pengguna tetap bisa dipakai sampai kedaluwarsa | — |
| A9 | 单选 | 作废 | Void | Hanguskan | — |
| A9 | 单选说明 | 变为「已失效」，App 端不再显示，不通知用户；已被待支付订单占用的 {count} 张仍可用于那一单 | They become "Voided" and disappear from the app; users are not notified. The {count} coupons already reserved by unpaid orders can still be used on those orders. | Statusnya menjadi "Tidak berlaku" dan tidak lagi tampil di aplikasi; pengguna tidak diberi tahu. {count} kupon yang sudah dicadangkan pesanan belum dibayar tetap bisa dipakai untuk pesanan tersebut. | — |
| A10 | 抽屉标题 | 券详情 | Coupon details | Detail kupon | admin.common.detail |
| A10 | 数据格 | 已领取 | Claimed | Diklaim | — |
| A10 | 数据格 | 占用中（待支付订单） | Reserved (unpaid orders) | Dicadangkan (pesanan belum dibayar) | — |
| A10 | 数据格 | 带来的订单金额 | Order value from this coupon | Nilai pesanan dari kupon ini | — |
| A10 | 吸底按钮（批量码） | 导出兑换码 | Export codes | Ekspor kode kupon | — |
| A11 | 列头 | 券 | Coupon | Kupon | — |
| A11 | 列头 | 获得方式 | Obtained via | Didapat dari | — |
| A11 | 列头 | 领取时间 | Claimed at | Waktu klaim | admin.shop.orders.col.createdAt 句式 |
| A11 | 列头 | 有效期至 | Valid until | Berlaku sampai | — |
| A11 | 获得方式值 | 新人 | New user | Pengguna baru | — |
| A11 | 获得方式值 | 领取 | Claimed | Klaim | — |
| A11 | 获得方式值 | banner | Banner | Banner | — |
| A11 | 券名小字 | 新人券 | New-user coupon | Kupon pengguna baru | App 稿 |
| A11 | 券名（满减，自动生成） | 满 {minSpend} 减 {discount} | {discount} off min. {minSpend} | Diskon {discount} min. belanja {minSpend} | — |
| A11 | 券名（免运，自动生成） | 免运 最高 {cap} | Free shipping up to {cap} | Gratis ongkir maks. {cap} | — |
| A11 | 用户券状态 | 被待支付订单占用 | Reserved by an unpaid order | Dicadangkan pesanan belum dibayar | — |
| A11 | 用户券状态 | 可用 | Available | Bisa dipakai | — |
| A11 | 用户券状态 | 已失效 | Voided | Tidak berlaku | App 稿「Kupon sudah tidak berlaku」 |
| A11 | 状态记录标题 | {coupon} · 状态变化 | {coupon} · Status history | {coupon} · Riwayat status | — |
| A11 | 状态记录 | 兑换获得 | Obtained with code | Didapat lewat kode | — |
| A11 | 状态记录 | 被 {orderNo} 占用 | Reserved by {orderNo} | Dicadangkan oleh {orderNo} | — |
| A11 | 状态记录 | 订单超时取消，退回为可用 | Order timed out and was cancelled; coupon available again | Pesanan dibatalkan karena waktu habis; kupon bisa dipakai lagi | — |
| A11 | 状态记录 | 付款，已使用 | Paid; coupon used | Dibayar; kupon terpakai | — |
| A12 | 空态 | 该用户还没有优惠券。 | This user has no coupons yet. | Pengguna ini belum punya kupon. | admin.v140.growth.ledger.empty |
| A13 | 页签顶部警示 | 兑换已锁定，{minutes} 分钟后解除（1 小时内输错兑换码 10 次）。客服不能手动解锁。 | Code redemption is locked; it unlocks in {minutes} min (10 wrong codes within 1 hour). Support cannot unlock it manually. | Penukaran kode dikunci dan terbuka lagi dalam {minutes} menit (10 kali salah kode dalam 1 jam). Layanan pelanggan tidak bisa membukanya secara manual. | admin.config.supportContact（layanan pelanggan） |

## B Banner

| 屏号 | 位置 | 中文 | English | Bahasa Indonesia | 参考词条 |
|---|---|---|---|---|---|
| B1 | 页头说明（改写） | 所有「已上架且在排期内」的 banner 都会在 App 展示，最多 6 张，按列表顺序轮播；拖动行调整顺序。跳转目标失效的 banner 在 App 端自动不再展示。 | Every banner that is active and within its schedule is shown in the app, up to 6, rotating in list order; drag rows to reorder. Banners whose tap target is no longer valid are hidden in the app automatically. | Semua banner yang aktif dan berada dalam jadwal tampil di aplikasi, maksimal 6, bergantian sesuai urutan daftar; seret baris untuk mengubah urutan. Banner dengan tujuan ketuk yang tidak berlaku lagi otomatis tidak ditampilkan di aplikasi. | 替换 admin.shop.banners.hint |
| B1 | 列头 | 排期 | Schedule | Jadwal | admin.schedule.title |
| B1 | 排期单元格结束值 | 不限 | No end | Tanpa akhir | admin.common.none（zh 同字不同义，不可复用） |
| B1/B3 | 点击跳转类型（新选项）/ 列表值前缀「优惠券 · {name}」 | 优惠券（领券） | Coupon (claim) | Kupon (klaim) | admin.shop.banners.target.* |
| B1 | 跳转单元格红字 | 品类跳转已下线，请改选 | Category links are retired; choose another | Tautan kategori sudah dihentikan; pilih yang lain | admin.tags.retired（retire） |
| B1 | 状态 | 目标失效 | Target invalid | Tujuan tidak berlaku | — |
| B1 | 状态（取代「已上架（被更高权重压住）」） | 超出展示上限 | Over display limit | Melebihi batas tampil | — |
| B3/B4/B5 | 抽屉标题（编辑态，修 bug） | 编辑 Banner | Edit banner | Ubah banner | admin.shop.form.titleEdit（Ubah） |
| B3 | 上传框下说明（改写） | 建议尺寸 1056 × 444 px（App 显示尺寸 352 × 148 的 3 倍，约 2.4:1），JPG / PNG。图的四角会切成圆角，右下角叠轮播页码点：文字和重点内容离边缘留出约 60 px，右下角留空。多张轮播按同一比例显示，比例不同的图会被裁切。可直接 Ctrl+V 粘贴。 | Recommended size 1056 × 444 px (3× the app display size of 352 × 148, about 2.4:1), JPG / PNG. Corners are rounded and the carousel page dots sit at the bottom right: keep text and key content about 60 px from the edges and leave the bottom-right corner empty. All carousel banners display at the same ratio; images with a different ratio are cropped. Or paste it with Ctrl+V. | Ukuran disarankan 1056 × 444 px (3× ukuran tampil di aplikasi 352 × 148, sekitar 2,4:1), JPG / PNG. Sudut gambar dibuat membulat dan titik halaman carousel ada di kanan bawah: beri jarak teks dan konten utama sekitar 60 px dari tepi, dan kosongkan sudut kanan bawah. Semua banner dalam carousel tampil dengan rasio yang sama; gambar dengan rasio berbeda akan terpotong. Bisa juga tempel dengan Ctrl+V. | 替换 admin.shop.banners.form.image.hint（末句沿用） |
| B3/B6 | 上传后状态 | 已上传 | Uploaded | Terunggah | — |
| B3 | 上传框旁尺寸 | {width} × {height} · 约 {ratio} | {width} × {height} · about {ratio} | {width} × {height} · sekitar {ratio} | admin.shop.banners.col.size |
| B3 | 优惠券字段提示 | 只能选发放方式为「用户领取」的券；新人券与兑换码券不能点击领取。 | Only coupons issued as "Claimed by users" can be selected; new-user coupons and coupon-code coupons cannot be claimed by tapping. | Hanya kupon dengan cara penerbitan "Diklaim pengguna" yang bisa dipilih; kupon pengguna baru dan kupon berkode tidak bisa diklaim lewat ketuk. | — |
| B3 | 表单字段 | 上线时间 | Start time | Waktu mulai tayang | admin.shop.banners.live（Tayang） |
| B3 | 表单字段 | 下线时间 | End time | Waktu berhenti tayang | — |
| B3 | 下线时间占位 | 不填 = 不下线 | Blank = no end | Kosongkan = tayang terus | admin.config.shareRewardMonthlyCap「0 = 不发」句式 |
| B4 | 抽屉顶部警示 | 「品类」跳转已随 App 品类筛选下线，App 端点击会落到 Toko 首页。请改选其他跳转类型后保存。 | The "Category" tap action was retired along with the app's category filter; tapping now lands on the Toko home. Choose another tap action before saving. | Aksi ketuk "Kategori" sudah dihentikan bersama filter kategori di aplikasi; ketukan kini membuka beranda Toko. Pilih aksi ketuk lain sebelum menyimpan. | admin.shop.banners.col.target（Tap action / Aksi ketuk） |
| B5 | 保存失败 | 跳转目标无效：这张券的发放期已结束。 | Invalid tap target: this coupon's issue period has ended. | Tujuan ketuk tidak berlaku: periode penerbitan kupon ini sudah berakhir. | — |
| B6 | 上传后比例警示 | 这张图是 {width} × {height}（{ratio}），比 App 的 banner 高。App 会按约 2.4:1 显示，上下会被裁掉。建议换图，仍可保存。 | This image is {width} × {height} ({ratio}), taller than the app banner. The app shows banners at about 2.4:1, so the top and bottom will be cropped. Consider replacing it; you can still save. | Gambar ini {width} × {height} ({ratio}), lebih tinggi dari banner aplikasi. Aplikasi menampilkan banner sekitar 2,4:1, jadi bagian atas dan bawah akan terpotong. Sebaiknya ganti gambar; tetap bisa disimpan. | — |
| B6 | 上传后比例警示（宽图变体，稿未画） | 这张图是 {width} × {height}（{ratio}），比 App 的 banner 宽。App 会按约 2.4:1 显示，左右会被裁掉。建议换图，仍可保存。 | This image is {width} × {height} ({ratio}), wider than the app banner. The app shows banners at about 2.4:1, so the left and right will be cropped. Consider replacing it; you can still save. | Gambar ini {width} × {height} ({ratio}), lebih lebar dari banner aplikasi. Aplikasi menampilkan banner sekitar 2,4:1, jadi bagian kiri dan kanan akan terpotong. Sebaiknya ganti gambar; tetap bisa disimpan. | — |

## C 分享奖励

| 屏号 | 位置 | 中文 | English | Bahasa Indonesia | 参考词条 |
|---|---|---|---|---|---|
| C1 | 卡片顶部常驻警示 | 分享奖励发放的 PawCoin 是真钱，请谨慎设置金额。 | PawCoin granted as share rewards is real money. Set amounts carefully. | PawCoin dari hadiah berbagi adalah uang sungguhan. Atur jumlahnya dengan hati-hati. | admin.config.shareReward |
| C2 | 确认弹窗标题 | 确认修改分享奖励配置？ | Confirm share reward changes? | Konfirmasi perubahan hadiah berbagi? | admin.config.appVersion.confirm.title |
| C2 | 确认弹窗警示 | 分享奖励的 PawCoin 是真钱。 | Share-reward PawCoin is real money. | PawCoin hadiah berbagi adalah uang sungguhan. | — |
| C2 | 估算行标签 | 每个账号每月最多可获得 | Max per account per month | Maksimal per akun per bulan | admin.config.shareReward.atCap（akun） |
| C2 | 估算行说明 | 取月度上限 {cap} 与「四种卡每次发放 × 每日次数 × 30 天 = {sum}」中较小者。改动立即生效。 | The lower of the monthly cap {cap} and "koin per share × daily limit × 30 days across the four cards = {sum}". Changes take effect immediately. | Nilai terkecil antara batas bulanan {cap} dan "koin per bagi × batas harian × 30 hari untuk keempat kartu = {sum}". Perubahan langsung berlaku. | admin.config.shareReward.monthlyCap |

## D 异常订单（含 D0 待办中心入口）

| 屏号 | 位置 | 中文 | English | Bahasa Indonesia | 参考词条 |
|---|---|---|---|---|---|
| D0 | 待办中心组末尾小标题（下挂「异常订单 ↗」「退款打款 ↗」） | 商城待办 | Shop tasks | Tugas toko | admin.group.inbox（Pusat tugas）、admin.group.shop（Toko） |
| D1 | 页头说明（改写） | 本页列出需要人工处置的订单：缺货、运单失败、揽收失败、配送异常（退回、丢件破损）。整单取消或联系用户后继续履约后，这一单就离开本页（部分取消会保留，方便逐行处理）。处置记录在操作审计里。 | This page lists orders that need manual handling: shortage, waybill failed, pickup failed, and delivery problems (returned, lost or damaged). After a full cancellation or "contact user and continue" the order leaves the page (a partial cancellation keeps it, so you can work line by line). Handling is recorded in the audit log. | Halaman ini menampilkan pesanan yang perlu ditangani manual: kurang stok, resi gagal, penjemputan gagal, dan pengiriman bermasalah (dikembalikan, hilang atau rusak). Setelah dibatalkan seluruhnya atau "hubungi pengguna lalu lanjut", pesanan keluar dari halaman ini (pembatalan sebagian tetap menyimpannya agar bisa ditangani per baris). Penanganan tercatat di log audit. | 替换 admin.v130.shopExceptions.liveHint |
| D1 | 队列来源徽标 | 缺货 | Shortage | Kurang stok | admin.v130.shopOrders.shortage.btn |
| D1/F1/H1 | 队列徽标 / 异常标记 / 订单号旁徽标 | 运单失败 | Waybill failed | Resi gagal | admin.shop.order.ship.hint（waybill / resi） |
| D1/F1/H1 | 同上 | 揽收失败 | Pickup failed | Penjemputan gagal | App 稿「Menunggu dijemput」 |
| D1/F1/H1/H8 | 同上（包裹被退回寄件人） | 退回 | Returned | Dikembalikan | — |
| D1/F1 | 同上 | 丢件破损 | Lost or damaged | Hilang atau rusak | — |
| D1 | 队列 meta 前缀 / 异常原因卡标题前缀 | 配送异常 | Delivery problem | Pengiriman bermasalah | App 稿「Pengiriman bermasalah」 |
| D4 | 异常原因卡（退回） | 原因：{reasonCategory}（{note}）· 判定于 {date} · 退回商品已确认入库 {receivedDate} | Reason: {reasonCategory} ({note}) · decided {date} · returned goods received {receivedDate} | Alasan: {reasonCategory} ({note}) · diputuskan {date} · barang kembali sudah masuk gudang {receivedDate} | admin.v130.shopExceptions.flag.at |
| D4 | 异常原因卡（退回未入库） | 退回商品尚未确认入库 | Returned goods not yet confirmed as received | Barang kembali belum dikonfirmasi masuk gudang | — |
| D5 | 异常原因卡（丢件） | 未购买保价 | No shipping insurance | Tanpa asuransi pengiriman | App 稿「Tanpa asuransi」 |
| D1 | 处置卡小标题 | 整单取消 · 原因类别 | Cancel whole order · Reason category | Batalkan seluruh pesanan · Kategori alasan | admin.shop.exceptions.btn.cancelWhole |
| D3 | 确认弹窗行 | 原因类别：{category} | Reason category: {category} | Kategori alasan: {category} | — |
| D1/D7 | 原因类别单选 | 平台原因 | Platform fault | Kesalahan platform | admin.shop.returns.quote.compensationWhy |
| D1 | 单选说明 | 缺货、运单失败、揽收失败、快递原因的配送异常 | Shortage, waybill failed, pickup failed, or a courier-caused delivery problem | Kurang stok, resi gagal, penjemputan gagal, atau masalah pengiriman karena kurir | — |
| D1/D7/H8 | 原因类别单选 | 用户原因 | User fault | Kesalahan pengguna | — |
| D1 | 单选说明 | 地址错、联系不上、拒收 | Wrong address, unreachable, or refused | Alamat salah, tidak bisa dihubungi, atau ditolak | admin.v130.shopReturns.type.REFUSED_ON_DELIVERY |
| D1 | 单选说明（丢件破损） | 运营已标丢件或破损 | Ops has flagged it lost or damaged | Tim operasional sudah menandai hilang atau rusak | — |
| D1 | 处置卡小标题 | 退款明细 | Refund breakdown | Rincian refund | admin.shop.returns.section.quote |
| D1/D3/D6 | 明细合计行 | 退款合计 | Refund total | Total refund | admin.shop.recon.col.refundTotal |
| D1 | 明细值 | {amount}（全额） | {amount} (full) | {amount} (penuh) | — |
| D1 | 明细值（PawCoin 段） | {amount} · 立即退回 | {amount} · returned immediately | {amount} · langsung dikembalikan | admin.v130.shopExceptions.confirm.cancelWhole |
| D1 | 明细值（现金段） | {amount} · 等用户选去向 | {amount} · waiting for the user to choose where it goes | {amount} · menunggu pengguna memilih tujuan dana | admin.shop.returns.col.cashDestination |
| D1 | 明细行 | 转 PawCoin 补偿 | Convert-to-PawCoin compensation | Kompensasi ubah jadi PawCoin | admin.shop.returns.quote.incentive；App 稿「Ubah jadi PawCoin」 |
| D1 | 明细值 | 用户把现金段选为退为 PawCoin 时，按平台责任补偿配置另发 | Granted separately per the platform-fault compensation setting if the user takes the cash segment as PawCoin | Diberikan terpisah sesuai pengaturan kompensasi kesalahan platform bila pengguna memilih bagian tunai dikembalikan sebagai PawCoin | admin.shop.returns.quote.compensation |
| D1 | 明细值（优惠券行，券退回给用户） | 退回 | Returned | Dikembalikan | 与包裹「退回」同字不同义，建议分 key |
| D1 | 明细行 | 库存 | Stock | Stok | admin.nav.shopInventory |
| D1 | 明细值 | 回补（货还在仓库） | Restocked (goods are still in the warehouse) | Dikembalikan ke stok (barang masih di gudang) | — |
| D4 | 明细行 | 应付金额 | Amount payable | Total tagihan | — |
| D4 | 明细行 | 扣除用户实付运费 | Less shipping paid by user | Dikurangi ongkir yang dibayar pengguna | admin.shop.order.col.shippingFee |
| D4 | 明细行 | 扣除保价费 | Less insurance fee | Dikurangi biaya asuransi | — |
| D4/D5 | 明细行 | 补偿 · 优惠券 | Compensation · Coupon | Kompensasi · Kupon | — |
| D4 | 明细值 | 不给 · 不退 | None · Not returned | Tidak ada · Tidak dikembalikan | — |
| D4 | 明细值（库存） | 不自动回补（已经入库入口回补） | Not restocked automatically (restocked via the return inbound entry) | Tidak otomatis kembali ke stok (sudah masuk lewat entri retur masuk) | admin.shop.inventory.return.title |
| D5 | 明细行 | 商品实付上限 | Cap: goods amount paid | Batas: nilai barang yang dibayar | — |
| D5 | 明细值 | {amount}（应付 − 实付运费 − 保价费） | {amount} (payable − shipping paid − insurance fee) | {amount} (tagihan − ongkir dibayar − biaya asuransi) | — |
| D5 | 明细行（输入框） | 协商退款金额 | Agreed refund amount | Jumlah refund yang disepakati | — |
| D5 | 输入框旁提示 | 未购买保价，填与用户协商的金额 | No insurance was bought; enter the amount agreed with the user | Tidak ada asuransi; isi jumlah yang disepakati dengan pengguna | — |
| D5 | 明细值 | 给 · 退回 | Given · Returned | Diberikan · Dikembalikan | — |
| D5 | 明细值（库存） | 不回补 | Not restocked | Tidak kembali ke stok | — |
| D1 | 「联系用户后继续」旁输入占位 | 处置原因 | Reason | Alasan | admin.shop.exceptions.reason.placeholder |
| D3 | 确认弹窗标题（改写） | 确认整单取消并退款？ | Cancel the whole order and refund? | Batalkan seluruh pesanan dan kembalikan dana? | 拆自 admin.v130.shopExceptions.confirm.cancelWhole |
| D3 | 确认弹窗 PawCoin 段注 | 即时退回 | Returned immediately | Langsung dikembalikan | — |
| D3 | 确认弹窗现金段注 | 等用户在 App 选择去向 | User chooses where it goes in the app | Pengguna memilih tujuan dana di aplikasi | — |
| D3 | 确认弹窗正文 | 优惠券退回，库存回补。 | The coupon is returned and stock is restocked. | Kupon dikembalikan dan stok dipulihkan. | — |
| D4 | 错误横幅 | 退回商品尚未确认入库，不能提交用户原因的取消。请先在「退货审核」的入库入口确认收到退回商品。 | The returned goods have not been confirmed as received, so a user-fault cancellation cannot be submitted. First confirm receipt at the inbound entry in "Return review". | Barang kembali belum dikonfirmasi masuk gudang, jadi pembatalan karena kesalahan pengguna belum bisa diajukan. Konfirmasi dulu penerimaannya di entri barang masuk pada "Tinjau retur". | admin.nav.shopReturns |
| D6 | 内联表单标题 | 部分取消 · {product} | Cancel part · {product} | Batalkan sebagian · {product} | admin.shop.exceptions.btn.cancelLine |
| D6 | 内联表单字段 | 取消数量 | Quantity to cancel | Jumlah dibatalkan | admin.shop.inventory.col.qty |
| D6 | 拆分明细行 | 该行实付 | Paid for this line | Dibayar untuk baris ini | — |
| D6 | 拆分明细值 | {amount}（已按满减券分摊） | {amount} (spend-discount coupon pro-rated) | {amount} (sudah dikurangi porsi kupon diskon belanja) | — |
| D6 | 拆分明细值（PawCoin 段） | {amount} · 立即退回（按原订单支付比例，向下取整） | {amount} · returned immediately (pro-rated by the original payment split, rounded down) | {amount} · langsung dikembalikan (sesuai proporsi pembayaran awal, dibulatkan ke bawah) | — |
| D6 | 拆分明细值（现金段） | {amount} · 零头算在现金，等用户选去向 | {amount} · includes the rounding remainder; waiting for the user to choose where it goes | {amount} · termasuk sisa pembulatan; menunggu pengguna memilih tujuan dana | — |
| D6 | 拆分明细行 | 运费 · 保价 · 券 | Shipping · Insurance · Coupon | Ongkir · Asuransi · Kupon | — |
| D6 | 拆分明细值 | 不退 | Not refunded | Tidak dikembalikan | — |
| D6 | 确认弹窗标题（改写） | 确认取消「{product}」{qty} 件？ | Cancel {qty} of "{product}"? | Batalkan {qty} unit "{product}"? | 拆自 admin.v130.shopExceptions.confirm.cancelLine |
| D6 | 确认弹窗现金段注 | 等用户选择去向 | Waiting for the user to choose | Menunggu pilihan pengguna | — |
| D6 | 确认弹窗正文 | 运费、保价费与优惠券不退。库存会按退货入库批次回补，并告知用户。 | Shipping, insurance fee and coupon are not refunded. Stock is put back as a return inbound batch and the user is notified. | Ongkir, biaya asuransi, dan kupon tidak dikembalikan. Stok dikembalikan sebagai batch masuk retur dan pengguna diberi tahu. | admin.v130.shopExceptions.confirm.cancelLine（后半句沿用） |
| D7 | 内联表单标题 | 部分取消 · {product}（剩下的最后一行） | Cancel part · {product} (last remaining line) | Batalkan sebagian · {product} (baris terakhir) | — |
| D7 | 内联表单提示 | 取消这一行后订单里就没有商品了，将按整单取消处理：退回剩余未退的应付金额（含运费、保价费），请选原因类别。 | Cancelling this line leaves no items in the order, so it is handled as a whole-order cancellation: the remaining unrefunded amount payable (incl. shipping and insurance fee) is refunded. Choose a reason category. | Setelah baris ini dibatalkan, pesanan tidak berisi produk lagi, jadi diproses sebagai pembatalan seluruh pesanan: sisa tagihan yang belum dikembalikan (termasuk ongkir dan biaya asuransi) dikembalikan. Pilih kategori alasan. | — |
| D7 | 平台原因单选说明 | 给补偿、退券 | Compensation given, coupon returned | Kompensasi diberikan, kupon dikembalikan | — |
| D7 | 明细行 | 剩余未退应付 | Remaining payable not yet refunded | Sisa tagihan belum dikembalikan | — |
| D7 | 明细值 | {amount}（已减去此前部分取消退过的 {refunded}） | {amount} (after deducting {refunded} already refunded by earlier partial cancellations) | {amount} (sudah dikurangi {refunded} dari pembatalan sebagian sebelumnya) | — |
| D7 | 按钮 | 取消并按整单退款 | Cancel and refund as whole order | Batalkan dan refund seluruh pesanan | admin.shop.exceptions.btn.cancelWhole |
| D8 | toast | 已整单取消 {orderNo}：PawCoin {coinAmount} 已退回，现金 {cashAmount} 待用户选择去向 | Order {orderNo} cancelled: PawCoin {coinAmount} returned, cash {cashAmount} waiting for the user to choose where it goes | Pesanan {orderNo} dibatalkan: PawCoin {coinAmount} sudah dikembalikan, dana tunai {cashAmount} menunggu pengguna memilih tujuan dana | — |

## E 退款打款

| 屏号 | 位置 | 中文 | English | Bahasa Indonesia | 参考词条 |
|---|---|---|---|---|---|
| E1 | 页头说明 | 汇集所有待退的电商现金退款。用户在 App 选「退到银行 / 电子钱包」并提交收款账户后变为待打款；财务在系统外转账后回到这里标记。不接自动出款。 | All shop cash refunds still to be paid out. A refund becomes "Awaiting payout" once the user picks "Back to bank / e-wallet" in the app and submits a payout account; finance transfers outside the system, then marks it here. No automatic payout. | Semua refund tunai Toko yang belum dicairkan. Refund menjadi "Menunggu pencairan" setelah pengguna memilih "Kembali ke bank / e-wallet" di aplikasi dan mengirim rekening penerima; tim keuangan mentransfer di luar sistem, lalu menandainya di sini. Tidak ada pencairan otomatis. | App 稿 F14 选项原文 refundMethodToBank |
| E1/F6 | 文案内引用的 App 选项 | 退到银行 / 电子钱包 | Back to bank / e-wallet | Kembali ke bank / e-wallet | App 稿 F14 原文（refundMethodToBank）；「转为 PawCoin」= Ubah jadi PawCoin（refundMethodToPawcoin） |
| E1 | 页签 / 摘要格 | 待用户选择 | Awaiting user choice | Menunggu pilihan pengguna | — |
| E1/E4 | 页签 / 摘要格 / 吸底红框标题 / 按钮 | 打款失败 | Payout failed | Pencairan gagal | admin.v130.shopReturns.status.REFUND_FAILED 句式 |
| E1 | 页签 | 已退为 PawCoin | Refunded as PawCoin | Dikembalikan sebagai PawCoin | — |
| E1 | 列头 / 抽屉数据格 | 退款金额 | Refund amount | Jumlah refund | admin.common.amount |
| E1 | 列头 | 收款渠道与账号 | Payout channel & account | Kanal & rekening pencairan | admin.refundDetail.payoutChannel |
| E1/E3 | 列头 / 抽屉字段 | 户名 | Account name | Nama pemilik rekening | App 稿 |
| E1/E3 | 列头 / 抽屉字段 | 用户提交时间 | Submitted by user at | Waktu diajukan pengguna | — |
| E1/F7 | 来源值前缀「配送异常 · 丢件破损」 | 配送异常 | Delivery problem | Pengiriman bermasalah | 界面统一称「配送异常」（2026-11-06 定） |
| E1 | 摘要格 | 其中超 7 天未选 | of which not chosen for 7+ days | di antaranya belum dipilih > 7 hari | admin.shop.recon.col.shippingByCoin（of which / di antaranya） |
| E1 | 行内徽标（待用户选择页签） | 超 7 天未选 | Not chosen for 7+ days | Belum dipilih > 7 hari | — |
| E1 | 摘要格值 | {count} 笔 | {count} refund(s) | {count} transaksi | — |
| E2 | 空态 | 没有待打款的退款。 | No refunds awaiting payout. | Tidak ada refund yang menunggu pencairan. | — |
| E3 | 抽屉字段 | 发起人 | Initiated by | Diajukan oleh | admin.algo.col.by（Diubah oleh 句式） |
| E3 | 抽屉字段 | 收款渠道 | Payout channel | Kanal pencairan | admin.refundDetail.payoutChannel |
| E3 | 来源值 | 异常订单 · 缺货整单取消 | Exception orders · Whole-order cancellation (shortage) | Pesanan bermasalah · Pembatalan seluruh pesanan (kurang stok) | admin.shop.exceptions.title |
| E3 | 渠道值 | {bank} 转账（免手续费） | {bank} transfer (no fee) | Transfer {bank} (tanpa biaya) | App 稿「Transfer BCA」 |
| E3 | 吸底灰框标题 / 按钮 / 确认按钮 | 标记已打款 | Mark as paid | Tandai sudah dibayar | admin.v130.settlements.status.paid |
| E3 | 表单字段 | 转账流水号 | Transfer reference no. | No. referensi transfer | — |
| E3 | 表单字段 | 转账截图 | Transfer screenshot | Tangkapan layar transfer | — |
| E3 | 上传按钮 | 上传 | Upload | Unggah | admin.vets.avatar.upload |
| E3 | 确认弹窗标题 | 确认已打款？ | Confirm payout made? | Konfirmasi sudah dibayar? | — |
| E3 | 确认弹窗行 | 收款人 | Payee | Penerima | admin.shop.order.col.receiver |
| E3 | 确认弹窗行 | 收款账户 | Payout account | Rekening penerima | App 稿「rekening penerima」 |
| E3 | 确认弹窗合计行 | 打款金额 | Payout amount | Jumlah pencairan | — |
| E3 | 确认弹窗正文 | 标记后 App 订单详情显示「已退款」，并自动扣回该单成长值。 | Once marked, the app order detail shows "Refunded" and the EXP earned on this order is reversed automatically. | Setelah ditandai, detail pesanan di aplikasi menampilkan "Dana dikembalikan" dan EXP dari pesanan ini otomatis ditarik. | admin.v140.growth.ledger.reversal.REFUND |
| E4 | 打款失败原因选项 | 账户信息有误 | Incorrect account details | Data rekening salah | — |
| E4 | 打款失败原因选项（稿未画） | 转账被退回 | Transfer bounced back | Transfer dikembalikan bank | — |
| E4 | 红框提示 | App 订单详情会提示用户重新填写收款账户；不给用户发推送，提交后提醒客服联系用户。 | The app order detail asks the user to re-enter the payout account. No push is sent; after submitting, support is reminded to contact the user. | Detail pesanan di aplikasi meminta pengguna mengisi ulang rekening penerima. Tidak ada notifikasi push; setelah dikirim, layanan pelanggan diingatkan untuk menghubungi pengguna. | App 稿「Isi ulang rekening」 |
| E4 | 确认弹窗标题 | 确认标记打款失败（{reason}）？ | Mark payout as failed ({reason})? | Tandai pencairan gagal ({reason})? | — |
| E4 | 确认弹窗正文 | 用户需要重新填写收款账户，请联系客服去联系用户。 | The user needs to re-enter the payout account. Ask support to contact the user. | Pengguna perlu mengisi ulang rekening penerima. Minta layanan pelanggan menghubungi pengguna. | — |
| E5 | 抽屉警示（职责分离） | 这笔退款由你发起（{operator} {action}），按职责分离规则不能由你标记打款结果，请交给其他有「打款」权限的同事。 | You initiated this refund ({operator}, {action}), so under the duty-separation rule you cannot mark its payout result. Hand it to a colleague with the "Payout" permission. | Refund ini diajukan oleh Anda ({operator}, {action}), jadi sesuai aturan pemisahan tugas Anda tidak bisa menandai hasil pencairannya. Serahkan ke rekan lain yang punya izin "Pencairan". | admin.audit.action.REFUND_DUTY_VIOLATION_BLOCKED、admin.v130.shopInventory.needEdit |

## F 订单

| 屏号 | 位置 | 中文 | English | Bahasa Indonesia | 参考词条 |
|---|---|---|---|---|---|
| F1 | 页头说明 | 全部电商订单（含已取消、退款中）。按状态、异常标记筛选，点行看订单详情、退款与成长值。下运单、发货、标记送达等履约操作在「订单履约」。 | All shop orders (incl. cancelled and refunding). Filter by status or exception flag; click a row for order details, refunds and EXP. Fulfillment actions such as creating waybills, shipping and marking delivered are in "Order fulfillment". | Semua pesanan toko (termasuk yang dibatalkan dan sedang dikembalikan). Saring menurut status atau tanda masalah; klik baris untuk melihat detail pesanan, refund, dan EXP. Aksi pemenuhan seperti membuat resi, mengirim, dan menandai terkirim ada di "Pemenuhan pesanan". | admin.nav.shopOrders、admin.shop.orders.title |
| F1/H1 | 筛选项 | 异常标记 | Exception flag | Tanda masalah | admin.v130.shopOrders.exception |
| F1/F2 | 异常标记选项 / 订单号旁徽标 / 横幅标题 | 取消后到账 | Paid after cancellation | Dibayar setelah dibatalkan | — |
| F1 | 摘要格 | 待收货 | Awaiting delivery | Menunggu diterima | admin.v130.shopOrders.status.DELIVERED（diterima） |
| F1 | 摘要格 | 取消后到账待处理 | Paid after cancellation – to handle | Dibayar setelah dibatalkan – perlu ditangani | admin.v130.shopExceptions.tab.pending |
| F2 | 抽屉顶部横幅 | 取消后到账 · 到账 {amount} · {paidAt}（订单已于 {cancelledAt} 超时取消） | Paid after cancellation · received {amount} · {paidAt} (order timed out and was cancelled at {cancelledAt}) | Dibayar setelah dibatalkan · diterima {amount} · {paidAt} (pesanan dibatalkan otomatis karena waktu habis pada {cancelledAt}) | — |
| F2 | 金额行注（PawCoin 段） | 超时取消时已退回 | Returned at timeout cancellation | Dikembalikan saat batal otomatis | — |
| F2 | 吸底灰框标题 | 取消后到账处理 | Handle payment after cancellation | Tangani pembayaran setelah pembatalan | — |
| F2 | 吸底灰框提示 | 先联系用户，征得同意后再重新下单；用户不同意、条件不满足或没有库存时退还到账款（不给补偿）。 | Contact the user first and re-place the order only with their consent. If the user declines, conditions are not met, or there is no stock, refund the received payment (no compensation). | Hubungi pengguna dulu dan buat ulang pesanan hanya dengan persetujuannya. Jika pengguna menolak, syarat tidak terpenuhi, atau stok tidak ada, kembalikan dana yang diterima (tanpa kompensasi). | — |
| F2/F6 | 按钮 / 确认按钮 | 退还到账款 | Refund received payment | Kembalikan dana diterima | — |
| F2 | 按钮（替用户重新建订单） | 重新下单 | Re-place order | Buat ulang pesanan | App 稿「Pesanan ini dibuat ulang」；H5 同字不同义 |
| F3 | 吸底核对清单标题 | 重新下单 · 核对清单（全部满足才可提交） | Re-place order · Checklist (all must be met to submit) | Buat ulang pesanan · Daftar periksa (semua harus terpenuhi untuk mengirim) | — |
| F3 | 核对项 | 用户已同意由平台帮他重新下单 | The user agreed to let the platform re-place the order | Pengguna setuju platform membuat ulang pesanannya | — |
| F3 | 核对项 | 原订单用过的券仍在用户账户中、没有被用掉 | The coupon used on the original order is still in the user's account and unused | Kupon yang dipakai di pesanan asal masih ada di akun pengguna dan belum terpakai | admin.shop.inventory.col.originalOrderNo（pesanan asal） |
| F3 | 核对项小字 | {coupon} · 已过期也可用于这张新订单 | {coupon} · usable for this new order even if expired | {coupon} · tetap bisa dipakai untuk pesanan baru ini meski sudah kedaluwarsa | — |
| F3 | 核对项 | 用户 PawCoin 余额足以支付原订单的 PawCoin 段 | The user's PawCoin balance covers the original order's PawCoin segment | Saldo PawCoin pengguna cukup untuk bagian PawCoin pesanan asal | admin.userdetail.pawcoinBalance |
| F3 | 核对项小字 | 需 {required} · 当前余额 {balance} | Needs {required} · current balance {balance} | Butuh {required} · saldo saat ini {balance} | — |
| F3/F4 | 核对项 | 至少一行商品有库存 | At least one line is in stock | Minimal satu baris produk ada stoknya | — |
| F3 | 核对项小字 | {count} 行均有库存 | All {count} lines in stock | Semua {count} baris ada stok | admin.v130.shopReturns.lines |
| F3 | 核对项 | 收货地址仍有效 | The shipping address is still valid | Alamat pengiriman masih berlaku | admin.shop.order.section.shipTo |
| F3 | 核对清单提示 | 新订单金额按原订单锁定；原快递不可用时改选当前最便宜的一家，差额平台承担。 | The new order's amount is locked to the original order. If the original courier is unavailable, the cheapest current courier is used and the platform covers the difference. | Nilai pesanan baru dikunci sesuai pesanan asal. Jika kurir awal tidak tersedia, dipakai kurir termurah saat ini dan selisihnya ditanggung platform. | — |
| F3 | 按钮 | 提交重新下单 | Submit re-placed order | Kirim pesanan ulang | — |
| F4 | 核对项小字 | {product} 缺货：提交后这一行到「异常订单」走部分取消 | {product} is short: after submitting, cancel this line in "Exception orders" | {product} kurang stok: setelah dikirim, batalkan baris ini di "Pesanan bermasalah" | admin.shop.exceptions.title |
| F4 | 抽屉警示 | {product} 当前无库存。新订单仍按原订单全部商品生成，缺货行提交后在「异常订单」部分取消。 | {product} is currently out of stock. The new order still includes every item of the original; cancel the short line in "Exception orders" after submitting. | {product} saat ini tidak ada stok. Pesanan baru tetap berisi semua produk pesanan asal; batalkan sebagian baris yang kurang stok di "Pesanan bermasalah" setelah dikirim. | — |
| F5 | 成功横幅 | 已为用户重新下单：{newOrderNo}（{status}）· 用到账款支付 QRIS 段 {amount} · 操作人 {operator} · {time} | Order re-placed for the user: {newOrderNo} ({status}) · QRIS part {amount} paid with the received payment · by {operator} · {time} | Pesanan dibuat ulang untuk pengguna: {newOrderNo} ({status}) · bagian QRIS {amount} dibayar dengan dana yang diterima · oleh {operator} · {time} | admin.shop.order.col.cashAmount |
| F5 | toast | 已重新下单 {orderNo}，已通知用户 | Order re-placed as {orderNo}; user notified | Pesanan dibuat ulang: {orderNo}; pengguna sudah diberi tahu | — |
| F6 | 确认弹窗标题 | 确认退还到账款？ | Refund the received payment? | Kembalikan dana yang diterima? | — |
| F6 | 确认弹窗合计行 | 到账款 | Received payment | Dana diterima | — |
| F6 | 确认弹窗正文 | App 订单详情会出现「选择退款方式」，由用户选「转为 PawCoin」或「退到银行 / 电子钱包」。这笔退款不给转 PawCoin 补偿。 | The app order detail will show "Choose refund method", where the user picks "Convert to PawCoin" or "Back to bank / e-wallet". No PawCoin conversion bonus is given for this refund. | Detail pesanan di aplikasi akan menampilkan "Pilih cara pengembalian dana", lalu pengguna memilih "Ubah jadi PawCoin" atau "Kembali ke bank / e-wallet". Refund ini tidak mendapat bonus konversi PawCoin. | App 稿 F14 原文 |
| F7 | 抽屉小节标题 | 退款与成长值 | Refunds & EXP | Refund & EXP | admin.v140.users.tab.growth |
| F7 | 列头 | 去向 / 状态 | Destination / Status | Tujuan / Status | admin.shop.returns.col.cashDestination |
| F7 | 来源值 | 补偿 | Compensation | Kompensasi | admin.shop.returns.quote.compensation |
| F7 | PawCoin 段值 | {amount} 已退 | {amount} refunded | {amount} dikembalikan | — |
| F7 | 去向 / 状态值 | 待用户选择后计算 | Calculated after the user chooses | Dihitung setelah pengguna memilih | — |
| F7 | 成长值行 | 当初入账成长值 | EXP originally earned | EXP yang semula diperoleh | admin.v140.users.tab.growth |
| F7 | 成长值行 | 已扣回 | Reversed | Sudah ditarik | admin.v140.growth.ledger.reversal.REFUND |
| F7 | 成长值值 | {exp}（PawCoin 段退回时） | {exp} (when the PawCoin segment was returned) | {exp} (saat bagian PawCoin dikembalikan) | — |
| F7 | 成长值行 | 待扣回 | To be reversed | Akan ditarik | — |
| F7 | 成长值值 | {exp}（标记已打款时自动扣） | {exp} (reversed automatically when marked as paid) | {exp} (otomatis ditarik saat ditandai sudah dibayar) | — |

## G 服务范围与运费 / 商品

| 屏号 | 位置 | 中文 | English | Bahasa Indonesia | 参考词条 |
|---|---|---|---|---|---|
| G1 | 页头说明（改写） | 以 Kecamatan 为粒度维护可配送区域，只决定卖不卖：收货地址须在开通区域内、且 Biteship 至少有一家快递可达才能下单。运费由 Biteship 按包裹重量体积实时报价，用户自选快递。停用区域用停用而不是删除。 | Maintain deliverable areas at Kecamatan level. Areas only decide whether we sell: an order can be placed only if the shipping address is in an enabled area and at least one Biteship courier can reach it. Shipping fees are quoted live by Biteship from package weight and dimensions, and the user picks the courier. Disable an area instead of deleting it. | Kelola area pengiriman per Kecamatan. Area hanya menentukan bisa dijual atau tidak: pesanan hanya bisa dibuat bila alamat pengiriman ada di area aktif dan minimal satu kurir Biteship bisa menjangkaunya. Ongkir dihitung real-time oleh Biteship berdasarkan berat dan dimensi paket, dan pengguna memilih kurirnya. Nonaktifkan area, jangan dihapus. | 替换 admin.shop.shipping.hint |
| G1/G2 | 卡片标题 | 发货地址 | Ship-from address | Alamat pengirim | admin.shop.shipping.section.returnAddress 句式 |
| G1 | 发货地址字段 | 联系人 / 电话 | Contact / Phone | Kontak / No. HP | admin.shop.order.col.phone |
| G2 | 发货地址表单字段 | 联系人 | Contact name | Nama kontak | — |
| G2 | 发货地址表单字段 | 邮编 | Postal code | Kode pos | App 稿「Kode pos」 |
| G2 | 按钮 | 保存发货地址 | Save ship-from address | Simpan alamat pengirim | admin.shop.shipping.btn.saveReturnAddress |
| G1 | 卡片标题 | Biteship 账户 | Biteship account | Akun Biteship | — |
| G1 | 金额行 | 上次录入的余额 | Last entered balance | Saldo terakhir yang dicatat | — |
| G1 | 金额行 | 此后运单扣除 | Waybills deducted since | Potongan resi sejak itu | — |
| G1 | 金额行注 | {count} 单，不含已取消运单 | {count} waybills, excl. cancelled ones | {count} resi, tidak termasuk resi yang dibatalkan | — |
| G1 | 金额合计行 | 预估余额 | Estimated balance | Perkiraan saldo | — |
| G1 | 余额提示 | 预估值，可能与 Biteship 实际余额有出入。充值后或发现对不上时，到 Biteship 后台查看实际余额，填进下面更新。 | This is an estimate and may differ from the actual Biteship balance. After topping up, or whenever it doesn't match, check the actual balance in the Biteship dashboard and enter it below. | Ini perkiraan dan bisa berbeda dari saldo Biteship sebenarnya. Setelah isi ulang, atau bila tidak cocok, cek saldo sebenarnya di dasbor Biteship lalu isi di bawah. | admin.config.topupPaused（isi ulang） |
| G1 | 表单字段 | 录入实际余额 | Enter actual balance | Catat saldo sebenarnya | — |
| G1 | 输入占位 | 按 Biteship 后台显示的余额填写 | As shown in the Biteship dashboard | Sesuai saldo di dasbor Biteship | — |
| G1 | 按钮 | 更新余额 | Update balance | Perbarui saldo | — |
| G1 | 表单字段 | 余额预警阈值 | Low-balance alert threshold | Ambang peringatan saldo | admin.shop.shipping.section.threshold（Ambang） |
| G1 | 阈值提示 | 预估余额低于阈值时飞书提醒运营充值。余额不足会导致下运单失败。 | When the estimated balance drops below the threshold, ops gets a Lark reminder to top up. An insufficient balance makes waybill creation fail. | Bila perkiraan saldo di bawah ambang, tim operasional diingatkan lewat Lark untuk isi ulang. Saldo tidak cukup membuat pembuatan resi gagal. | — |
| G1 | 按钮 | 保存阈值 | Save threshold | Simpan ambang | admin.shop.shipping.btn.saveThreshold |
| G1 | 免运门槛卡说明（改写） | 商品实付达到该金额即减免运费，减免额取本单最低快递运费与「最高减免」中较小者。门槛填 0 表示不做免运。 | When the goods amount paid reaches this amount, shipping is discounted by the lower of the order's cheapest courier fee and the "Maximum discount". 0 means no free shipping. | Bila nilai barang yang dibayar mencapai jumlah ini, ongkir dipotong sebesar nilai terkecil antara ongkir kurir termurah pesanan dan "Potongan maksimal". Isi 0 berarti tidak ada gratis ongkir. | 替换 admin.shop.shipping.threshold.hint |
| G1/G4 | 免运门槛卡字段 | 最高减免 | Maximum discount | Potongan maksimal | 同 A4「最高减免金额」 |
| G4 | 字段错误 | 门槛大于 0 时必填 | Required when the threshold is above 0 | Wajib diisi bila ambang lebih dari 0 | — |
| G1 | 退货收件地址字段 | 收件人 / 电话 | Recipient / Phone | Penerima / No. HP | admin.shop.order.col.receiver、admin.shop.order.col.phone |
| G2 | 页顶错误横幅 | 还没有配置发货地址：App 端无法向 Biteship 询价，所有用户结算时都拿不到运费、不能下单。 | No ship-from address configured: the app cannot get Biteship quotes, so no user gets a shipping fee at checkout or can place an order. | Alamat pengirim belum diatur: aplikasi tidak bisa meminta tarif ke Biteship, jadi semua pengguna tidak mendapat ongkir saat checkout dan tidak bisa memesan. | — |
| G3 | 页顶警示横幅 | Biteship 预估余额 {balance}，低于预警阈值 {threshold}，请尽快充值，充值后录入实际余额。余额不足会导致下运单失败。 | Estimated Biteship balance {balance} is below the alert threshold {threshold}. Top up soon and enter the actual balance afterwards. An insufficient balance makes waybill creation fail. | Perkiraan saldo Biteship {balance} di bawah ambang peringatan {threshold}. Segera isi ulang lalu catat saldo sebenarnya. Saldo tidak cukup membuat pembuatan resi gagal. | — |
| G5 | 筛选项 | 包裹数据 | Package data | Data paket | admin.shop.order.section.packages |
| G5 | 筛选选项 | 缺重量体积 | Missing weight/dimensions | Berat/dimensi kosong | — |
| G5 | 摘要格 | 缺重量体积的 SKU | SKUs missing weight/dimensions | SKU tanpa berat/dimensi | admin.v130.shopProducts.summary.skus |
| G5 | 商品名旁徽标 | {count} 个 SKU 缺重量体积 | {count} SKU(s) missing weight/dimensions | {count} SKU tanpa berat/dimensi | — |
| G6/J1 | 商品编辑页签 | 商品信息 | Product info | Info produk | admin.shop.form.section.basic |
| G6/J1 | 商品编辑页签（及文案内「评价」页签引用） | 评价 | Reviews | Ulasan | admin.ratings.col.comment（zh 同字，译 Comment/Komentar，不可复用）；App 稿「Ulasan」 |
| G6 | 规格卡错误横幅 | 有 {count} 个规格缺包裹重量或长宽高，缺的规格在 App 端不能加入购物车、不能购买。 | {count} variant(s) are missing package weight or dimensions; those variants cannot be added to cart or bought in the app. | {count} varian belum punya berat atau dimensi paket; varian tersebut tidak bisa dimasukkan ke keranjang atau dibeli di aplikasi. | admin.shop.sku.specName（Variant / Varian） |
| G6 | 规格表列头 | 重量(g) | Weight (g) | Berat (g) | admin.shop.sku.netWeight |
| G6 | 规格表列头 | 长×宽×高(cm) | L × W × H (cm) | P × L × T (cm) | — |
| G6 | 规格表单元格 | 未填 | Missing | Belum diisi | — |
| G6 | 规格表单字段 | 包裹重量(g) | Package weight (g) | Berat paket (g) | — |
| G6 | 规格表单字段 | 长(cm) | Length (cm) | Panjang (cm) | — |
| G6 | 规格表单字段 | 宽(cm) | Width (cm) | Lebar (cm) | — |
| G6 | 规格表单字段 | 高(cm) | Height (cm) | Tinggi (cm) | — |

## H 订单履约

| 屏号 | 位置 | 中文 | English | Bahasa Indonesia | 参考词条 |
|---|---|---|---|---|---|
| H1 | 摘要格 | 有异常标记 | Flagged | Ada tanda masalah | admin.v130.shopOrders.exception |
| H1/F1 | 异常标记选项 / 订单号旁徽标 / H12 横幅与灰框标题 | 物流停滞 | Tracking stalled | Pelacakan terhenti | — |
| H1/F1 | 异常标记选项 / 徽标 | 待索赔 | Claim pending | Menunggu klaim | admin.v130.shopReturns.tab.*（Menunggu …） |
| H2 | 订单节字段 | 快递（用户所选） | Courier (user's choice) | Kurir (pilihan pengguna) | admin.merchantPo.col.carrier |
| H2 | 订单节值 | {courier} · 预计 {days} 天 | {courier} · est. {days} days | {courier} · estimasi {days} hari | — |
| H2 | 订单节字段 / 下单框字段 | 保价 | Insurance | Asuransi | App 稿「Asuransi pengiriman」 |
| H2 | 保价值 | 是 · {fee} | Yes · {fee} | Ya · {fee} | admin.shop.recon.balanced（Ya） |
| H2 | 吸底灰框标题 / 按钮 | Biteship 下单 | Create Biteship order | Buat pesanan Biteship | — |
| H2 | 下单框字段 | 快递 | Courier | Kurir | admin.merchantPo.col.carrier |
| H2 | 下单框值 | {courier}（用户所选） | {courier} (user's choice) | {courier} (pilihan pengguna) | — |
| H2 | 下单框字段 | 用户支付运费 | Shipping paid by user | Ongkir dibayar pengguna | — |
| H2 | 下单框保价值 | 随单传给 Biteship · 商品价值 {value} | Sent to Biteship with the order · goods value {value} | Dikirim ke Biteship bersama pesanan · nilai barang {value} | admin.shop.returns.quote.goods |
| H2 | 下单框提示 | 确认后创建运单并预约上门取件，自动回填运单号与 Biteship 实际运费。 | Confirming creates the waybill and books a pickup; the tracking no. and the actual Biteship fee are filled in automatically. | Setelah dikonfirmasi, resi dibuat dan penjemputan dijadwalkan; nomor resi dan ongkir Biteship sebenarnya terisi otomatis. | admin.shop.order.col.trackingNo |
| H3 | 成功横幅 | 运单已创建 · {time} · 预约取件 {pickupSlot} | Waybill created · {time} · pickup booked {pickupSlot} | Resi dibuat · {time} · penjemputan dijadwalkan {pickupSlot} | — |
| H3 | 包裹状态 | 待揽收 | Awaiting pickup | Menunggu dijemput | App 稿 |
| H3 | 吸底灰框标题 / 按钮 | 标记已发货 | Mark as shipped | Tandai sudah dikirim | admin.v130.shopOrders.status.SHIPPED |
| H3 | 灰框提示 | 仓库确认已打包后标记。不标记时，Biteship 回传「已揽收」会自动转为已发货。 | Mark once the warehouse confirms it is packed. If not marked, a "picked up" update from Biteship moves it to Shipped automatically. | Tandai setelah gudang memastikan paket sudah dikemas. Jika tidak ditandai, status "sudah dijemput" dari Biteship otomatis mengubahnya menjadi Sudah dikirim. | — |
| H3 | toast | 已在 Biteship 下单：{trackingNo} | Biteship order created: {trackingNo} | Pesanan Biteship dibuat: {trackingNo} | — |
| H4 | 订单节字段 | 已发货来源 | Shipped status source | Sumber status dikirim | — |
| H4 | 已发货来源值前缀「运营标记 · {operator} · {time}」 | 运营标记 | Marked by ops | Ditandai tim operasional | — |
| H4 | 订单节字段 | 快递揽收 | Courier pickup | Dijemput kurir | — |
| H4 | 揽收值 | {time}（Biteship 回传） | {time} (Biteship update) | {time} (pembaruan Biteship) | — |
| H4 | 包裹状态 | 运输中 | In transit | Dalam perjalanan | admin.v130.shopOrders.summary.inTransit |
| H4 | 吸底灰框标题 | 送达与配送异常 | Delivery & delivery problems | Pengiriman & masalah pengiriman | 界面统一称「配送异常」 |
| H4/H10/H12 | 按钮 / 红框标题 | 标记丢件破损 | Mark lost or damaged | Tandai hilang atau rusak | admin.v130.shopOrders.shortage.btn 句式 |
| H5 | 错误横幅 | 运单失败 · Biteship 返回：{reason} · {time} | Waybill failed · Biteship returned: {reason} · {time} | Resi gagal · respons Biteship: {reason} · {time} | — |
| H5 | 红框标题 | 运单失败 · 选择处理方式 | Waybill failed · Choose how to handle | Resi gagal · Pilih penanganan | admin.shop.returns.disposal.choose |
| H5/H6 | 红框按钮（同一家快递重提运单） | 重新提交运单 | Retry waybill | Ajukan ulang resi | 新 key（与 F2「重新下单」区分，2026-11-06 定） |
| H5/H6/H7 | 红框按钮 / 表单标题 | 手工填写运单 | Enter waybill manually | Isi resi manual | — |
| H5/H6 | 红框按钮 | 联系用户后取消 → 异常订单 | Cancel after contacting user → Exception orders | Batalkan setelah menghubungi pengguna → Pesanan bermasalah | admin.shop.exceptions.title |
| H5 | 红框下提示 | 「重新提交运单」用同一家快递再提交一次，适合超时、接口报错、无可约取件时段、余额不足等临时问题。短时间内重新提交次数快速上升会通知技术排查。 | "Retry waybill" resubmits with the same courier — for temporary issues such as timeouts, API errors, no pickup slot, or insufficient balance. A sharp rise in retries within a short time alerts tech to investigate. | "Buat ulang resi" mengirim ulang dengan kurir yang sama — untuk masalah sementara seperti timeout, error API, tidak ada jadwal jemput, atau saldo tidak cukup. Lonjakan percobaan ulang dalam waktu singkat akan memberi tahu tim teknis untuk memeriksa. | — |
| H6 | 错误横幅 | 揽收失败 · Biteship 回传：{reason} · {time} | Pickup failed · Biteship update: {reason} · {time} | Penjemputan gagal · pembaruan Biteship: {reason} · {time} | — |
| H6 | 红框标题 | 揽收失败 · 选择处理方式 | Pickup failed · Choose how to handle | Penjemputan gagal · Pilih penanganan | — |
| H6 | 红框下提示 | 三种处理执行前都会先取消 Biteship 上的旧运单。用户侧不提示异常。 | All three options cancel the old Biteship waybill first. The user is not shown any problem. | Ketiga opsi membatalkan resi lama di Biteship terlebih dahulu. Pengguna tidak melihat pemberitahuan masalah. | — |
| H7 | 表单提示（取代原「发货」表单提示） | 手工运单不接 Biteship 回传：App 只显示快递与运单号，送达需在这里手工标记。承运成本进对账页。 | Manual waybills get no Biteship updates: the app shows only the courier and tracking no., and delivery must be marked here manually. Carrier cost goes to the reconciliation page. | Resi manual tidak menerima pembaruan Biteship: aplikasi hanya menampilkan kurir dan nomor resi, dan status terkirim harus ditandai manual di sini. Biaya kurir masuk ke halaman rekonsiliasi. | 取代 admin.shop.order.ship.hint 的使用场景 |
| H7 | 按钮（取代「发货」） | 保存并发货 | Save and ship | Simpan dan kirim | admin.shop.order.btn.ship |
| H8 | 错误横幅 | 退回 · Biteship 回传：{reason} · {time} | Returned · Biteship update: {reason} · {time} | Dikembalikan · pembaruan Biteship: {reason} · {time} | — |
| H8 | 红框标题 | 判定退回原因 | Decide why it was returned | Tentukan penyebab paket kembali | — |
| H8 | 单选 | 快递原因 | Courier fault | Kesalahan kurir | 与「平台原因」对仗 |
| H8 | 单选说明 | 平台承担运费，免费重新发货，订单标「待索赔」 | Platform pays the shipping and reships for free; the order is flagged "Claim pending" | Platform menanggung ongkir dan mengirim ulang gratis; pesanan ditandai "Menunggu klaim" | — |
| H8 | 单选说明（用户原因） | 地址错、联系不上、拒收：退回商品确认入库后，到异常订单整单取消（用户原因） | Wrong address, unreachable or refused: after the returned goods are confirmed received, cancel the whole order in Exception orders (user fault) | Alamat salah, tidak bisa dihubungi, atau ditolak: setelah barang kembali dikonfirmasi masuk gudang, batalkan seluruh pesanan di Pesanan bermasalah (kesalahan pengguna) | — |
| H8 | 输入占位 | 说明（例：收件人电话三次未接） | Note (e.g. recipient missed 3 calls) | Keterangan (mis. penerima tidak mengangkat telepon 3 kali) | admin.v140.growth.spend.col.desc（Keterangan） |
| H8 | 按钮 | 确认判定 | Confirm decision | Konfirmasi keputusan | admin.config.appVersion.confirm.btn |
| H9 | 警示横幅 | 退回 · 快递原因 · 判定于 {date} | Returned · courier fault · decided {date} | Dikembalikan · kesalahan kurir · diputuskan {date} | — |
| H9/H11 | 包裹状态（包裹已退回寄件人） | 已退回 | Returned to sender | Kembali ke pengirim | admin.v140.giftOrders.sum.refunded（zh 同字，译 Refunded，不可复用） |
| H9 | 吸底灰框标题 | 重新发货 | Reship | Kirim ulang | — |
| H9 | 灰框快递值 | {courier}（同原订单） | {courier} (same as original order) | {courier} (sama dengan pesanan asal) | — |
| H9 | 灰框运费值 | 平台成本，不向用户收取 | Platform cost; not charged to the user | Biaya platform; tidak ditagihkan ke pengguna | — |
| H9 | 灰框提示 | 退回到仓的商品不走入库，直接重新发出。 | Goods returned to the warehouse are reshipped directly without an inbound record. | Barang yang kembali ke gudang langsung dikirim ulang tanpa dicatat sebagai barang masuk. | admin.shop.inventory.btn.purchase（barang masuk） |
| H9 | 按钮 | 新建运单重发 | Create new waybill & reship | Buat resi baru & kirim ulang | 新 key（2026-11-06 定） |
| H9 | 灰框标题 | 索赔 | Claim | Klaim | — |
| H9/H11 | 按钮 / 灰框标题 | 索赔结案 | Close claim | Tutup klaim | — |
| H10/D5 | 单选 / 异常原因卡 | 丢件 | Lost | Hilang | — |
| H10 | 单选 | 破损 | Damaged | Rusak | — |
| H10 | 输入占位 | 说明（例：Biteship 确认丢失，工单 #88123） | Note (e.g. Biteship confirmed it lost, ticket #88123) | Keterangan (mis. Biteship konfirmasi hilang, tiket #88123) | admin.support.title（Tiket） |
| H10 | 红框提示（已保价） | 本单已购买保价：赔付按商品实付。标记后订单加「待索赔」，App 显示「配送异常」，到异常订单整单取消（丢件破损）退款。 | This order has shipping insurance: compensation follows the goods amount paid. Once marked, the order is flagged "Claim pending", the app shows "Delivery problem", and the refund is made by cancelling the whole order (lost or damaged) in Exception orders. | Pesanan ini memakai asuransi pengiriman: ganti rugi mengikuti nilai barang yang dibayar. Setelah ditandai, pesanan diberi tanda "Menunggu klaim", aplikasi menampilkan "Pengiriman bermasalah", dan refund dilakukan dengan membatalkan seluruh pesanan (hilang atau rusak) di Pesanan bermasalah. | — |
| H10 | 确认弹窗标题 | 确认把订单 {orderNo} 标记为{lostOrDamaged}？ | Mark order {orderNo} as {lostOrDamaged}? | Tandai pesanan {orderNo} sebagai {lostOrDamaged}? | admin.v130.shopOrders.confirm.markDelivered 句式 |
| H10 | 确认弹窗正文 | App 订单详情会显示「配送异常，请联系平台客服」。 | The app order detail will show "Delivery problem, please contact support". | Detail pesanan di aplikasi akan menampilkan "Pengiriman bermasalah, hubungi CS". | App 稿「Pengiriman bermasalah / Hubungi CS」 |
| H10 | 确认按钮 | 标记{lostOrDamaged}（稿：标记丢件） | Mark as {lostOrDamaged} | Tandai {lostOrDamaged} | — |
| H11 | 警示横幅 | 退回 · 快递原因 · 已重新发货 | Returned · courier fault · reshipped | Dikembalikan · kesalahan kurir · sudah dikirim ulang | — |
| H11 | 表单字段 | 索赔结果 | Claim result | Hasil klaim | — |
| H11 | 索赔结果选项 | 已赔付 | Paid out | Sudah diganti | — |
| H11 | 索赔结果选项（稿未画） | 部分赔付 | Partially paid | Diganti sebagian | — |
| H11 | 索赔结果选项（稿未画） | 被拒 | Rejected | Ditolak | admin.v130.shopReturns.status.REJECTED |
| H11 | 表单字段 | 到账金额 | Amount received | Jumlah diterima | — |
| H11 | 按钮 | 结案并清除「待索赔」 | Close and clear "Claim pending" | Tutup dan hapus tanda "Menunggu klaim" | — |
| H12 | 警示横幅 | 物流停滞 · 物流状态 {days} 天没有更新（最后：{lastStatus} · {time}）。请到 Biteship 追件。 | Tracking stalled · no tracking update for {days} days (last: {lastStatus} · {time}). Follow up in Biteship. | Pelacakan terhenti · status tidak berubah selama {days} hari (terakhir: {lastStatus} · {time}). Lacak di Biteship. | — |
| H12 | 灰框提示 | 物流恢复更新时自动清除；追件后确认无异常也可手工清除。停滞期间不会自动标记送达、自动完成。 | Cleared automatically when tracking updates resume; you can also clear it manually after following up. While stalled, the order is not auto-marked delivered or auto-completed. | Otomatis hilang saat pelacakan diperbarui lagi; bisa juga dihapus manual setelah dicek. Selama terhenti, pesanan tidak otomatis ditandai diterima atau selesai. | — |
| H12 | 按钮 | 去 Biteship 查看 | View in Biteship | Lihat di Biteship | — |
| H12 | 按钮 | 清除停滞标记 | Clear stalled flag | Hapus tanda terhenti | — |
| H13 | 订单节字段 | 运单来源 | Waybill source | Sumber resi | — |
| H13 | 运单来源值 | 手工填写 · {operator} · {time}（无 Biteship 回传） | Entered manually · {operator} · {time} (no Biteship updates) | Diisi manual · {operator} · {time} (tanpa pembaruan Biteship) | — |

## I 对账

| 屏号 | 位置 | 中文 | English | Bahasa Indonesia | 参考词条 |
|---|---|---|---|---|---|
| I1/I2 | 新卡片标题 | 优惠与物流成本 | Discounts & logistics cost | Diskon & biaya logistik | admin.shop.margin.section.spec22 |
| I1 | 列头 | 项目 | Item | Item | — |
| I1 | 行 | 满减券抵扣 | Spend-discount coupons | Potongan kupon diskon belanja | — |
| I1 | 行说明 | 平台承担的商品折扣 | Product discount borne by the platform | Diskon produk yang ditanggung platform | — |
| I1 | 行 | 满额免运减免 | Free-shipping threshold discount | Potongan ambang gratis ongkir | admin.shop.shipping.section.threshold |
| I1 | 行 | 免运券减免 | Free-shipping coupon discount | Potongan kupon gratis ongkir | App 稿「Kupon gratis ongkir」 |
| I1 | 行 | 用户实际支付的运费 | Shipping actually paid by users | Ongkir yang benar-benar dibayar pengguna | — |
| I1 | 行说明 | 运费 − 满额免运或免运券减免 | Shipping fee − threshold or coupon discount | Ongkir − potongan ambang atau kupon gratis ongkir | — |
| I1 | 行 | Biteship 实际运费 | Actual Biteship shipping cost | Ongkir Biteship sebenarnya | — |
| I1 | 行说明 | 不含保价；手工运单取录入的承运成本 | Excl. insurance; manual waybills use the entered carrier cost | Tanpa asuransi; resi manual memakai biaya kurir yang dicatat | admin.shop.order.col.carrierCost |
| I1 | 行 | 重发运费 | Reshipping cost | Ongkir kirim ulang | — |
| I1 | 行说明 | 快递原因重新发货 | Reshipments due to courier fault | Kirim ulang karena kesalahan kurir | — |
| I1 | 行 | 运费净成本 | Net shipping cost | Biaya ongkir bersih | admin.shop.margin.col.shippingNet |
| I1 | 行说明 | Biteship 实际运费 + 重发运费 − 用户实际支付的运费（减免已含在内，不再另加） | Actual Biteship cost + reshipping cost − shipping paid by users (discounts are already included; not added again) | Ongkir Biteship sebenarnya + ongkir kirim ulang − ongkir dibayar pengguna (potongan sudah termasuk, tidak ditambahkan lagi) | — |
| I1 | 行 | 保价费收入 / 保价实际成本 | Insurance fee income / actual insurance cost | Pendapatan biaya asuransi / biaya asuransi sebenarnya | admin.shop.margin.col.shippingRevenue |
| I1 | 行 | 转 PawCoin 补偿 | Convert-to-PawCoin compensation | Kompensasi ubah jadi PawCoin | admin.shop.returns.quote.incentive |
| I1 | 行说明 | 平台原因退款 | Refunds due to platform fault | Refund karena kesalahan platform | — |
| I1 | 行 | 待索赔 / 已索赔到账 | Claims pending / claims received | Menunggu klaim / klaim diterima | — |
| I1 | 行值 | {count} 单 / {amount} | {count} orders / {amount} | {count} pesanan / {amount} | admin.shop.recon.col.orders |
| I1 | 行说明（退款打款 · 打款失败） | 用户重填账户后回到待打款 | Returns to "Awaiting payout" after the user re-enters the account | Kembali ke "Menunggu pencairan" setelah pengguna mengisi ulang rekening | admin.v130.settlements.status.pendingFinance |
| I1 | 行（前缀「取消后到账 · 」） | 已转新订单 | Moved to new order | Dialihkan ke pesanan baru | — |
| I1 | 行说明 | 计入新订单现金收入，不重复计 | Counted in the new order's cash income; not double-counted | Dihitung sebagai pemasukan tunai pesanan baru; tidak dihitung ganda | — |
| I1 | 行（前缀「取消后到账 · 」） | 已退 | Refunded | Dikembalikan | — |
| I2 | 卡内空态 | 所选时间段没有数据。 | No data for the selected period. | Tidak ada data untuk periode yang dipilih. | admin.shop.margin.noSales |

## J 评价

| 屏号 | 位置 | 中文 | English | Bahasa Indonesia | 参考词条 |
|---|---|---|---|---|---|
| J1 | 图片列值 | {count} 张 | {count} image(s) | {count} gambar | admin.content.col.images |
| J1 | 状态 / 摘要格 | 显示中 | Visible | Ditampilkan | — |
| J1 | 状态 | 已隐藏 · {reason} | Hidden · {reason} | Disembunyikan · {reason} | — |
| J1 | 摘要格 | 已隐藏 | Hidden | Disembunyikan | — |
| J1 | 摘要格 | 平均分（显示中） | Average rating (visible) | Rata-rata nilai (ditampilkan) | admin.ratings.avg |
| J1/J3 | 行内操作 / 确认按钮 | 隐藏 | Hide | Sembunyikan | — |
| J1/J4 | 行内操作 / 确认按钮 | 恢复显示 | Unhide | Tampilkan lagi | — |
| J2 | 空态 | 这个商品还没有评价。 | This product has no reviews yet. | Produk ini belum punya ulasan. | App 稿「Belum ada ulasan」 |
| J3 | 确认弹窗标题 | 隐藏这条评价？ | Hide this review? | Sembunyikan ulasan ini? | — |
| J3 | 原因选项 | 辱骂 | Abusive | Kasar | — |
| J3 | 原因选项 | 广告 | Advertising | Iklan | admin.review.cat.ad |
| J3 | 原因选项 | 隐私 | Privacy | Privasi | — |
| J3 | 确认弹窗提示 | 隐藏后 App 端不再显示，商品平均分与评价条数随之重算。不通知写评价的用户。 | Once hidden, it no longer shows in the app, and the product's average rating and review count are recalculated. The reviewer is not notified. | Setelah disembunyikan, ulasan tidak lagi tampil di aplikasi, dan rata-rata nilai serta jumlah ulasan produk dihitung ulang. Penulis ulasan tidak diberi tahu. | — |
| J4 | 确认弹窗标题 | 恢复显示这条评价？ | Unhide this review? | Tampilkan lagi ulasan ini? | — |
| J4 | 确认弹窗正文 | App 端会重新显示，商品平均分与评价条数随之重算。 | It will show in the app again, and the product's average rating and review count are recalculated. | Ulasan akan tampil lagi di aplikasi, dan rata-rata nilai serta jumlah ulasan produk dihitung ulang. | — |
| J5/J6 | 运营配置新卡标题 / 确认弹窗新旧值行标签 | 商品评价展示 | Product review display | Tampilan ulasan produk | admin.v130.config.confirm.diff |
| J5 | 卡片说明 | 开启后 App 商品详情显示评价列表与全部评价页；关闭时只显示平均分与条数。请先在各商品的「评价」页签看完历史评价、隐藏有问题的，再开启。 | When on, the app product detail shows the review list and the all-reviews page; when off, only the average rating and count are shown. Check past reviews in each product's "Reviews" tab and hide problematic ones before turning this on. | Jika aktif, detail produk di aplikasi menampilkan daftar ulasan dan halaman semua ulasan; jika nonaktif, hanya rata-rata nilai dan jumlah ulasan. Periksa ulasan lama di tab "Ulasan" tiap produk dan sembunyikan yang bermasalah sebelum mengaktifkan. | — |
| J5 | 勾选项 | 在 App 商品详情展示评价内容 | Show review content on the app product detail | Tampilkan isi ulasan di detail produk aplikasi | — |

## (a) 用词决定（2026-11-06 操作者确认「文案用词可以」）

已按下列建议落实：「打款」印尼语统一 Pencairan（待打款 = Menunggu pencairan）；「重新下单」拆为三词——替用户重建订单「重新下单」、同一快递重提运单「重新提交运单」、退回后给 Biteship 新建一单「新建运单重发」；界面统一称「配送异常」；统一「转 PawCoin 补偿」；电商订单状态用「待支付」。其余条目照表内译法。

### 原始备注

1. 打款 / 退款打款 → payout / Pencairan：后台已有词不统一：admin.refundDetail.* 用 Pencairan，admin.audit.action.REFUND_PAYOUT_RECORDED 用 Pembayaran refund，admin.v130.settlements.status.pendingFinance（待打款）是 Menunggu pencairan。本表菜单 / 标题用「Pencairan refund」，状态「待打款」原样复用 Menunggu pencairan。需定一个词，菜单、页签、对账行要一致。

2. 满减 → Spend discount / Diskon belanja：印尼语跟 App 稿；英文后台和 App 都还没有定稿。备选「Amount off」。

3. 门槛（券）→ Minimum spend / Minimum belanja；门槛（免运设置、预警阈值）→ threshold / Ambang：沿用 admin.shop.shipping.section.threshold。同一个「门槛」按场景译成两个词，A1「面额 / 门槛」列与 G1 免运卡说明的对应关系要请业务确认。

4. 发放 → issue / penerbitan：分享奖励的「已发放」现译 Granted / Sudah diberikan（admin.config.shareReward.granted）。券是先发行、再由用户领取，所以没有复用那组词；如果希望整个后台统一用 grant/diberikan，需要改 A 泳道 6 处。

5. 运单 → waybill / resi；下单（Biteship）→ Create Biteship order：印尼语「resi」通常就是运单号，所以「运单失败」译成 Resi gagal 会被理解成「运单号出错」。备选：Pembuatan resi gagal。

6. 「重新下单」一个词有三种意思：F2 是替用户重建电商订单（Re-place order / Buat ulang pesanan）；H5/H6 是用同一家快递重提运单（Retry waybill / Buat ulang resi）；H9 写作「Biteship 重新下单」，指给 Biteship 新建一单（Create new Biteship order）。中文相同但必须拆成不同 key。建议中文也改开，比如 H5/H6 改成「重新提交运单」。

7. 「物流异常」和「配送异常」是同一个意思（E1 来源、H4 灰框用「物流异常」，D1 页头、队列 meta、H10 用「配送异常」）：本表都译为 Delivery problem / Pengiriman bermasalah（与 App 一致）。建议中文统一成「配送异常」。

8. 「退为 PawCoin 补偿」（D1）和「转 PawCoin 补偿」（I1）是同一概念、写法不同：两处都译为 Convert-to-PawCoin compensation / Kompensasi ubah jadi PawCoin。建议中文统一。

9. 「退回」「已退回」各有两种意思：一是包裹退回寄件人（Returned / Dikembalikan；已退回 = Returned to sender / Kembali ke pengirim），二是券或币退还给用户（Returned / Dikembalikan）。现有 admin.v140.giftOrders.sum.refunded=「已退回」译 Refunded，不可复用。

10. 评价 → Reviews / Ulasan：现有 admin.ratings.col.comment=「评价」是兽医评分的 Comment/Komentar，不可复用，需新 key。

11. 不限：现有 admin.common.none=「不限」译 Any/Semua；A 里的意思是 Unlimited / Tanpa batas，B1 排期里的意思是 No end / Tanpa akhir，三处都要新 key。

12. 物流停滞 → Tracking stalled / Pelacakan terhenti；待索赔 → Claim pending / Menunggu klaim；占用中 → Reserved / Dicadangkan；作废 / 已失效 → Void, Voided / Hanguskan, Tidak berlaku：这几个都没有现成词条可以参照，是我自拟的。「已失效」的印尼语跟 App 的「Kupon sudah tidak berlaku」走。

13. 平台原因 / 用户原因 / 快递原因 → Platform / User / Courier fault, Kesalahan platform / pengguna / kurir：「平台原因」的译法来自 admin.shop.returns.quote.compensationWhy（platform fault）。但「用户原因」里包括了「联系不上」，译成 fault / kesalahan 会让人觉得在责怪用户。不过这只是后台内部字段，App 不显示，所以影响有限。备选：Karena pengguna。

14. 线下打款 已核：App 稿 F14 原文为「Kembali ke bank / e-wallet」（退到银行 / 电子钱包）、「Ubah jadi PawCoin」，E1、F6 已照改。

15. 待收货 → Awaiting delivery / Menunggu diterima：线上订单状态里没有「待收货」（只有已发货 / 已签收），App 稿的状态名也没找到对应，需要和 App 状态对齐。

16. 券名模板「满 {minSpend} 减 {discount}」「免运 最高 {cap}」：这是后台按券参数自动拼出来的短名，要和 App「Kupon Saya」里的券标题格式保持一致；本表是我自拟的。

17. 待付款：线上只有 admin.merchantPo.status.PENDING_PAYMENT（门店进货单）这个字面一致的词条，电商订单状态用的是「待支付」（admin.v130.shopOrders.status.PENDING_PAYMENT）。F1 摘要格按规则跳过了，但建议 F1 改成「待支付」直接复用。

18. 已停用（券状态）：zh 已有 6 个同字词条，译法分别是 Disabled / Deactivated / Suspended。按规则跳过，复用时请用 admin.v130.accounts.status.disabled（Disabled / Dinonaktifkan）或 admin.opsPopups.state.DISABLED（Nonaktif），不要用 admin.utags.pickDeactivated（Suspended）。

19. E5 职责分离警示里的「你」：译成 You / Anda。印尼语沿用 admin.shop.banners.hint 里的 Anda，没有用 kamu（admin.v130.shopInventory.confirmStocktakeFallback 里用了 kamu，属于不一致的旧文案）。

20. H10 确认弹窗：稿上只画了「丢件」，本表写成 {lostOrDamaged} 模板。印尼语里 hilang / rusak 能直接代入，英文代入后是 lost / damaged，句子都通。


## (b) 本版含义改变的线上文案（旧 key → 新文案所在屏）

| 旧 key | 旧中文（摘要） | 本版变化 | 屏号 |
|---|---|---|---|
| admin.shop.banners.hint | 同一时间只展示一张：App 取「已上架 + 权重最高」…（商品详情 / 品类 / 外部链接） | 改写：最多 6 张轮播、拖动排序、目标失效自动不展示 | B1 |
| admin.shop.banners.form.image.hint | 可直接 Ctrl+V 粘贴。 | 扩写：建议尺寸、安全边距、比例裁切说明（末句保留） | B3 |
| admin.shop.banners.activeNotLive / admin.v130.shopBanners.state.activeNotLive | 已上架（被更高权重压住） | 废弃，由「超出展示上限」取代 | B1 |
| admin.shop.banners.col.weight / admin.v130.shopBanners.weightHint | 权重 / 越大越优先…同一时间只展示一张 | 废弃（权重列与字段删除，改拖动排序） | B1/B3 |
| admin.shop.banners.target.CATEGORY | 品类 | 选项下线；存量 banner 显示 B1 红字与 B4 警示 | B1/B4 |
| admin.shop.banners.section.add / admin.shop.form.titleEdit | 新增 Banner / 编辑商品 | 线上 bug：编辑态抽屉标题固定「新增 Banner」、编辑按钮显示「编辑商品」；改用新 key「编辑 Banner」 | B3 |
| admin.v130.shopExceptions.liveHint | 本页只列「待发货且已标记缺货」的订单… | 改写：来源扩展为缺货 / 运单失败 / 揽收失败 / 配送异常 | D1 |
| admin.shop.exceptions.hint | 这里列出运营在拣货时标记为缺货的待发货订单… | 同上，范围已不只缺货；若页面仍引用需同步改或废弃 | D1 |
| admin.v130.shopExceptions.confirm.cancelWhole | 确认整单取消并退款？订单金额 {0}，PawCoin 段即时退回并补偿，现金段走打款流程。 | 含义变：补偿只在现金段选择退为 PawCoin 时发；弹窗改为原因类别 + 两段明细 + 券 / 库存说明 | D3 |
| admin.v130.shopExceptions.confirm.cancelLine | 确认取消「{0}」{1} 件（按单价计约 {2}）？… | 改为按实付拆两段（不再「按单价计约」），并写明运费 / 保价 / 券不退 | D6 |
| admin.shop.shipping.hint | 每个区域一个固定运费。不接承运商实时计费；配送方式只有 Reguler 一档… | 含义反转：运费改由 Biteship 实时报价、用户自选快递，区域只决定卖不卖 | G1 |
| admin.shop.shipping.col.fee | 运费 | 区域表「运费」列删除，key 废弃 | G1 |
| admin.shop.shipping.threshold.hint | 商品小计达到该金额即免运费… | 改为按「商品实付」判断，且减免额受「最高减免」封顶 | G1 |
| admin.shop.shipping.empty | 还没有可配送区域——没有区域就一单也发不出去 | 语义仍成立，但现在还需要配置发货地址（见 G2 新横幅）；可保留 | G1/G2 |
| admin.shop.order.ship.hint | 承运成本填运单实际金额…承运商只作留痕，不影响用户运费。 | 「不影响用户运费」已不成立（用户自选快递）；原发货表单改为手工运单兜底，提示由 H7 新文案取代 | H2/H7 |
| admin.shop.order.btn.ship / admin.shop.order.section.ship | 发货 | 吸底「发货」表单换成「Biteship 下单」，手工表单按钮改「保存并发货」 | H2/H7 |
| admin.v130.shopOrders.shortage.goto 等 | 去异常订单处置 | 文案不变，但异常订单的来源已不只缺货，可继续复用 | — |
| admin.config.shareRewardEnabled | 分享奖励总开关（关掉即全线停发） | 文案不变，默认值由开改为关（无需改文案） | C1 |
| （原生 confirm，无 key） | 分享奖励保存时用浏览器原生 confirm 复述改动 | 改为 confirm-modal，新增标题 / 警示 / 估算行 | C2 |
