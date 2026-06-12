package com.dahaoshen.maxlock.aspect;

import com.dahaoshen.maxlock.exception.LockException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LockKeyResolverTest {

    private final LockKeyResolver resolver = new LockKeyResolver();

    @SuppressWarnings("unused")
    static class Sample {
        public void byId(Long userId) { }
        public void byUser(User user) { }
    }

    static class User {
        private final String name;
        User(String name) { this.name = name; }
        public String getName() { return name; }
    }

    private Method method(String name, Class<?>... types) throws Exception {
        return Sample.class.getMethod(name, types);
    }

    @Test
    void 无井号的普通字符串原样返回() throws Exception {
        String key = resolver.resolve("static-key", method("byId", Long.class), new Object[]{1L});
        assertThat(key).isEqualTo("static-key");
    }

    @Test
    void 模板风格表达式_字面量与变量混合() throws Exception {
        String key = resolver.resolve("user:#{#userId}", method("byId", Long.class), new Object[]{42L});
        assertThat(key).isEqualTo("user:42");
    }

    @Test
    void 纯SpEL表达式_对象属性导航() throws Exception {
        String key = resolver.resolve("#user.name", method("byUser", User.class), new Object[]{new User("alice")});
        assertThat(key).isEqualTo("alice");
    }

    @Test
    void 内置变量methodName与className() throws Exception {
        String key = resolver.resolve("#{#className}.#{#methodName}", method("byId", Long.class), new Object[]{1L});
        assertThat(key).isEqualTo("Sample.byId");
    }

    @Test
    void 对null对象属性导航抛异常时包装为LockException且保留cause() throws Exception {
        assertThatThrownBy(() -> resolver.resolve("#user.name", method("byUser", User.class), new Object[]{null}))
                .isInstanceOf(LockException.class)
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    void 表达式求值结果本身为null时抛配置异常() throws Exception {
        // 引用一个不存在的变量，SpEL 求值返回 null，走 value == null 检查分支
        assertThatThrownBy(() -> resolver.resolve("#missingVar", method("byId", Long.class), new Object[]{1L}))
                .isInstanceOf(LockException.class)
                .hasMessageContaining("null");
    }
}
