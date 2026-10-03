package com.example.smarthome.scheduler;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.smarthome.entity.Command;
import com.example.smarthome.repository.CommandRepo;
import com.example.smarthome.service.RealtimePublisher;

/**
 * Chuyển lệnh PENDING quá 10 giây không có ack sang TIMEOUT (mục 9).
 * Chạy mỗi 5 giây; tắt bằng COMMAND_TIMEOUT_JOB=false (dùng cho test tự động).
 */
@Component
@ConditionalOnProperty(prefix = "app.command-timeout", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CommandTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(CommandTimeoutJob.class);

    private final CommandRepo commandRepo;
    private final RealtimePublisher realtimePublisher;
    private final long timeoutSeconds;

    public CommandTimeoutJob(CommandRepo commandRepo,
                             RealtimePublisher realtimePublisher,
                             @Value("${app.command-timeout.timeout-seconds:10}") long timeoutSeconds) {
        this.commandRepo = commandRepo;
        this.realtimePublisher = realtimePublisher;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${app.command-timeout.poll-ms:5000}")
    @Transactional
    public void checkTimeouts() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(timeoutSeconds);
        List<Command> stale = commandRepo.findByStatusAndSentAtBefore(Command.STATUS_PENDING, cutoff);
        for (Command command : stale) {
            command.setStatus(Command.STATUS_TIMEOUT);
            commandRepo.save(command);
            realtimePublisher.commandUpdated(command);
            log.info("timeout: lệnh '{}' của '{}' quá {}s không có ack -> TIMEOUT",
                    command.getCmdId(), command.getDeviceId(), timeoutSeconds);
        }
    }
}
