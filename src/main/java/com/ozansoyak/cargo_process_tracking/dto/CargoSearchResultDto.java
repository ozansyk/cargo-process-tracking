package com.ozansoyak.cargo_process_tracking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CargoSearchResultDto {
    private String trackingNumber;
    private String senderName;
    private String receiverName;
    private String receiverCity;
    private String currentStatus; // Kullanıcı dostu metin
    private String currentStatusBadgeClass; // Bootstrap class
    private LocalDateTime lastUpdatedAt;
    private boolean cancellable; // İptal edilebilir mi?
    private boolean completable; // İlerletilebilir mi? (Aktif en az bir User Task var mı?)
}