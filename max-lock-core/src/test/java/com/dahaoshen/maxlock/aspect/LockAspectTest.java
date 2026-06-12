package com.dahaoshen.maxlock.aspect;

import com.dahaoshen.maxlock.annotation.Lock;
import com.dahaoshen.maxlock.core.DistributedLock;
import com.dahaoshen.maxlock.exception.LockException;
import com.dahaoshen.maxlock.local.LocalJvmDistributedLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.util.AopTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LockAspectTest {

    @Configuration
    @EnableAspectJAutoProxy
    static class TestConfig {
        @Bean
        public DistributedLock distributedLock() {
            return new LocalJvmDistributedLock();
        }

        @Bean
        public LockAspect lockAspect(DistributedLock distributedLock) {
            return new LockAspect(distributedLock, "GLOBAL");
        }

        @Bean
        public SampleService sampleService() {
            return new SampleService();
        }
    }

    static class SampleService {
        volatile String observedKey;
        volatile DistributedLock lockRef;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Lock(key = "user:#{#userId}")
        public String resolveKey(Long userId) {
            // 切面已加锁，此处探测完整 key 是否被锁定
            observedKey = "GLOBAL:user:" + userId;
            return lockRef.isLocked("user:" + userId) ? "unlocked?" : probe(userId);
        }

        private String probe(Long userId) {
            // isLocked(String) 用默认前缀 LOCK，这里直接用 LockSpec 探测 GLOBAL 前缀
            boolean locked = lockRef.isLocked(
                    com.dahaoshen.maxlock.core.LockSpec.of("user:" + userId).prefix("GLOBAL"));
            return locked ? "locked-during-execution" : "not-locked";
        }

        @Lock(key = "slow", waitTime = 0, timeoutMessage = "资源忙")
        public String slowOperation() throws InterruptedException {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return "done";
        }

        @Lock(key = "prefixed", prefix = "BIZ")
        public String withCustomPrefix() {
            return probeFull("BIZ:prefixed");
        }

        private String probeFull(String fullKey) {
            boolean locked = lockRef.isLocked(
                    com.dahaoshen.maxlock.core.LockSpec.of(fullKey).prefix(""));
            return locked ? "locked" : "not-locked";
        }
    }

    private AnnotationConfigApplicationContext context;
    private SampleService service;
    /** CGLIB 代理背后的真实目标对象，字段赋值须在此操作 */
    private SampleService target;
    private DistributedLock lock;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        service = context.getBean(SampleService.class);
        target = AopTestUtils.getTargetObject(service);
        lock = context.getBean(DistributedLock.class);
        target.lockRef = lock;
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void SpEL解析key_方法执行期间锁定_结束后释放() {
        String result = service.resolveKey(42L);
        assertThat(result).isEqualTo("locked-during-execution");
        assertThat(lock.isLocked(
                com.dahaoshen.maxlock.core.LockSpec.of("user:42").prefix("GLOBAL"))).isFalse();
    }

    @Test
    void 注解prefix优先于全局默认前缀() {
        assertThat(service.withCustomPrefix()).isEqualTo("locked");
    }

    @Test
    void waitTime为0时锁被占用立即抛超时异常() throws Exception {
        Thread holder = new Thread(() -> {
            try {
                service.slowOperation();
            } catch (Exception ignored) {
            }
        });
        holder.start();
        assertThat(target.entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.slowOperation())
                .isInstanceOf(LockException.class)
                .hasMessageContaining("资源忙");

        target.release.countDown();
        holder.join();
    }
}
