package com.dahaoshen.maxlock.core;

import com.dahaoshen.maxlock.exception.LockException;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 分布式锁统一抽象。
 * <p>
 * 实现方只需提供 4 个抽象方法（lock / tryLock / isLocked / forceUnlock），
 * 函数式 API（execute / tryExecute）与字符串便捷重载由 default 方法提供，
 * 锁的生命周期自动管理。
 *
 * @author zhaowenhao
 * @since 2026-06-12
 */
public interface DistributedLock {

    // ==================== 实现方必须提供的 4 个抽象方法 ====================

    /**
     * 阻塞获取锁（忽略 spec.waitTime）
     *
     * @return 锁句柄，配合 try-with-resources 使用
     * @throws LockException 被中断等失败场景
     */
    LockHandle lock(LockSpec spec);

    /**
     * 尝试获取锁：spec.waitTime 为 null 或 ZERO 时立即尝试，否则最多等待 waitTime
     *
     * @return 锁句柄；获取失败返回 null
     */
    LockHandle tryLock(LockSpec spec);

    /** 锁当前是否被任意持有者占用 */
    boolean isLocked(LockSpec spec);

    /** 强制释放锁（谨慎使用），不支持或失败返回 false */
    boolean forceUnlock(LockSpec spec);

    // ==================== 函数式 API（default） ====================

    /** 阻塞获取锁并执行动作，结束后自动释放 */
    default <T> T execute(LockSpec spec, Callable<T> action) {
        LockHandle handle = lock(spec);
        try {
            return invoke(spec.fullKey(), action);
        } finally {
            handle.release();
        }
    }

    /** 尝试获取锁并执行动作；获取失败时返回 fallback 的值 */
    default <T> T tryExecute(LockSpec spec, Callable<T> action, Supplier<T> fallback) {
        LockHandle handle = tryLock(spec);
        if (handle == null) {
            return fallback.get();
        }
        try {
            return invoke(spec.fullKey(), action);
        } finally {
            handle.release();
        }
    }

    // ==================== 字符串 / Runnable 便捷重载（default） ====================

    default LockHandle lock(String key) {
        return lock(LockSpec.of(key));
    }

    default LockHandle tryLock(String key) {
        return tryLock(LockSpec.of(key));
    }

    default <T> T execute(String key, Callable<T> action) {
        return execute(LockSpec.of(key), action);
    }

    default void execute(String key, Runnable action) {
        execute(LockSpec.of(key), toCallable(action));
    }

    default void execute(LockSpec spec, Runnable action) {
        execute(spec, toCallable(action));
    }

    default <T> T tryExecute(String key, Callable<T> action, Supplier<T> fallback) {
        return tryExecute(LockSpec.of(key), action, fallback);
    }

    default boolean isLocked(String key) {
        return isLocked(LockSpec.of(key));
    }

    default boolean forceUnlock(String key) {
        return forceUnlock(LockSpec.of(key));
    }

    // ==================== 静态辅助（Java 8 接口无 private 方法） ====================

    /** Runnable 转 Callable */
    static Callable<Object> toCallable(Runnable action) {
        return () -> {
            action.run();
            return null;
        };
    }

    /** 执行动作：运行时异常原样抛出，受检异常包装为 LockException */
    static <T> T invoke(String fullKey, Callable<T> action) {
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw LockException.executionFailed(fullKey, e);
        }
    }
}
