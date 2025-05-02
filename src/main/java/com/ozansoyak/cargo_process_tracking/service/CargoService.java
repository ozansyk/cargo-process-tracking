package com.ozansoyak.cargo_process_tracking.service;

import com.ozansoyak.cargo_process_tracking.dto.*; // DTO importları
import jakarta.persistence.EntityNotFoundException;

public interface CargoService {

    CargoResponse createCargoAndStartProcess(CreateCargoRequest request);

    void cancelCargoProcess(String trackingNumber);

    void completeUserTaskAndPrepareNextStep(String trackingNumber);

    TrackingInfoResponse getTrackingInfo(String trackingNumber);

    /**
     * Personel paneli için gerekli özet verileri (widget sayıları, son işlemler) getirir.
     * @return Panel verilerini içeren DTO.
     */
    PanelDataDto getPanelData(); // Yeni metot
}