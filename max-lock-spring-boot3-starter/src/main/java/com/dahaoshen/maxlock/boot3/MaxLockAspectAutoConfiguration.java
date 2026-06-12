package com.dahaoshen.maxlock.boot3;

import com.dahaoshen.maxlock.aspect.LockAspect;
import com.dahaoshen.maxlock.core.DistributedLock;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * max-lock 切面自动装配（Spring Boot 3.x）。
 * <p>
 * 独立于 {@link MaxLockAutoConfiguration} 并通过 {@code @AutoConfiguration(after=...)} 保证在
 * DistributedLock Bean 注册之后处理，使 {@code @ConditionalOnBean(DistributedLock.class)}
 * 可靠生效——只要容器存在锁 Bean（框架装配或用户自定义）且未关闭切面，即装配 LockAspect。
 *
 * @author zhaowenhao
 * @since 2026-06-12
 */
@AutoConfiguration(after = MaxLockAutoConfiguration.class)
@ConditionalOnClass(ProceedingJoinPoint.class)
@ConditionalOnProperty(prefix = MaxLockProperties.PREFIX, name = "aspect-enabled",
        havingValue = "true", matchIfMissing = true)
public class MaxLockAspectAutoConfiguration {

    @Bean
    @ConditionalOnBean(DistributedLock.class)
    @ConditionalOnMissingBean(LockAspect.class)
    public LockAspect maxLockAspect(DistributedLock distributedLock, MaxLockProperties properties) {
        return new LockAspect(distributedLock, properties.getKeyPrefix());
    }
}
