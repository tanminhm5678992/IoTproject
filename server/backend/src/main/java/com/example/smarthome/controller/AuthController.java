package com.example.smarthome.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.smarthome.dto.LoginRequest;
import com.example.smarthome.dto.LoginResponse;
import com.example.smarthome.entity.User;
import com.example.smarthome.repository.UserRepo;
import com.example.smarthome.security.JwtService;
import jakarta.validation.Valid;

/**
 * POST /api/auth/login (mục 9): {username,password} -> {token}.
 * Sai tài khoản/mật khẩu -> 401 JSON. Không bao giờ log mật khẩu hay token.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRepo userRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(UserRepo userRepo, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    @ResponseStatus(HttpStatus.OK)
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        User user = userRepo.findByUsername(request.username())
                .orElseThrow(() -> new BadCredentialsException("Sai username hoặc mật khẩu"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Sai username hoặc mật khẩu");
        }
        String token = jwtService.generateToken(user.getUsername(), user.getRole());
        // Chỉ log username, KHÔNG log mật khẩu/token (mục 9)
        log.info("Đăng nhập thành công: {} ({})", user.getUsername(), user.getRole());
        return new LoginResponse(token, user.getUsername(), user.getRole(), jwtService.expireSeconds());
    }
}
