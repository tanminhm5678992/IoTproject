package com.example.smarthome.repository;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.example.smarthome.entity.Telemetry;

/** Repository bảng telemetry. */
public interface TelemetryRepo extends JpaRepository<Telemetry, Long>, JpaSpecificationExecutor<Telemetry> {

    /** Bản ghi mới nhất đứng đầu - dùng để kiểm tra kết quả trong test và cho API. */
    Optional<Telemetry> findFirstByDeviceIdOrderByRecordedAtDesc(String deviceId);

    /** Cùng trên nhưng trả List (giữ cho test hiện có). */
    java.util.List<Telemetry> findByDeviceIdOrderByRecordedAtDesc(String deviceId);

    // Lịch sử telemetry có from/to/lọc xem TelemetryService.search (JPA Specification).
    // Không dùng @Query với ":from IS NULL" vì PostgreSQL báo
    // "could not determine data type of parameter" khi bind NULL.
}


