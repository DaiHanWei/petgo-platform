package com.tailtopia.shop.logistics;

import static com.tailtopia.shop.logistics.LogisticsFixtures.line;
import static com.tailtopia.shop.logistics.LogisticsFixtures.paidOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.logistics.biteship.dto.BiteshipCreateOrderRequest;
import com.tailtopia.shop.logistics.BiteshipWaybillRequestAssembler.Assembly;
import com.tailtopia.shop.logistics.BiteshipWaybillRequestAssembler.Missing;
import com.tailtopia.shop.logistics.BiteshipWaybillRequestAssembler.Origin;
import com.tailtopia.shop.logistics.BiteshipWaybillRequestAssembler.Ready;
import com.tailtopia.shop.logistics.BiteshipWaybillRequestAssembler.SkuPackage;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：v1.4.2 shop Story 5.1 AC4.2 —— 下运单请求组装（纯函数）。 */
class BiteshipWaybillRequestAssemblerTest {

    private static final Origin ORIGIN = new Origin("Gudang TailTopia", "+62 811-000", "Jl. Gudang 1", "12440");
    private static final SkuPackage PKG = new SkuPackage(1000, 20, 15, 10);

    private static List<ShopOrderLine> lines() {
        return List.of(line(1L, 11L, "Royal Canin", "2kg", 100_000L, 2), line(2L, 12L, "Snack", null, 50_000L, 1));
    }

    private static Map<Long, SkuPackage> pkgs() {
        return Map.of(11L, PKG, 12L, new SkuPackage(200, 10, 10, 5));
    }

    private static Ready ready(Assembly a) {
        assertThat(a).isInstanceOf(Ready.class);
        return (Ready) a;
    }

    @Test
    @DisplayName("reference_id = display_no-序号；快递 / delivery_type 取订单快照与配置")
    void referenceAndCourier() {
        Ready r = ready(BiteshipWaybillRequestAssembler.assemble(paidOrder(false), lines(), pkgs(), ORIGIN, 0L, 3,
                "now"));
        BiteshipCreateOrderRequest req = r.request();
        assertThat(req.referenceId()).isEqualTo("TOKO-20261105-ABC123-3");
        assertThat(req.courierCompany()).isEqualTo("jne");
        assertThat(req.courierType()).isEqualTo("reg");
        assertThat(req.deliveryType()).isEqualTo("now");
        assertThat(req.price()).isEqualTo(28_000L);
    }

    @Test
    @DisplayName("电话只留数字；地址 address_line, kecamatan, kota, provinsi 逗号拼接；邮编取 kode_pos")
    void destination() {
        BiteshipCreateOrderRequest req = ready(BiteshipWaybillRequestAssembler.assemble(paidOrder(false), lines(),
                pkgs(), ORIGIN, 0L, 1, "now")).request();
        assertThat(req.destination().phone()).isEqualTo("628123456789");
        assertThat(req.destination().address())
                .isEqualTo("Jl. Melawai IV No. 12, Kebayoran Baru, Jakarta Selatan, DKI Jakarta");
        assertThat(req.destination().postalCode()).isEqualTo("12160");
        assertThat(req.destination().name()).isEqualTo("Budi Santoso");
        assertThat(req.origin().phone()).isEqualTo("62811000");
        assertThat(BiteshipWaybillRequestAssembler.normalizePhone("+62812")).isEqualTo("62812");
    }

    @Test
    @DisplayName("items：名称 = 商品名 + 规格名；value = 单价；部分取消件数扣减、剩 0 行跳过")
    void itemsDeductRefundedQty() {
        List<ShopOrderLine> ls = lines();
        ls.get(0).addRefundedQty(1);
        ls.get(1).addRefundedQty(1);
        BiteshipCreateOrderRequest req = ready(BiteshipWaybillRequestAssembler.assemble(paidOrder(false), ls, pkgs(),
                ORIGIN, 0L, 1, "now")).request();
        assertThat(req.items()).hasSize(1);
        assertThat(req.items().get(0).name()).isEqualTo("Royal Canin 2kg");
        assertThat(req.items().get(0).quantity()).isEqualTo(1);
        assertThat(req.items().get(0).value()).isEqualTo(100_000L);
        assertThat(req.items().get(0).weightG()).isEqualTo(1000);
    }

    @Test
    @DisplayName("全部件已取消 → 409「订单已无可发货商品」")
    void allCancelledRejected() {
        List<ShopOrderLine> ls = lines();
        ls.get(0).addRefundedQty(2);
        ls.get(1).addRefundedQty(1);
        assertThatThrownBy(() -> BiteshipWaybillRequestAssembler.assemble(paidOrder(false), ls, pkgs(), ORIGIN, 0L,
                1, "now")).isInstanceOf(AppException.class).hasMessageContaining("无可发货商品");
    }

    @Test
    @DisplayName("SKU 包裹参数缺失 → SKU_PACKAGE_MISSING；发货地缺失 → ORIGIN_NOT_CONFIGURED")
    void missingData() {
        Assembly a = BiteshipWaybillRequestAssembler.assemble(paidOrder(false), lines(),
                Map.of(11L, PKG, 12L, new SkuPackage(200, null, 10, 5)), ORIGIN, 0L, 1, "now");
        assertThat(a).isEqualTo(new Missing(BiteshipWaybillRequestAssembler.SKU_PACKAGE_MISSING));

        Assembly b = BiteshipWaybillRequestAssembler.assemble(paidOrder(false), lines(), pkgs(),
                new Origin("Gudang", " ", "Jl. 1", "12440"), 0L, 1, "now");
        assertThat(b).isEqualTo(new Missing(BiteshipWaybillRequestAssembler.ORIGIN_NOT_CONFIGURED));
        assertThat(BiteshipWaybillRequestAssembler.assemble(paidOrder(false), lines(), pkgs(), null, 0L, 1, "now"))
                .isEqualTo(new Missing(BiteshipWaybillRequestAssembler.ORIGIN_NOT_CONFIGURED));
    }

    @Test
    @DisplayName("保价额 = 商品金额 − 满减 − LINE 级已退商品额（下限 0）；未勾选不传")
    void insurance() {
        ShopOrder insured = paidOrder(true);
        Ready r = ready(BiteshipWaybillRequestAssembler.assemble(insured, lines(), pkgs(), ORIGIN, 40_000L, 1, "now"));
        assertThat(r.insuranceAmount()).isEqualTo(340_000L - 25_000L - 40_000L);
        assertThat(r.request().insuranceValue()).isEqualTo(275_000L);

        Ready plain = ready(BiteshipWaybillRequestAssembler.assemble(paidOrder(false), lines(), pkgs(), ORIGIN,
                40_000L, 1, "now"));
        assertThat(plain.request().insuranceValue()).isNull();
        assertThat(plain.insuranceAmount()).isZero();

        assertThat(BiteshipWaybillRequestAssembler.insuranceValue(100L, 50L, 80L)).isZero();
    }

    @Test
    @DisplayName("商品名截断 100 字")
    void nameTruncated() {
        String longName = "x".repeat(120);
        List<ShopOrderLine> ls = List.of(line(1L, 11L, longName, "S", 1L, 1));
        BiteshipCreateOrderRequest req = ready(BiteshipWaybillRequestAssembler.assemble(paidOrder(false), ls,
                Map.of(11L, PKG), ORIGIN, 0L, 1, "now")).request();
        assertThat(req.items().get(0).name()).hasSize(100);
    }
}
