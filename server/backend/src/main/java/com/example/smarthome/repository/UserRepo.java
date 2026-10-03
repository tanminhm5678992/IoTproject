package com.example.smarthome.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.smarthome.entity.User;

/** Repository bảng users (Phase 3 dùng cho đăng nhập). */
public interface UserRepo extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);
}
