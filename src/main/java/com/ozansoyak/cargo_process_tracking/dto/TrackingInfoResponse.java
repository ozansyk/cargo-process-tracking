package com.ozansoyak.cargo_process_tracking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder // Builder pattern eklemek kullanımı kolaylaştırabilir
public class TrackingInfoResponse {
    private String trackingNumber;
    private String currentStatus; // Enum adı veya daha kullanıcı dostu bir metin olabilir
    private String currentStatusBadgeClass; // Duruma göre bootstrap badge class'ı
    private String senderCity;
    private String receiverCity;
    private String processInstanceId; // Teknik bilgi, isteğe bağlı
    private List<TrackingHistoryEvent> historyEvents;

    // Hata durumunu belirtmek için (opsiyonel, controller'da da yönetilebilir)
    private boolean found = true;
    private String errorMessage;

    // Sadece hata durumu için basit bir constructor
    public TrackingInfoResponse(boolean found, String errorMessage) {
        this.found = found;
        this.errorMessage = errorMessage;
    }
}