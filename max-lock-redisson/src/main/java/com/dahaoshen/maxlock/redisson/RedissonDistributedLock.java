package com.dahaoshen.maxlock.redisson;

import com.dahaoshen.maxlock.core.DistributedLock;
import com.dahaoshen.maxlock.core.LockHandle;
import com.dahaoshen.maxlock.core.LockSpec;
import com.dahaoshen.maxlock.exception.LockException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Redisson 的分布式锁实现。
 * <p>
 * leaseTime 未设置时启用 Redisson Watchdog 自动续期；
 * fair() 时使用公平锁（按申请顺序获取）。
 *
 * @author zhaowenhao
 * @since 2026-06-12
 */
@Slf4j
public class RedissonDistributedLock implements DistributedLock {

    private final RedissonClient redissonClient;

    public RedissonDistributedLock(RedissonClient redissonClient) {
        if (redissonClient == null) {
            throw LockException.invalidConfig("RedissonClient 不能为 null");
        }
        this.redissonClient = redissonClient;
    }

    @Override
    public LockHandle lock(LockSpec spec) {
        RLock rLock = getRLock(spec);
        boolean watchdog = spec.getLeaseTime() == null;
        try {
            if (watchdog) {
                rLock.lockInterruptibly();
            } else {
                rLock.lockInterruptibly(spec.getLeaseTime().toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw LockException.interrupted(spec.fullKey(), e);
        }
        return new RedissonLockHandle(spec.fullKey(), rLock, watchdog);
    }

    @Override
    public LockHandle tryLock(LockSpec spec) {
        RLock rLock = getRLock(spec);
        boolean watchdog = spec.getLeaseTime() == null;
        long waitMillis = spec.getWaitTime() == null ? 0 : spec.getWaitTime().toMillis();
        // Redisson 约定：leaseTime = -1 表示启用 Watchdog
        long leaseMillis = watchdog ? -1 : spec.getLeaseTime().toMillis();
        boolean acquired;
        try {
            acquired = rLock.tryLock(waitMillis, leaseMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return acquired ? new RedissonLockHandle(spec.fullKey(), rLock, watchdog) : null;
    }

    @Override
    public boolean isLocked(LockSpec spec) {
        // 必须按 fair 标志取对应的 RLock：公平锁在 Redis 中使用额外的队列/超时结构
        return getRLock(spec).isLocked();
    }

    @Override
    public boolean forceUnlock(LockSpec spec) {
        log.warn("强制释放分布式锁: {}", spec.fullKey());
        return getRLock(spec).forceUnlock();
    }

    private RLock getRLock(LockSpec spec) {
        return spec.isFair()
                ? redissonClient.getFairLock(spec.fullKey())
                : redissonClient.getLock(spec.fullKey());
    }

    /** Redisson 锁句柄 */
    private static final class RedissonLockHandle implements LockHandle {

        private final String fullKey;
        private final RLock rLock;
        private final boolean watchdog;
        private final long startNanos = System.nanoTime();
        private final AtomicBoolean released = new AtomicBoolean(false);
        private volatile long releasedNanos;

        private RedissonLockHandle(String fullKey, RLock rLock, boolean watchdog) {
            this.fullKey = fullKey;
            this.rLock = rLock;
            this.watchdog = watchdog;
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
            return watchdog;
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
                if (rLock.isHeldByCurrentThread()) {
                    rLock.unlock();
                }
            }
        }
    }
}
