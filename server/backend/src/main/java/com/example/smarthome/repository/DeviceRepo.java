package com.example.smarthome.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.smarthome.entity.Device;

/** Repository bảng devices. */
public interface DeviceRepo extends JpaRepository<Device, Long> {

    Optional<Device> findByDeviceId(String deviceId);

    boolean existsByDeviceId(String deviceId);
}

