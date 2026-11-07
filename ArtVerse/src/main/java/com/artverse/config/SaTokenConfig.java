package com.artverse.config;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
            String method = SaHolder.getRequest().getMethod();
            if ("OPTIONS".equalsIgnoreCase(method)) {
                return;
            }

            String path = SaHolder.getRequest().getRequestPath();
            if (path.equals("/actuator") || path.startsWith("/actuator/")) {
                if (path.equals("/actuator/health")) {
                    return;
                }
                StpUtil.checkLogin();
                StpUtil.checkRole("ADMIN");
                return;
            }
            if (!path.startsWith("/api/")) {
                return;
            }
            if (path.startsWith("/api/square/")
                    || path.equals("/api/auth/login")
                    || path.equals("/api/auth/register")
                    || path.equals("/api/auth/refresh")
                    || path.equals("/api/auth/challenge/config")
                    || path.equals("/api/auth/captcha/image")
                    || path.equals("/api/auth/login-by-code")
                    || path.equals("/api/auth/password/verify-code")
                    || path.equals("/api/auth/password/reset")
                    || path.equals("/api/mail/send-code")
                    || path.equals("/api/mail/config")) {
                return;
            }
            StpUtil.checkLogin();
        })).addPathPatterns("/**");
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * 本地开发模式（ARTVERSE_DEV_MODE=true）下强制关闭 Sa-Token 会话 Cookie 的 Secure 属性，
     * 否则纯 HTTP 访问（尤其是局域网 IP）时浏览器不会保存 satoken Cookie，登录状态无法维持。
     * 线上模式完全沿用 application.yml 中 sa-token.cookie.secure 的配置。
     */
    @Bean
    static BeanPostProcessor saTokenDevModeCookieCustomizer(Environment environment) {
        boolean devMode = environment.getProperty("artverse.dev-mode", Boolean.class, false);
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (devMode && bean instanceof cn.dev33.satoken.config.SaTokenConfig config) {
                    config.getCookie().setSecure(false);
                }
                return bean;
            }
        };
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        jsonSerializer.configure(mapper -> mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
