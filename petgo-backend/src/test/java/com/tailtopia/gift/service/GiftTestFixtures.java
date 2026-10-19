package com.tailtopia.gift.service;

import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftCatalogStatus;
import com.tailtopia.gift.domain.GiftKind;
import com.tailtopia.gift.domain.GiftOrder;
import com.tailtopia.gift.domain.GiftSource;
import com.tailtopia.gift.port.GiftPetDirectory.GiftPet;
import com.tailtopia.pay.domain.PayChannel;
import java.time.Instant;
import org.springframework.test.util.ReflectionTestUtils;

/** gift L0 单测共用夹具（不起 Spring、不连库）。 */
public final class GiftTestFixtures {

    public static final long SENDER = 7L;
    public static final long OWNER = 8L;
    public static final long PET = 70L;

    private GiftTestFixtures() {
    }

    public static GiftCatalogItem gift(long id, long price, boolean cat, boolean dog, boolean other, int sort) {
        GiftCatalogItem g = GiftCatalogItem.create("g" + id, "Gift" + id, "Hadiah" + id, "https://cdn/g" + id + ".png",
                price, cat, dog, other, GiftKind.NORMAL, null, sort);
        ReflectionTestUtils.setField(g, "id", id);
        return g;
    }

    public static GiftCatalogItem exclusive(long id, long price, int minLevel, int sort) {
        GiftCatalogItem g = GiftCatalogItem.create("g" + id, "Gift" + id, "Hadiah" + id, "https://cdn/g" + id + ".png",
                price, false, false, false, GiftKind.EXCLUSIVE, minLevel, sort);
        ReflectionTestUtils.setField(g, "id", id);
        return g;
    }

    public static GiftCatalogItem offline(GiftCatalogItem g) {
        ReflectionTestUtils.setField(g, "status", GiftCatalogStatus.OFFLINE);
        return g;
    }

    public static GiftPet pet(String petType) {
        return new GiftPet(PET, OWNER, "Mochi", "https://cdn/p.png", petType, null, true, false);
    }

    public static GiftOrder order(long id, PayChannel channel, int quantity, long unitPrice) {
        GiftOrder o = GiftOrder.pending("TOK" + id, SENDER, PET, OWNER, "Mochi", gift(5L, unitPrice, false, false,
                false, 0), quantity, channel, GiftSource.DIARY_TAB, GiftIdemKeys.send(SENDER, "r" + id), Instant.EPOCH);
        ReflectionTestUtils.setField(o, "id", id);
        return o;
    }
}
