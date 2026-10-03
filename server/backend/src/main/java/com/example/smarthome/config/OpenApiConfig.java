package com.example.smarthome.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/** Cấu hình Swagger UI (springdoc): mô tả API + nút Authorize (Bearer JWT). */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI smarthomeOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("MQTT Smart Home API")
                        .description("""
                                REST API của đồ án MQTT Smart Home (Phase 3).

                                Cách dùng nhanh:
                                1) POST /api/auth/login với {username,password} lấy token;
                                2) bấm Authorize, nhập: Bearer <token>;
                                3) gọi các endpoint còn lại (thiết bị, telemetry, lệnh).

                                WebSocket STOMP: endpoint /ws, gửi header Authorization: Bearer <token>
                                trong frame CONNECT; các topic: /topic/telemetry/{deviceId},
                                /topic/devices, /topic/commands/{deviceId}.
                                """)
                        .version("0.3.0"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Dán JWT lấy từ /api/auth/login, có hoặc không kèm 'Bearer '")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
