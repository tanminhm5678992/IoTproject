package com.example.smarthome.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.smarthome.entity.Command;

/** Repository bảng commands. */
public interface CommandRepo extends JpaRepository<Command, Long> {

    /** Thiết bị trả ack kèm cmdId -> tra ra lệnh tương ứng. */
    Optional<Command> findByCmdId(String cmdId);

    /** Lịch sử lệnh của một thiết bị, mới nhất đứng đầu (GET .../commands). */
    Page<Command> findByDeviceIdOrderBySentAtDesc(String deviceId, Pageable pageable);

    /** Các lệnh PENDING đã quá hạn để CommandTimeoutJob chuyển sang TIMEOUT. */
    List<Command> findByStatusAndSentAtBefore(String status, OffsetDateTime before);
}

