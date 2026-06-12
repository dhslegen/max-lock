package com.dahaoshen.maxlock.local;

import com.dahaoshen.maxlock.core.DistributedLock;
import com.dahaoshen.maxlock.core.LockHandle;
import com.dahaoshen.maxlock.core.LockSpec;
import com.dahaoshen.maxlock.exception.LockException;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 本地 JVM 锁实现：无 Redis 环境的降级方案。
 * <p>
 * 注意：仅保证单进程内互斥，集群部署时不具备分布式语义；
 * leaseTime 被忽略（JVM 锁随持有线程显式释放或进程退出而释放）。
 *
 * @author zhaowenhao
 * @since 2026-06-12
 */
@Slf4j
public class LocalJvmDistributedLock implements DistributedLock {

    /** fullKey -> 锁条目；引用计数归零时移除，防止 map 无界增长 */
    private final ConcurrentMap<String, LockEntry> locks = new ConcurrentHashMap<>();

    private final AtomicBoolean leaseWarned = new AtomicBoolean(false);

    static final class LockEntry {
        final ReentrantLock lock;
        int refCount;

        LockEntry(boolean fair) {
            this.lock = new ReentrantLock(fair);
        }
    }

    @Override
    public LockHandle lock(LockSpec spec) {
        warnIfLeaseTimeSet(spec);
        String fullKey = spec.fullKey();
        LockEntry entry = acquireEntry(fullKey, spec.isFair());
        try {
            entry.lock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            releaseEntry(fullKey);
            throw LockException.interrupted(fullKey, e);
        }
        return new LocalLockHandle(fullKey, entry);
    }

    @Override
    public LockHandle tryLock(LockSpec spec) {
        warnIfLeaseTimeSet(spec);
        String fullKey = spec.fullKey();
        LockEntry entry = acquireEntry(fullKey, spec.isFair());
        boolean acquired;
        try {
            Duration wait = spec.getWaitTime();
            if (wait == null || wait.isZero()) {
                acquired = entry.lock.tryLock();
            } else {
                acquired = entry.lock.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            releaseEntry(fullKey);
            return null;
        }
        return new LocalLockHandle(fullKey, entry);
    }

    @Override
    public boolean isLocked(LockSpec spec) {
        LockEntry entry = locks.get(spec.fullKey());
        return entry != null && entry.lock.isLocked();
    }

    @Override
    public boolean forceUnlock(LockSpec spec) {
        LockEntry entry = locks.get(spec.fullKey());
        if (entry == null || !entry.lock.isLocked()) {
            return false;
        }
        if (!entry.lock.isHeldByCurrentThread()) {
            // ReentrantLock 无法从非持有线程释放
            log.warn("本地 JVM 锁不支持跨线程强制释放: {}", spec.fullKey());
            return false;
        }
        // 解开全部重入层级，并归还对应的引用计数
        while (entry.lock.isHeldByCurrentThread()) {
            entry.lock.unlock();
            releaseEntry(spec.fullKey());
        }
        return true;
    }

    /** 当前内部条目数量（测试与监控用） */
    public int entryCount() {
        return locks.size();
    }

    private void warnIfLeaseTimeSet(LockSpec spec) {
        if (spec.getLeaseTime() != null && leaseWarned.compareAndSet(false, true)) {
            log.warn("本地 JVM 锁不支持 leaseTime，将忽略该配置（仅提示一次）");
        }
    }

    private LockEntry acquireEntry(String fullKey, boolean fair) {
        return locks.compute(fullKey, (k, entry) -> {
            if (entry == null) {
                entry = new LockEntry(fair);
            } else if (entry.lock.isFair() != fair) {
                log.warn("锁 {} 已以 fair={} 创建，忽略本次 fair={} 请求", k, entry.lock.isFair(), fair);
            }
            entry.refCount++;
            return entry;
        });
    }

    private void releaseEntry(String fullKey) {
        locks.computeIfPresent(fullKey, (k, entry) -> --entry.refCount <= 0 ? null : entry);
    }

    /** 本地锁句柄 */
    private final class LocalLockHandle implements LockHandle {

        private final String fullKey;
        private final LockEntry entry;
        private final long startNanos = System.nanoTime();
        private final AtomicBoolean released = new AtomicBoolean(false);
        private volatile long releasedNanos;

        private LocalLockHandle(String fullKey, LockEntry entry) {
            this.fullKey = fullKey;
            this.entry = entry;
        }

        @Override
        public String getLockKey() {
            return fullKey;
        }

        @Override
        public boolean isReleased() {
            return released.get();
        }

        @Override
        public boolean isWatchdogEnabled() {
            return false;
        }

        @Override
        public Duration getHoldDuration() {
            long end = released.get() ? releasedNanos : System.nanoTime();
            return Duration.ofNanos(end - startNanos);
        }

        @Override
        public void release() {
            if (released.compareAndSet(false, true)) {
                releasedNanos = System.nanoTime();
                // releaseEntry 与 unlock 同处守卫内：若锁已被 forceUnlock 提前释放
                // （此时本线程不再持有），则计数已归还，避免重复递减 refCount
                if (entry.lock.isHeldByCurrentThread()) {
                    entry.lock.unlock();
                    releaseEntry(fullKey);
                }
            }
        }
    }
}
