package com.dahaoshen.maxlock.core;

import com.dahaoshen.maxlock.exception.LockException;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;
import java.time.Duration;

/**
 * 锁参数对象（不可变），用流式构建收敛"公平/等待/持有/前缀"等正交维度，
 * 替代重载爆炸的接口设计。
 *
 * <pre>
 * LockSpec.of("order:1001").prefix("BIZ").fair()
 *         .waitTime(Duration.ofSeconds(3))
 *         .leaseTime(Duration.ofSeconds(30));
 * </pre>
 *
 * <ul>
 *   <li>waitTime == null：阻塞等待（lock 语义）；Duration.ZERO：立即尝试（tryLock 语义）</li>
 *   <li>leaseTime == null：启用 Watchdog 自动续期（实现支持时）</li>
 * </ul>
 *
 * @author zhaowenhao
 * @since 2026-06-12
 */
@Getter
@ToString
@EqualsAndHashCode
public final class LockSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 默认锁前缀 */
    public static final String DEFAULT_PREFIX = "LOCK";

    private final String key;
    private final String prefix;
    private final boolean fair;
    private final Duration waitTime;
    private final Duration leaseTime;

    private LockSpec(String key, String prefix, boolean fair, Duration waitTime, Duration leaseTime) {
        this.key = key;
        this.prefix = prefix;
        this.fair = fair;
        this.waitTime = waitTime;
        this.leaseTime = leaseTime;
    }

    /**
     * 创建锁参数，默认：前缀 LOCK、非公平、阻塞等待、Watchdog 续期
     *
     * @param key 锁的业务 key，不能为空
     */
    public static LockSpec of(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw LockException.invalidConfig("锁 key 不能为空");
        }
        return new LockSpec(key, DEFAULT_PREFIX, false, null, null);
    }

    /** 设置锁前缀，传空字符串则无前缀 */
    public LockSpec prefix(String prefix) {
        return new LockSpec(key, prefix == null ? "" : prefix, fair, waitTime, leaseTime);
    }

    /** 使用公平锁 */
    public LockSpec fair() {
        return new LockSpec(key, prefix, true, waitTime, leaseTime);
    }

    /** 最大等待时间；Duration.ZERO 表示立即尝试 */
    public LockSpec waitTime(Duration waitTime) {
        return new LockSpec(key, prefix, fair, waitTime, leaseTime);
    }

    /** 锁持有时间；不设置则启用 Watchdog */
    public LockSpec leaseTime(Duration leaseTime) {
        return new LockSpec(key, prefix, fair, waitTime, leaseTime);
    }

    /** 完整锁 key：{prefix}:{key}，前缀为空时仅 key */
    public String fullKey() {
        return prefix == null || prefix.isEmpty() ? key : prefix + ":" + key;
    }
}
