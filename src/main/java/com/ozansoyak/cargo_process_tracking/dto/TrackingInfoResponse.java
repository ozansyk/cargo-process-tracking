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
    private List<TrackingHistoryEvent> historyEvents;

    private String senderName;
    private String receiverName;
    private String senderPhone;
    private String receiverPhone;
    private String senderAddress;
    private String receiverAddress;
    private Double weight;
    private String dimensions;
    private String contentDescription;
    private boolean completable;    // Genel adım tamamlanabilir mi?
    private boolean cancellable;

    // --- YENİ ALANLAR: Aktif görev bilgisi için ---
    private String activeTaskDefinitionKey; // Örn: "userTask_PhysicalReception"
    private String activeTaskName;          // Örn: "Fiziksel Alımı Onayla"
    // ---------------------------------------------

    private boolean found = true;
    private String errorMessage;

    public TrackingInfoResponse(boolean found, String errorMessage) {
        this.found = found;
        this.errorMessage = errorMessage;
    }
}