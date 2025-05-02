package com.ozansoyak.cargo_process_tracking.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecentActivityDto {
    private String trackingNumber;
    private String statusDescription; // Kullanıcı dostu durum
    private String statusBadgeClass;  // Bootstrap badge class
    private LocalDateTime timestamp;     // İşlem zamanı
    // private String performedBy; // İleride eklenebilir
}