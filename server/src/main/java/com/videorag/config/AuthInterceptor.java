package com.videorag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videorag.auth.AuthService;
import com.videorag.common.ErrorCode;
import com.videorag.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public AuthInterceptor(AuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        try {
            Long userId = authService.resolveUser(request.getHeader("Authorization"));
            request.setAttribute(AuthService.REQUEST_USER_ID, userId);
            return true;
        } catch (SecurityException error) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(),
                    Result.error(ErrorCode.UNAUTHORIZED.code(), error.getMessage()));
            return false;
        }
    }
}
