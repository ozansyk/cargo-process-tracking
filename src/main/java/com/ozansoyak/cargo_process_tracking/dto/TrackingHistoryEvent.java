package com.ozansoyak.cargo_process_tracking.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrackingHistoryEvent {
    private LocalDateTime timestamp;
    private String statusDescription; // BPMN'deki aktivite adı veya özel bir açıklama
    private String location; // Şimdilik basit tutalım, aktivite adından çıkarılabilir veya null olabilir
}