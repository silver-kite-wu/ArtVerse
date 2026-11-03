package com.artverse.common;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {
    @Test
    void missingPostRouteReturns404InsteadOfInternalServerError() throws Exception {
        try (var context = context(StaticResources.class)) {
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(post("/api/chapters/1/story-chat/conversations/missing/runs/missing/artifacts/missing/decision")
                            .contentType("application/json").content("{\"decision\":\"confirm\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.message").value("请求的接口或资源不存在"));
            mvc.perform(get("/missing-resource.png"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        }
    }

    @Test
    void missingHandlerWithoutResourceFallbackAlsoReturns404() throws Exception {
        try (var context = context(Mvc.class)) {
            MockMvcBuilders.webAppContextSetup(context).build().perform(post("/api/missing"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        }
    }

    private AnnotationConfigWebApplicationContext context(Class<?> config) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(config);
        context.refresh();
        return context;
    }

    @Configuration
    @EnableWebMvc
    static class Mvc {
        @Bean GlobalExceptionHandler errors() { return new GlobalExceptionHandler(); }
    }

    @Configuration
    @EnableWebMvc
    static class StaticResources extends Mvc implements WebMvcConfigurer {
        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/**").addResourceLocations("classpath:/static/");
        }
    }
}
