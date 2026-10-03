package com.example.smarthome.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/** Trang dữ liệu thống nhất cho telemetry và lịch sử lệnh (mục 9: phân trang). */
public record PageResponse<T>(List<T> items, long total, int page, int size) {

    public static <T> PageResponse<T> of(List<T> items, long total, int page, int size) {
        return new PageResponse<>(items, total, page, size);
    }

    public static <T> PageResponse<T> from(Page<T> source, List<T> items) {
        return new PageResponse<>(items, source.getTotalElements(),
                source.getNumber(), source.getSize());
    }
}
