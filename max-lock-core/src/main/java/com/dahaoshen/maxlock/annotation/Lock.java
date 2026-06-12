package com.dahaoshen.maxlock.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.temporal.ChronoUnit;

/**
 * 声明式分布式锁注解
 *
 * <pre>
 * &#64;Lock(key = "order:#{#orderId}", waitTime = 3, timeoutMessage = "订单处理中，请稍后")
 * public void process(Long orderId) { ... }
 * </pre>
 *
 * @author zhaowenhao
 * @since 2026-06-12
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Lock {

    /**
     * 锁的 key，支持 SpEL：模板风格 "user:#{#userId}" 或纯表达式 "#user.name"
     */
    String key();

    /**
     * 最大等待时间数值：-1 = 阻塞等待；0 = 立即尝试；>0 = 等待对应时长
     */
    long waitTime() default 10;

    /** 等待时间单位 */
    ChronoUnit waitTimeUnit() default ChronoUnit.SECONDS;

    /**
     * 锁持有时间数值：-1 = 启用 Watchdog 自动续期
     */
    long leaseTime() default -1;

    /** 持有时间单位 */
    ChronoUnit leaseTimeUnit() default ChronoUnit.SECONDS;

    /** 是否公平锁 */
    boolean fair() default false;

    /** 锁前缀；空字符串表示使用全局默认前缀 */
    String prefix() default "";

    /** 获取锁超时/失败时的异常消息 */
    String timeoutMessage() default "获取锁超时，请稍后重试";
}
