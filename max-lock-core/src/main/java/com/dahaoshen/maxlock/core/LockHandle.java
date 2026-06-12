package com.dahaoshen.maxlock.core;

import java.time.Duration;

/**
 * 锁句柄：持有已获取的锁，支持 try-with-resources 自动释放
 *
 * @author zhaowenhao
 * @since 2026-06-12
 */
public interface LockHandle extends AutoCloseable {

    /** 完整锁 key（含前缀） */
    String getLockKey();

    /** 锁是否已释放 */
    boolean isReleased();

    /** 是否启用了 Watchdog 自动续期 */
    boolean isWatchdogEnabled();

    /** 从获取到当前（或释放时刻）的持有时长 */
    Duration getHoldDuration();

    /** 释放锁（幂等，重复调用无副作用） */
    void release();

    @Override
    default void close() {
        release();
    }
}
