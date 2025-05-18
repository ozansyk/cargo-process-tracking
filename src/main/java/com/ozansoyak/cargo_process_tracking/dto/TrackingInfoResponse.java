package com.ozansoyak.cargo_process_tracking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrackingInfoResponse {
    private String trackingNumber;
    private String currentStatus;
    private String currentStatusBadgeClass;
    private String senderCity;
    private String receiverCity;
    private String processInstanceId;
    private List<TrackingHistoryEvent> historyEvents; // Bu DTO'nun tanımının sizde olduğunu varsayıyorum

    private String senderName;
    private String receiverName;
    private String senderPhone;
    private String receiverPhone;
    private String senderAddress;
    private String receiverAddress;
    private Double weight;
    private String dimensions;
    private String contentDescription;

    // `completable` alanı kaldırıldı, yerine `activeUserTasks` listesi geldi.
    // private boolean completable; // KALDIRILDI

    private boolean cancellable;

    // --- YENİ ALAN: Aktif kullanıcı görevlerini tutacak liste ---
    private List<ActiveTaskInfo> activeUserTasks;
    // --- `activeTaskDefinitionKey` ve `activeTaskName` KALDIRILDI ---
    // private String activeTaskDefinitionKey;
    // private String activeTaskName;

    private boolean found = true;
    private String errorMessage;

    // Hata durumu için constructor
    public TrackingInfoResponse(boolean found, String errorMessage) {
        this.found = found;
        this.errorMessage = errorMessage;
        this.activeUserTasks = List.of(); // Hata durumunda boş liste
        this.historyEvents = List.of(); // Hata durumunda boş liste
    }
}