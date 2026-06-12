package com.dahaoshen.maxlock.aspect;

import com.dahaoshen.maxlock.exception.LockException;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;

/**
 * 锁 key 的 SpEL 解析器。
 * <p>
 * 支持两种写法：
 * <ul>
 *   <li>模板风格：{@code "user:#{#userId}"}（含 #{ } 时按模板解析，字面量与表达式混排）</li>
 *   <li>纯表达式：{@code "#user.name"}（含 # 但无 #{ } 时按纯 SpEL 解析）</li>
 * </ul>
 * 内置变量：{@code #methodName}、{@code #className} 与方法的全部形参名。
 *
 * @author zhaowenhao
 * @implNote 形参名注入依赖编译期 debug 信息（{@code -g}，Maven/Spring Boot 默认开启）或
 *           {@code -parameters} 编译选项之一；若两者皆缺失，{@code #形参名} 变量将无法解析。
 * @since 2026-06-12
 */
public class LockKeyResolver {

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();
    private final TemplateParserContext templateContext = new TemplateParserContext();

    /**
     * 解析锁 key 表达式
     *
     * @param keyExpression @Lock 注解上的 key
     * @param method        被拦截的方法
     * @param args          实际入参
     * @return 解析后的 key
     */
    public String resolve(String keyExpression, Method method, Object[] args) {
        if (keyExpression == null || !keyExpression.contains("#")) {
            return keyExpression;
        }
        try {
            StandardEvaluationContext context = new StandardEvaluationContext();
            String[] paramNames = discoverer.getParameterNames(method);
            if (paramNames != null) {
                for (int i = 0; i < args.length && i < paramNames.length; i++) {
                    context.setVariable(paramNames[i], args[i]);
                }
            }
            context.setVariable("methodName", method.getName());
            context.setVariable("className", method.getDeclaringClass().getSimpleName());

            Expression expression = keyExpression.contains("#{")
                    ? parser.parseExpression(keyExpression, templateContext)
                    : parser.parseExpression(keyExpression);
            Object value = expression.getValue(context);
            if (value == null) {
                throw LockException.invalidConfig("SpEL 表达式解析结果为 null: " + keyExpression);
            }
            return value.toString();
        } catch (LockException e) {
            throw e;
        } catch (Exception e) {
            throw LockException.invalidConfig("SpEL 表达式解析失败: " + keyExpression + ", 原因: " + e.getMessage(), e);
        }
    }
}
