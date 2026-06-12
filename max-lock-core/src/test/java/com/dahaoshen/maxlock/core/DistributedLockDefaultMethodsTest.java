package com.dahaoshen.maxlock.core;

import com.dahaoshen.maxlock.exception.LockException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DistributedLockDefaultMethodsTest {

    /** 最小桩：单把 ReentrantLock 模拟 */
    static class StubLock implements DistributedLock {
        final ReentrantLock lock = new ReentrantLock();
        String lastFullKey;

        @Override
        public LockHandle lock(LockSpec spec) {
            lastFullKey = spec.fullKey();
            lock.lock();
            return handle(spec.fullKey());
        }

        @Override
        public LockHandle tryLock(LockSpec spec) {
            lastFullKey = spec.fullKey();
            return lock.tryLock() ? handle(spec.fullKey()) : null;
        }

        @Override
        public boolean isLocked(LockSpec spec) { return lock.isLocked(); }

        @Override
        public boolean forceUnlock(LockSpec spec) { return false; }

        private LockHandle handle(String fullKey) {
            AtomicBoolean released = new AtomicBoolean(false);
            return new LockHandle() {
                @Override public String getLockKey() { return fullKey; }
                @Override public boolean isReleased() { return released.get(); }
                @Override public boolean isWatchdogEnabled() { return false; }
                @Override public Duration getHoldDuration() { return Duration.ZERO; }
                @Override public void release() {
                    if (released.compareAndSet(false, true)) lock.unlock();
                }
            };
        }
    }

    @Test
    void execute应执行动作并在结束后释放锁() {
        StubLock stub = new StubLock();
        String result = stub.execute(LockSpec.of("k"), () -> "ok");
        assertThat(result).isEqualTo("ok");
        assertThat(stub.lock.isLocked()).isFalse();
    }

    @Test
    void execute动作抛运行时异常时原样抛出且释放锁() {
        StubLock stub = new StubLock();
        assertThatThrownBy(() -> stub.execute(LockSpec.of("k"), () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(stub.lock.isLocked()).isFalse();
    }

    @Test
    void execute动作抛受检异常时包装为LockException() {
        StubLock stub = new StubLock();
        assertThatThrownBy(() -> stub.execute(LockSpec.of("k"), () -> { throw new Exception("checked"); }))
                .isInstanceOf(LockException.class)
                .hasMessageContaining("checked");
        assertThat(stub.lock.isLocked()).isFalse();
    }

    @Test
    void tryExecute锁被占用时走fallback() throws InterruptedException {
        StubLock stub = new StubLock();
        // 由子线程确定性地持锁（ReentrantLock 可重入，必须跨线程占用才能让主线程 tryLock 失败）
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            stub.lock.lock();
            try {
                acquired.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                stub.lock.unlock();
            }
        });
        holder.start();
        acquired.await();   // 确保子线程已真正持锁，无竞态窗口

        String result = stub.tryExecute(LockSpec.of("k"), () -> "got", () -> "fallback");
        assertThat(result).isEqualTo("fallback");

        release.countDown();
        holder.join();      // 自清理：等待子线程退出，避免线程泄漏
        assertThat(stub.lock.isLocked()).isFalse();
    }

    @Test
    void 字符串与Runnable便捷重载应生效() {
        StubLock stub = new StubLock();
        AtomicBoolean ran = new AtomicBoolean(false);
        stub.execute("simple-key", () -> ran.set(true));
        assertThat(ran).isTrue();
        assertThat(stub.lastFullKey).isEqualTo("LOCK:simple-key");
        assertThat(stub.lock.isLocked()).isFalse();
    }
}
