package io.github.xgl34222220.bichen;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class NetworkEpochTest {
    private static int checks;
    private static void check(boolean b, String why) { checks++; if (!b) throw new AssertionError(why); }
    private static void ready(NetworkEpoch<String> state, String name) {
        state.available(name); state.capabilities(name, true); state.links(name, "dns:" + name);
    }
    public static void main(String[] args) throws Exception {
        NetworkEpoch<String> state = new NetworkEpoch<>();
        check(state.snapshot().network == null, "no guessed network before callback");
        check("waiting".equals(state.snapshot().status), "initial waiting");
        state.available("wifi");
        NetworkEpoch.Snapshot<String> waiting = state.snapshot();
        check(waiting.network == null, "onAvailable is not enough");
        state.capabilities("wifi", true);
        check(state.snapshot().network == null, "wait for ordered link properties");
        state.links("wifi", "dns:one");
        NetworkEpoch.Snapshot<String> wifi = state.snapshot();
        check("wifi".equals(wifi.network), "available caps links make ready");
        check("ready".equals(wifi.status), "ready is a network state, not upstream DNS reachability");
        check(!state.isCurrent(waiting), "old waiting state invalidated");
        state.capabilities("wifi", true); state.links("wifi", "dns:one"); state.available("wifi"); state.blocked("wifi", false);
        check(state.snapshot() == wifi, "duplicate callbacks do not flush caches");
        state.links("wifi", "dns:two");
        check(!state.isCurrent(wifi), "same network changed DNS invalidates old response");
        wifi = state.snapshot();
        check("wifi".equals(wifi.network), "DNS change retains network handle");
        state.capabilities("wifi", false);
        check(state.snapshot().network == null, "VPN or missing internet capability not used");
        state.capabilities("wifi", true);
        check("wifi".equals(state.snapshot().network), "capability restored");
        state.blocked("wifi", true);
        check("blocked".equals(state.snapshot().status) && state.snapshot().network == null, "system block suspends upstream");
        state.blocked("wifi", false);
        check("wifi".equals(state.snapshot().network), "system unblock recovers");
        wifi = state.snapshot();
        state.available("cell");
        check(!state.isCurrent(wifi) && state.snapshot().network == null, "handover invalidates in-flight Wi-Fi work immediately");
        state.capabilities("cell", true); state.links("cell", "cell-dns");
        NetworkEpoch.Snapshot<String> cell = state.snapshot();
        state.lost("wifi"); state.links("wifi", "stale"); state.capabilities("wifi", false); state.blocked("wifi", true);
        check(state.snapshot() == cell, "stale callbacks never disconnect new default");
        state.lost("cell");
        check("offline".equals(state.snapshot().status) && state.snapshot().network == null, "airplane mode is offline");
        state.links("cell", "late"); state.capabilities("cell", true);
        check(state.snapshot().network == null, "late events cannot resurrect lost network");
        ready(state, "wifi"); NetworkEpoch.Snapshot<String> beforeReset = state.snapshot();
        state.reset();
        check(!state.isCurrent(beforeReset), "VPN stop invalidates all old work");
        ready(state, "wifi");
        check(state.snapshot().epoch > beforeReset.epoch, "restart with same handle is a fresh epoch");
        state.reset(); state.available(null); state.capabilities(null, true); state.links(null, "fake");
        check(state.snapshot().network == null, "null events cannot select route");
        state.available("reverse"); state.links("reverse", "lp");
        check(state.snapshot().network == null, "link before capability still waits");
        state.capabilities("reverse", true); check(state.snapshot().network != null, "out of order test eventually ready");
        // A paused response thread loses the ability to publish after a handover.
        NetworkEpoch.Snapshot<String> old = state.snapshot();
        CountDownLatch captured = new CountDownLatch(1), resume = new CountDownLatch(1);
        AtomicReference<Boolean> accepted = new AtomicReference<>();
        Thread response = new Thread(() -> {
            captured.countDown();
            try { resume.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
            synchronized (state) { accepted.set(state.isCurrent(old)); }
        });
        response.start(); captured.await(); ready(state, "replacement"); resume.countDown(); response.join(2000);
        check(!response.isAlive(), "concurrent response finishes");
        check(Boolean.FALSE.equals(accepted.get()), "late response rejected after network handover");
        DnsCache cache = new DnsCache();
        DnsPacket.Query q = DnsCacheTest.query("network.example", 1);
        NetworkEpoch.Snapshot<String> first = state.snapshot();
        cache.put(q, DnsCacheTest.answer(q, 60), first.epoch + ":rules");
        check(cache.get(q, first.epoch + ":rules") != null, "cache works within network epoch");
        ready(state, "next");
        check(cache.get(q, state.snapshot().epoch + ":rules") == null, "new network cannot reuse old cached IP");
        System.out.println("PASS: network epoch " + checks + " assertions");
    }
}
