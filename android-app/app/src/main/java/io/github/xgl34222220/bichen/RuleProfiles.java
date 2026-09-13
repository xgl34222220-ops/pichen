package io.github.xgl34222220.bichen;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Presets change subscriptions only. User exceptions and the hosts pause state survive. */
public final class RuleProfiles {
    private RuleProfiles() {}
    public static Map<String, Boolean> flags(String id) {
        boolean adaway, hagezi;
        switch (id) {
            case "lite": adaway = false; hagezi = false; break;
            case "balanced": adaway = true; hagezi = false; break;
            case "enhanced": adaway = true; hagezi = true; break;
            default: throw new IllegalArgumentException("未知保护档位");
        }
        LinkedHashMap<String, Boolean> result = new LinkedHashMap<>();
        result.put("adaway", adaway); result.put("china", true);
        // Privacy tracking is a separate, deliberate opt-in, not silently inherited.
        result.put("tracking", false); result.put("hagezi", hagezi);
        return Collections.unmodifiableMap(result);
    }
    public static String identify(Map<String, Boolean> current) {
        for (String id : new String[]{"lite", "balanced", "enhanced"})
            if (flags(id).equals(current)) return id;
        return "custom";
    }
    public static String title(String id) {
        switch (id) {
            case "lite": return "轻量";
            case "balanced": return "均衡";
            case "enhanced": return "加强";
            default: return "自定义";
        }
    }
}
