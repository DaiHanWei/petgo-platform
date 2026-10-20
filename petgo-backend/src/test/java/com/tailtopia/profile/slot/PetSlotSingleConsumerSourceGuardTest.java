package com.tailtopia.profile.slot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * V1.4.0 multi-pet Story 2.2 · AC6.5 · L0 源码守卫（照 {@code KeepsakeSingleConsumerSourceGuardTest}）：
 * {@code PET_SLOT_RENT} 的 {@code PaymentIntentPaidEvent} 发放只有 {@code PetSlotPaidHandler} 一处
 * （① 的成长值监听 {@code GrowthSpendListener} 按档位表通读全部 purpose，不发名额，豁免）。
 */
class PetSlotSingleConsumerSourceGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/tailtopia");
    /** ① 成长值线的消费返成长值监听（不发名额）。 */
    private static final Set<String> GROWTH_EXEMPT = Set.of("GrowthSpendListener.java");
    static final Pattern LISTENS_PAID = Pattern.compile("\\(\\s*PaymentIntentPaidEvent\\s+\\w+\\s*\\)");
    static final Pattern PET_SLOT = Pattern.compile("\\b(PET_SLOT_RENT|PetSlotPurchase\\w*|PetSlotRental\\w*)\\b");

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    static boolean isPetSlotPaidConsumer(String src) {
        return LISTENS_PAID.matcher(src).find() && PET_SLOT.matcher(src).find();
    }

    @Test
    void onlyPetSlotPaidHandlerConsumesPaidEventsForPetSlotRent() throws IOException {
        List<String> consumers = new ArrayList<>();
        for (Path p : javaFiles(MAIN)) {
            String name = p.getFileName().toString();
            if (GROWTH_EXEMPT.contains(name)) {
                continue;
            }
            if (isPetSlotPaidConsumer(Files.readString(p, StandardCharsets.UTF_8))) {
                consumers.add(name);
            }
        }
        assertThat(consumers).containsExactly("PetSlotPaidHandler.java");
    }

    @Test
    void patternSelfCheck() {
        assertThat(isPetSlotPaidConsumer("void onPaid(PaymentIntentPaidEvent event) { if (p == PET_SLOT_RENT) }"))
                .isTrue();
        assertThat(isPetSlotPaidConsumer("void on( PaymentIntentPaidEvent e ) { petSlotRentals.save(x); PetSlotRental r; }"))
                .isTrue();
        assertThat(isPetSlotPaidConsumer("void onPaid(PaymentIntentPaidEvent event) { TAILSONALITY }")).isFalse();
        assertThat(isPetSlotPaidConsumer("publish(new PaymentIntentPaidEvent(1, t, u, PET_SLOT_RENT, c, 1, \"IDR\"))"))
                .isFalse();
    }
}
