package dev.finguard.config.web;

import dev.finguard.config.exception.InvalidParameterException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC configuration — registers interceptors and argument resolver customisations.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PageableParamValidationInterceptor());
    }

    /**
     * Validates {@code page} and {@code size} query parameters before Spring Data's
     * {@code PageableHandlerMethodArgumentResolver} silently swallows non-numeric values.
     */
    static class PageableParamValidationInterceptor implements HandlerInterceptor {

        private static final String[] NUMERIC_PARAMS = {"page", "size"};

        @Override
        public boolean preHandle(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Object handler) {
            for (String param : NUMERIC_PARAMS) {
                String value = request.getParameter(param);
                if (value != null) {
                    try {
                        Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        throw new InvalidParameterException(param, value);
                    }
                }
            }
            return true;
        }
    }
}