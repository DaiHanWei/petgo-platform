package com.tailtopia.triage;

import com.tailtopia.triage.domain.TriageStatus;
import com.tailtopia.triage.domain.TriageTask;
import java.lang.reflect.Field;
import java.util.List;

/** L0 测试辅助：反射设置 {@link TriageTask} 的 id/status（工厂不设 id，状态机受控构造）。 */
public final class TriageTestSupport {

    private TriageTestSupport() {
    }

    public static TriageTask task(long id, long userId, TriageStatus status,
            String symptom, List<String> imageKeys) {
        TriageTask t = TriageTask.submit(userId, null, symptom, imageKeys, null);
        set(t, "id", id);
        set(t, "status", status);
        return t;
    }

    public static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
