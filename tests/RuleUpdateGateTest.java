package io.github.xgl34222220.bichen;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class RuleUpdateGateTest {
    private static int checks;
    interface Action { void run() throws Exception; }
    private static void check(boolean b, String why) { checks++; if (!b) throw new AssertionError(why); }
    private static void rejected(Action action, String why) throws Exception {
        try { action.run(); throw new AssertionError(why); } catch (IOException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        RuleUpdateGate gate = new RuleUpdateGate();
        RuleUpdateGate.Lease first = gate.begin();
        first.verify("r1", "r1"); check(true, "unchanged generation can commit");
        rejected(() -> gate.begin(), "duplicate manual/daily download must not queue");
        rejected(() -> first.verify("r1", "r2"), "edits during download must win");
        first.verify("r2", "r2"); check(true, "conflict check does not mutate lease");
        first.close();
        RuleUpdateGate.Lease second = gate.begin();
        first.close();
        rejected(() -> gate.begin(), "double close cannot release another active update");
        rejected(() -> first.verify("r1", "r1"), "closed lease cannot commit");
        second.close();
        Thread.currentThread().interrupt();
        rejected(() -> gate.begin(), "interrupted download cannot begin");
        check(Thread.interrupted(), "interruption flag preserved");
        try (RuleUpdateGate.Lease lease = gate.begin()) {
            Thread.currentThread().interrupt();
            rejected(() -> lease.verify("r1", "r1"), "canceled download cannot publish");
            check(Thread.interrupted(), "verify preserves cancellation");
        }
        try { try (RuleUpdateGate.Lease lease = gate.begin()) { throw new IOException("download failed"); } }
        catch (IOException expected) { }
        try (RuleUpdateGate.Lease lease = gate.begin()) { lease.verify("r1", "r1"); check(true, "failure releases single-flight gate"); }
        // Model the integration pattern: a download holds only the update lease,
        // not the short configuration lock used by a foreground whitelist edit.
        Object configLock = new Object(); AtomicReference<String> revision = new AtomicReference<>("before");
        CountDownLatch downloading = new CountDownLatch(1), finishDownload = new CountDownLatch(1);
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread update = new Thread(() -> {
            try (RuleUpdateGate.Lease lease = gate.begin()) {
                String before; synchronized (configLock) { before = revision.get(); }
                downloading.countDown(); finishDownload.await();
                synchronized (configLock) { lease.verify(before, revision.get()); revision.set("downloaded"); }
            } catch (Throwable e) { result.set(e); }
        });
        update.start(); downloading.await();
        synchronized (configLock) { revision.set("user-whitelist"); }
        check("user-whitelist".equals(revision.get()), "whitelist edit is not blocked by download");
        rejected(() -> gate.begin(), "concurrent manual download rejected");
        finishDownload.countDown(); update.join(2000);
        check(!update.isAlive(), "download thread finishes");
        check(result.get() instanceof IOException, "stale batch rejected");
        check("user-whitelist".equals(revision.get()), "current configuration preserved");
        try (RuleUpdateGate.Lease lease = gate.begin()) { check(true, "new update allowed after stale result discarded"); }
        System.out.println("PASS: rule update gate " + checks + " assertions");
    }
}
