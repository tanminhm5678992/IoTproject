package com.example.smarthome.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.example.smarthome.entity.User;
import com.example.smarthome.repository.UserRepo;

/**
 * Seed tài khoản admin khi backend khởi động (mục 9: seed user admin qua biến môi trường).
 * - ADMIN_USERNAME / ADMIN_PASSWORD lấy từ .env (không hardcode);
 * - Nếu user đã tồn tại nhưng hash không khớp password hiện tại thì cập nhật lại
 *   (.env là nguồn chính - chỉ cần đổi .env + restart là đổi mật khẩu);
 * - Thiếu biến thì bỏ qua và log WARN (test tự động không cần admin).
 */
@Component
public class AdminSeeder {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserRepo userRepo;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminPassword;

    public AdminSeeder(UserRepo userRepo,
                       PasswordEncoder passwordEncoder,
                       @Value("${app.admin.username:}") String adminUsername,
                       @Value("${app.admin.password:}") String adminPassword) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedAdmin() {
        if (adminUsername == null || adminUsername.isBlank()
                || adminPassword == null || adminPassword.isBlank()) {
            log.warn("Thiếu ADMIN_USERNAME/ADMIN_PASSWORD trong .env - bỏ qua seed admin "
                    + "(không đăng nhập REST/Swagger được).");
            return;
        }
        if (adminPassword.startsWith("CHANGE_ME")) {
            log.warn("ADMIN_PASSWORD vẫn là giá trị mẫu CHANGE_ME - hãy đổi trước khi nộp bài!");
        }

        User user = userRepo.findByUsername(adminUsername).orElseGet(() -> {
            User created = new User();
            created.setUsername(adminUsername);
            created.setRole(User.ROLE_ADMIN);
            created.setPasswordHash(passwordEncoder.encode(adminPassword));
            userRepo.save(created);
            log.info("Đã seed admin '{}' từ biến môi trường", adminUsername);
            return created;
        });

        if (!passwordEncoder.matches(adminPassword, user.getPasswordHash())) {
            // Password trong .env đã đổi -> hash mới theo .env
            user.setPasswordHash(passwordEncoder.encode(adminPassword));
            userRepo.save(user);
            log.info("Đã cập nhật mật khẩu admin '{}' theo .env", adminUsername);
        }
    }
}
