package com.example.smarthome.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Tạo và kiểm tra JWT (mục 9): HS256, hết hạn sau jwt.expire-minutes (8 giờ).
 * Secret lấy từ biến môi trường JWT_SECRET - KHÔNG hardcode trong code.
 */
@Service
public class JwtService {

    /** HS256 yêu cầu key tối thiểu 32 byte (256 bit). */
    private static final int MIN_SECRET_BYTES = 32;

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final long expireMinutes;

    public JwtService(@Value("${jwt.secret:}") String secret,
                      @Value("${jwt.expire-minutes:480}") long expireMinutes) {
        this.expireMinutes = expireMinutes;
        if (secret == null || secret.isBlank()) {
            // Không chặn khởi động (test tự động không cần JWT) nhưng phải nói rõ khi dùng.
            this.key = null;
            log.warn("jwt.secret trống: login/token sẽ lỗi. Đặt JWT_SECRET trong .env.");
        } else {
            if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
                throw new IllegalStateException(
                        "JWT_SECRET quá ngắn (cần tối thiểu 32 ký tự). Đổi trong .env rồi chạy lại.");
            }
            this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Sinh token cho user với vai trị role; JWT tự hết hạn sau expireMinutes. */
    public String generateToken(String username, String role) {
        requireKey();
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expireMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    /**
     * Đọc username từ token.
     *
     * @return subject nếu token hợp lệ và chưa hết hạn; null nếu token sai/hết hạn
     */
    public String extractUsername(String token) {
        requireKey();
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            // Không log nội dung token (mục 9: không log mật khẩu hay token)
            log.debug("Token JWT không hợp lệ hoặc đã hết hạn: {}", e.toString());
            return null;
        }
    }

    /** Số giây tới lúc token hết hạn (client có thể dùng để tự làm mới). */
    public long expireSeconds() {
        return expireMinutes * 60;
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("jwt.secret trống - hãy đặt JWT_SECRET trong .env rồi khởi động lại backend.");
        }
    }
}
