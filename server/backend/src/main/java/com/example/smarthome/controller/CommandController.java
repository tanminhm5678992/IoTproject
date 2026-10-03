package com.example.smarthome.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.smarthome.dto.CommandCreateRequest;
import com.example.smarthome.dto.CommandResponse;
import com.example.smarthome.dto.PageResponse;
import com.example.smarthome.entity.Command;
import com.example.smarthome.service.CommandService;
import com.example.smarthome.service.DeviceService;
import jakarta.validation.Valid;

/**
 * API lệnh điều khiển (mục 9):
 *  POST /api/devices/{id}/commands   {target,value} -> kiểm tra ACTIVE+online -> 201 {cmdId,status}
 *                                     thiết bị offline/chưa ACTIVE -> 422 (đã chốt)
 *  GET  /api/devices/{id}/commands   lịch sử lệnh (phân trang)
 */
@RestController
@RequestMapping("/api/devices/{deviceId}/commands")
public class CommandController {

    private final DeviceService deviceService;
    private final CommandService commandService;

    public CommandController(DeviceService deviceService, CommandService commandService) {
        this.deviceService = deviceService;
        this.commandService = commandService;
    }

    @PostMapping
    public ResponseEntity<CommandResponse> send(@PathVariable String deviceId,
                                                @Valid @RequestBody CommandCreateRequest request) {
        deviceService.requireDevice(deviceId);
        Command command = commandService.sendCommand(
                deviceId, request.target(), request.value(), currentUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(CommandResponse.from(command));
    }

    @GetMapping
    public PageResponse<CommandResponse> history(@PathVariable String deviceId,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "100") int limit) {
        deviceService.requireDevice(deviceId);
        var result = commandService.history(deviceId, page, limit);
        return PageResponse.of(
                result.getContent().stream().map(CommandResponse::from).toList(),
                result.getTotalElements(), result.getNumber(), result.getSize());
    }

    /** Username từ JWT (dùng ghi vào cột created_by). */
    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "unknown";
    }
}
