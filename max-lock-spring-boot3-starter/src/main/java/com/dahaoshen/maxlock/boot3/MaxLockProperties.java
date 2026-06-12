package com.dahaoshen.maxlock.boot3;

import com.dahaoshen.maxlock.core.LockSpec;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * max-lock 配置属性
 *
 * @author zhaowenhao
 * @since 2026-06-12
 */
@Data
@ConfigurationProperties(prefix = MaxLockProperties.PREFIX)
public class MaxLockProperties {

    public static final String PREFIX = "max.lock";

    /** 锁提供者 */
    public enum Provider {
        /** 自动：有 Redisson 用 Redis 锁，否则降级本地 JVM 锁 */
        AUTO,
        /** 强制 Redisson（缺少依赖时启动报错） */
        REDISSON,
        /** 强制本地 JVM 锁 */
        LOCAL,
        /** 不装配任何锁 */
        NONE
    }

    /** 锁提供者，默认 AUTO */
    private Provider provider = Provider.AUTO;

    /** @Lock 注解未指定 prefix 时的全局默认前缀 */
    private String keyPrefix = LockSpec.DEFAULT_PREFIX;

    /** 是否启用 @Lock 注解切面 */
    private boolean aspectEnabled = true;
}
