package com.dahaoshen.maxlock.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LockExceptionTest {

    @Test
    void 工厂方法应携带类型与锁key() {
        LockException timeout = LockException.timeout("LOCK:order:1", "获取锁超时");
        assertThat(timeout.getType()).isEqualTo(LockException.Type.ACQUIRE_TIMEOUT);
        assertThat(timeout.getLockKey()).isEqualTo("LOCK:order:1");
        assertThat(timeout.getMessage()).isEqualTo("获取锁超时");

        LockException config = LockException.invalidConfig("key 不能为空");
        assertThat(config.getType()).isEqualTo(LockException.Type.INVALID_CONFIG);
        assertThat(config.getLockKey()).isNull();
    }

    @Test
    void 默认构造类型为UNKNOWN且可携带cause() {
        RuntimeException cause = new RuntimeException("boom");
        LockException e = new LockException("失败", cause);
        assertThat(e.getType()).isEqualTo(LockException.Type.UNKNOWN);
        assertThat(e.getCause()).isSameAs(cause);
    }

    @Test
    void busy工厂方法应携带LOCK_BUSY类型() {
        LockException busy = LockException.busy("LOCK:k", "锁被占用");
        assertThat(busy.getType()).isEqualTo(LockException.Type.LOCK_BUSY);
        assertThat(busy.getLockKey()).isEqualTo("LOCK:k");
        assertThat(busy.getMessage()).isEqualTo("锁被占用");
    }

    @Test
    void interrupted工厂方法应携带类型_cause与拼接消息() {
        InterruptedException cause = new InterruptedException();
        LockException e = LockException.interrupted("LOCK:k", cause);
        assertThat(e.getType()).isEqualTo(LockException.Type.INTERRUPTED);
        assertThat(e.getLockKey()).isEqualTo("LOCK:k");
        assertThat(e.getMessage()).contains("LOCK:k");
        assertThat(e.getCause()).isSameAs(cause);
    }

    @Test
    void executionFailed应携带类型并在cause为null时不抛异常() {
        RuntimeException cause = new RuntimeException("boom");
        LockException e = LockException.executionFailed("LOCK:k", cause);
        assertThat(e.getType()).isEqualTo(LockException.Type.EXECUTION_FAILED);
        assertThat(e.getMessage()).contains("boom");
        assertThat(e.getCause()).isSameAs(cause);

        // null cause 不应抛 NPE
        LockException nullCause = LockException.executionFailed("LOCK:k", null);
        assertThat(nullCause.getMessage()).contains("未知原因");
    }
}
