package com.karson.todoapp.logic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure helpers for the keyed Firebase layout, mirroring the web app's {@code sync-logic.mjs}:
 *
 * <pre>
 * users/{uid}/
 *   tasks/{key}:    task fields (the key is the task's id)
 *   projects/{key}: { name, parentId? }
 * </pre>
 *
 * <p>No Firebase or Gson dependency, so these can be reasoned about (and unit-tested) in isolation.
 * They operate on the plain {@code Map}/{@code List}/primitive trees that Gson produces from either
 * a model object or a Firebase snapshot — feeding both sides through the same conversion is what lets
 * a record read back from the database compare equal (via {@link Map#equals}) to the one written.
 */
public final class SyncLogic {

    private SyncLogic() {}

    /** Characters Firebase does not allow in a key. */
    public static boolean isValidKey(String key) {
        return key != null && !key.isEmpty() && key.length() <= 768
                && !key.matches(".*[.#$\\[\\]/\\u0000-\\u001f\\u007f].*");
    }

    /**
     * What Firebase would hand back for a value: no nulls, empty lists or empty maps. Returns
     * {@code null} when nothing survives (an empty container), matching the web's {@code prune}.
     */
    public static Object prune(Object value) {
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<?>) value) {
                Object pruned = prune(item);
                if (pruned != null) out.add(pruned);
            }
            return out.isEmpty() ? null : out;
        }
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                Object pruned = prune(entry.getValue());
                if (pruned != null) out.put(String.valueOf(entry.getKey()), pruned);
            }
            return out.isEmpty() ? null : out;
        }
        return value;
    }

    /**
     * The stored form of an in-memory record map: the {@code id} moves into the key, so it is
     * dropped here, and the rest is pruned. Never {@code null} — an empty record becomes {@code {}}.
     */
    public static Map<String, Object> toStored(Map<String, Object> fields) {
        Map<String, Object> copy = new LinkedHashMap<>(fields);
        copy.remove("id");
        Object pruned = prune(copy);
        if (pruned instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) pruned;
            return map;
        }
        return new LinkedHashMap<>();
    }
}
