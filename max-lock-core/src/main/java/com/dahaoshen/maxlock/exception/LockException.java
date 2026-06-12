package com.dahaoshen.maxlock.exception;

import lombok.Getter;

/**
 * 分布式锁异常
 *
 * @author zhaowenhao
 * @since 2026-06-12
 */
@Getter
public class LockException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 异常类型
     */
    public enum Type {
        /** 获取锁超时 */
        ACQUIRE_TIMEOUT,
        /** 锁被占用 */
        LOCK_BUSY,
        /** 等待锁时被中断 */
        INTERRUPTED,
        /** 配置错误 */
        INVALID_CONFIG,
        /** 业务执行异常（execute 包装受检异常） */
        EXECUTION_FAILED,
        /** 未知 */
        UNKNOWN
    }

    private final Type type;

    /** 关联的完整锁 key，可能为 null */
    private final String lockKey;

    public LockException(String message) {
        this(Type.UNKNOWN, null, message, null);
    }

    public LockException(String message, Throwable cause) {
        this(Type.UNKNOWN, null, message, cause);
    }

    public LockException(Type type, String lockKey, String message) {
        this(type, lockKey, message, null);
    }

    public LockException(Type type, String lockKey, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
        this.lockKey = lockKey;
    }

    public static LockException timeout(String lockKey, String message) {
        return new LockException(Type.ACQUIRE_TIMEOUT, lockKey, message);
    }

    public static LockException busy(String lockKey, String message) {
        return new LockException(Type.LOCK_BUSY, lockKey, message);
    }

    public static LockException interrupted(String lockKey, Throwable cause) {
        return new LockException(Type.INTERRUPTED, lockKey, "等待锁时被中断: " + lockKey, cause);
    }

    public static LockException invalidConfig(String message) {
        return new LockException(Type.INVALID_CONFIG, null, message);
    }

    public static LockException invalidConfig(String message, Throwable cause) {
        return new LockException(Type.INVALID_CONFIG, null, message, cause);
    }

    public static LockException executionFailed(String lockKey, Throwable cause) {
        String reason = cause == null ? "未知原因" : cause.getMessage();
        return new LockException(Type.EXECUTION_FAILED, lockKey, "锁内业务执行异常: " + reason, cause);
    }
}
