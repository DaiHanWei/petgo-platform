package com.tailtopia.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：锁键登记无重复（analytics-pipeline Story 1.1 AC9）。 */
class AnalyticsLockKeysTest {

    private static List<Field> keyFields() {
        List<Field> fields = new ArrayList<>();
        for (Field f : AnalyticsLockKeys.class.getDeclaredFields()) {
            int m = f.getModifiers();
            if (Modifier.isPublic(m) && Modifier.isStatic(m) && Modifier.isFinal(m)
                    && f.getType() == int.class && !f.getName().equals("NAMESPACE")) {
                fields.add(f);
            }
        }
        return fields;
    }

    @Test
    void keysAreUnique() throws IllegalAccessException {
        List<Integer> values = new ArrayList<>();
        for (Field f : keyFields()) {
            values.add(f.getInt(null));
        }
        assertThat(values).doesNotHaveDuplicates();
    }

    @Test
    void allPlannedTasksRegistered() {
        assertThat(keyFields()).extracting(Field::getName).contains(
                "PARTITION_MAINTENANCE", "DETAIL_BUILDER", "ERASURE", "STORAGE_WATERMARK",
                "UNKNOWN_EVENT_ALERT", "ROLLUP", "GA4_SNAPSHOT");
    }

    @Test
    void namespaceIsAnly() {
        assertThat(AnalyticsLockKeys.NAMESPACE).isEqualTo(0x414E4C59);
        byte[] ascii = java.nio.ByteBuffer.allocate(4).putInt(AnalyticsLockKeys.NAMESPACE).array();
        assertThat(new String(ascii, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("ANLY");
    }
}
