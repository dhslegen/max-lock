package com.dahaoshen.maxlock.aspect;

import com.dahaoshen.maxlock.annotation.Lock;
import com.dahaoshen.maxlock.core.DistributedLock;
import com.dahaoshen.maxlock.core.LockSpec;
import com.dahaoshen.maxlock.exception.LockException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * {@link Lock} 注解切面：解析 SpEL key，构建 {@link LockSpec}，
 * 通过 {@link DistributedLock} 的函数式 API 执行目标方法。
 *
 * @author zhaowenhao
 * @since 2026-06-12
 */
@Slf4j
@Aspect
public class LockAspect {

    private final DistributedLock distributedLock;

    /** @Lock.prefix 为空时使用的全局默认前缀 */
    private final String defaultPrefix;

    private final LockKeyResolver keyResolver = new LockKeyResolver();

    public LockAspect(DistributedLock distributedLock, String defaultPrefix) {
        if (distributedLock == null) {
            throw LockException.invalidConfig("DistributedLock 实例不能为 null");
        }
        this.distributedLock = distributedLock;
        this.defaultPrefix = defaultPrefix == null ? LockSpec.DEFAULT_PREFIX : defaultPrefix;
    }

    @Around("@within(lockAnnotation) || @annotation(lockAnnotation)")
    public Object aroundLock(ProceedingJoinPoint point, Lock lockAnnotation) {
        Lock lock = resolveAnnotation(lockAnnotation, point);
        LockSpec spec = buildSpec(lock, point);
        Callable<Object> action = toCallable(point);

        log.debug("申请分布式锁: key={}, method={}", spec.fullKey(), point.getSignature().toShortString());

        if (lock.waitTime() < 0) {
            // 阻塞等待
            return distributedLock.execute(spec, action);
        }
        // 立即尝试 / 限时等待，失败抛超时异常
        return distributedLock.tryExecute(spec, action, () -> {
            throw LockException.timeout(spec.fullKey(), lock.timeoutMessage());
        });
    }

    private Lock resolveAnnotation(Lock lock, ProceedingJoinPoint point) {
        if (lock != null) {
            return lock;
        }
        Lock classLevel = point.getTarget().getClass().getDeclaredAnnotation(Lock.class);
        if (classLevel == null) {
            throw LockException.invalidConfig("未找到 @Lock 注解");
        }
        return classLevel;
    }

    private LockSpec buildSpec(Lock lock, ProceedingJoinPoint point) {
        MethodSignature signature = (MethodSignature) point.getSignature();
        String key = keyResolver.resolve(lock.key(), signature.getMethod(), point.getArgs());
        if (key == null || key.trim().isEmpty()) {
            throw LockException.invalidConfig("锁 key 解析结果为空: " + lock.key());
        }

        String prefix = lock.prefix().isEmpty() ? defaultPrefix : lock.prefix();
        LockSpec spec = LockSpec.of(key).prefix(prefix);
        if (lock.fair()) {
            spec = spec.fair();
        }
        if (lock.waitTime() >= 0) {
            spec = spec.waitTime(Duration.of(lock.waitTime(), lock.waitTimeUnit()));
        }
        if (lock.leaseTime() > 0) {
            spec = spec.leaseTime(Duration.of(lock.leaseTime(), lock.leaseTimeUnit()));
        }
        return spec;
    }

    private Callable<Object> toCallable(ProceedingJoinPoint point) {
        return () -> {
            try {
                return point.proceed();
            } catch (Exception e) {
                // Callable 声明 throws Exception，受检/运行时异常原样上抛由 DistributedLock.invoke 处理
                throw e;
            } catch (Error e) {
                // JVM 严重错误（OOM、StackOverflow 等）必须原样传播，不得包装
                throw e;
            } catch (Throwable t) {
                throw new LockException("方法执行异常: " + t.getMessage(), t);
            }
        };
    }
}
