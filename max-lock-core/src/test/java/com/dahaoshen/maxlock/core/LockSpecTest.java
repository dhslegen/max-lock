package com.dahaoshen.maxlock.core;

import com.dahaoshen.maxlock.exception.LockException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LockSpecTest {

    @Test
    void 默认值_非公平_默认前缀_无等待与持有时间() {
        LockSpec spec = LockSpec.of("order:1001");
        assertThat(spec.getKey()).isEqualTo("order:1001");
        assertThat(spec.getPrefix()).isEqualTo(LockSpec.DEFAULT_PREFIX);
        assertThat(spec.isFair()).isFalse();
        assertThat(spec.getWaitTime()).isNull();
        assertThat(spec.getLeaseTime()).isNull();
        assertThat(spec.fullKey()).isEqualTo("LOCK:order:1001");
    }

    @Test
    void 流式配置返回新实例_原实例不变() {
        LockSpec base = LockSpec.of("k");
        LockSpec configured = base.prefix("BIZ").fair()
                .waitTime(Duration.ofSeconds(3))
                .leaseTime(Duration.ofSeconds(30));

        assertThat(configured.fullKey()).isEqualTo("BIZ:k");
        assertThat(configured.isFair()).isTrue();
        assertThat(configured.getWaitTime()).isEqualTo(Duration.ofSeconds(3));
        assertThat(configured.getLeaseTime()).isEqualTo(Duration.ofSeconds(30));

        // 不可变性：base 未被修改
        assertThat(base.getPrefix()).isEqualTo(LockSpec.DEFAULT_PREFIX);
        assertThat(base.isFair()).isFalse();
        assertThat(base.getWaitTime()).isNull();
        assertThat(base.getLeaseTime()).isNull();
    }

    @Test
    void 空前缀时fullKey不带冒号() {
        assertThat(LockSpec.of("k").prefix("").fullKey()).isEqualTo("k");
    }

    @Test
    void key为空白串时抛配置异常() {
        assertThatThrownBy(() -> LockSpec.of(" "))
                .isInstanceOf(LockException.class)
                .hasMessageContaining("key");
    }

    @Test
    void key为null时抛配置异常() {
        assertThatThrownBy(() -> LockSpec.of(null))
                .isInstanceOf(LockException.class)
                .hasMessageContaining("key");
    }
}
