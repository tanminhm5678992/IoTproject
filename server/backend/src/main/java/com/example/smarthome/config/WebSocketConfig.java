package com.example.smarthome.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket STOMP endpoint /ws (mục 9):
 *  - /topic/telemetry/{deviceId}, /topic/devices, /topic/commands/{deviceId}
 *  - Xác thực JWT trong header của frame STOMP CONNECT (JwtChannelInterceptor).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtChannelInterceptor jwtChannelInterceptor;
    private final List<String> allowedOrigins;

    public WebSocketConfig(JwtChannelInterceptor jwtChannelInterceptor,
                           @Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost}") String allowedOrigins) {
        this.jwtChannelInterceptor = jwtChannelInterceptor;
        this.allowedOrigins = JwtChannelInterceptor.allowedOrigins(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
        // Heartbeat mặc định 10s/10s của simple broker (giữ kết nối STOMP sống)
    }

    @Override
    public void registerStompEndpoints(org.springframework.web.socket.config.annotation.StompEndpointRegistry registry) {
        // Frontend (P5) kết nối ws://host:8080/ws; origin chỉ định theo FRONTEND_ORIGIN
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins.toArray(new String[0]));
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(jwtChannelInterceptor);
    }
}
