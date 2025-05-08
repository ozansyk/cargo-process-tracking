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
    private String statusDescription;
    private String statusBadgeClass; // Badge class'ı içeriyor
    private String location;
}