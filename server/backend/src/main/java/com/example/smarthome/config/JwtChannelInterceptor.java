package com.example.smarthome.config;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import com.example.smarthome.security.JwtService;
import com.example.smarthome.dto.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Xác thực JWT ở frame STOMP CONNECT (mục 9: xác thực JWT khi kết nối WebSocket).
 * Frontend gửi header {@code Authorization: Bearer <token>} trong frame CONNECT;
 * thiếu hoặc sai token thì ném AccessDeniedException -> Spring gửi ERROR và đóng kết nối.
 */
@Component
public class JwtChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtChannelInterceptor.class);

    private final ObjectProvider<JwtService> jwtServiceProvider;
    private final ObjectProvider<ObjectMapper> objectMapperProvider;

    public JwtChannelInterceptor(ObjectProvider<JwtService> jwtServiceProvider,
                                 ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.jwtServiceProvider = jwtServiceProvider;
        this.objectMapperProvider = objectMapperProvider;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || StompCommand.CONNECT != accessor.getCommand()) {
            return message;
        }

        String authHeader = accessor.getFirstNativeHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            deny(accessor, "Thiếu Authorization: Bearer <token> trong frame CONNECT");
        }
        String token = authHeader.substring(7).trim();
        JwtService jwtService = jwtServiceProvider.getIfAvailable();
        String username = null;
        if (jwtService != null) {
            username = jwtService.extractUsername(token);
        }
        if (username == null || username.isBlank()) {
            deny(accessor, "Token JWT không hợp lệ hoặc đã hết hạn");
        }

        accessor.setUser(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        log.info("WebSocket: user '{}' kết nối STOMP hợp lệ", username);
        return message;
    }

    /** Ghi log + ném lỗi để tầng WebSocket từ chối kết nối (không lộ chi tiết token). */
    private void deny(StompHeaderAccessor accessor, String reason) {
        log.warn("WebSocket: từ chối CONNECT - {}", reason);
        ObjectMapper mapper = objectMapperProvider.getIfAvailable();
        if (mapper != null) {
            try {
                ApiError body = new ApiError(OffsetDateTime.now(java.time.ZoneOffset.UTC),
                        401, "Unauthorized", reason);
                log.debug("WebSocket 401 body: {}", mapper.writeValueAsString(body));
            } catch (Exception ignored) {
                // chỉ phục vụ log debug
            }
        }
        throw new AccessDeniedException(reason);
    }

    /** Danh sách origin được phép kết nối WebSocket (dùng chung với CORS). */
    public static List<String> allowedOrigins(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
