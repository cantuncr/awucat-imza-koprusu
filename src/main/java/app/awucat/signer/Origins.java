package app.awucat.signer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Origin allow-list. Built-in defaults plus {@code --allow-origin} flags plus the persisted
 * {@code allowedOrigins} from the config file. Unknown origins are put to the {@link Prompter}
 * (a yes/no dialog); an approval is persisted, a refusal is remembered for a few minutes so a
 * page retrying in a loop does not re-open the dialog on every request.
 */
final class Origins {
    static final String[] DEFAULTS = {
        "https://awucat.app", "https://www.awucat.app", "http://localhost:*", "http://127.0.0.1:*"
    };
    private static final long DENY_MEMORY_MS = 5 * 60 * 1000L;

    /** Asks the user whether {@code origin} may use the bridge. */
    interface Prompter {
        boolean allow(String origin);
    }

    private final Config config;
    private final List<String> extra;
    private final Map<String, Long> deniedUntil = new HashMap<>();
    private final Object promptLock = new Object();

    Origins(Config config, List<String> extra) {
        this.config = config;
        this.extra = new ArrayList<>(extra);
    }

    boolean isAllowed(String origin) {
        if (origin == null) return false;
        for (String p : DEFAULTS) if (matches(p, origin)) return true;
        for (String p : extra) if (matches(p, origin)) return true;
        synchronized (config) {
            for (String p : config.allowedOrigins) if (matches(p, origin)) return true;
        }
        return false;
    }

    /**
     * Returns true when the origin may use the bridge, prompting the user for unknown origins
     * when a prompter is available. Only one prompt is shown at a time.
     */
    boolean check(String origin, Prompter prompter) {
        if (origin == null || origin.isEmpty() || origin.equals("null")) return false;
        if (isAllowed(origin)) return true;
        if (prompter == null) return false;
        synchronized (promptLock) {
            if (isAllowed(origin)) return true; // approved by a concurrent request's prompt
            Long until = deniedUntil.get(origin);
            if (until != null && until > System.currentTimeMillis()) return false;
            boolean ok = prompter.allow(origin);
            if (!ok) {
                deniedUntil.put(origin, System.currentTimeMillis() + DENY_MEMORY_MS);
                Log.warn("Origin reddedildi: " + origin);
                return false;
            }
            synchronized (config) {
                config.allowedOrigins.add(origin);
                config.save();
            }
            Log.info("Origin izin listesine eklendi: " + origin);
            return true;
        }
    }

    /** {@code scheme://host:*} matches any (or no) port; anything else must match exactly. */
    static boolean matches(String pattern, String origin) {
        String p = pattern.toLowerCase(Locale.ROOT), o = origin.toLowerCase(Locale.ROOT);
        if (!p.endsWith(":*")) return p.equals(o);
        String base = p.substring(0, p.length() - 2);
        if (o.equals(base)) return true;
        return o.startsWith(base + ":") && o.substring(base.length() + 1).matches("\\d{1,5}");
    }
}
