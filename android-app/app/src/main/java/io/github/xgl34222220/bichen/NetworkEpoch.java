package io.github.xgl34222220.bichen;

import java.util.Objects;

/** Default-network event reducer. No Android dependency; callers may lock this
 * object to make a final response/cache commit atomic with network changes. */
public final class NetworkEpoch<N> {
    public static final class Snapshot<N> {
        public final N network;
        public final long epoch;
        public final String status;
        private Snapshot(N network, long epoch, String status) {
            this.network = network; this.epoch = epoch; this.status = status;
        }
    }
    private N candidate;
    private boolean eligible, blocked;
    private String links;
    private Snapshot<N> current = new Snapshot<>(null, 0, "waiting");

    public synchronized Snapshot<N> snapshot() { return current; }
    public synchronized boolean isCurrent(Snapshot<N> value) { return value == current; }
    public synchronized void available(N network) {
        if (network == null || Objects.equals(candidate, network)) return;
        candidate = network; eligible = false; blocked = false; links = null;
        publish();
    }
    public synchronized void capabilities(N network, boolean usable) {
        if (!Objects.equals(candidate, network) || candidate == null || eligible == usable) return;
        eligible = usable; publish();
    }
    public synchronized void links(N network, String value) {
        if (!Objects.equals(candidate, network) || candidate == null || Objects.equals(links, value)) return;
        links = value; publish();
    }
    public synchronized void blocked(N network, boolean value) {
        if (!Objects.equals(candidate, network) || candidate == null || blocked == value) return;
        blocked = value; publish();
    }
    public synchronized void lost(N network) {
        // onLost(old Wi-Fi) can arrive AFTER onAvailable(new cellular).
        if (candidate != null && Objects.equals(candidate, network)) reset();
    }
    public synchronized void reset() {
        candidate = null; eligible = false; blocked = false; links = null;
        current = new Snapshot<>(null, current.epoch + 1, "offline");
    }
    private void publish() {
        String status = candidate == null ? "offline" : blocked ? "blocked" : eligible && links != null ? "ready" : "waiting";
        current = new Snapshot<>("ready".equals(status) ? candidate : null, current.epoch + 1, status);
    }
}
