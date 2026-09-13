package io.github.xgl34222220.bichen;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-flight downloads without holding the rule configuration lock. */
public final class RuleUpdateGate {
    private final Semaphore permit = new Semaphore(1);
    public Lease begin() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("规则更新已取消");
        if (!permit.tryAcquire()) throw new IOException("已有规则更新正在进行；未重复启动下载");
        return new Lease();
    }
    public final class Lease implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease() { }
        public void verify(String expected, String actual) throws IOException {
            if (closed.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("规则更新已取消");
            if (!Objects.equals(expected, actual))
                throw new IOException("下载期间规则配置已变更；保留当前名单和规则，请重新更新");
        }
        @Override public void close() { if (closed.compareAndSet(false, true)) permit.release(); }
    }
}
