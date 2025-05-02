package com.ozansoyak.cargo_process_tracking.service;

import com.ozansoyak.cargo_process_tracking.dto.CreateCargoRequest;
import com.ozansoyak.cargo_process_tracking.dto.CargoResponse;
import com.ozansoyak.cargo_process_tracking.dto.TrackingInfoResponse; // Eklendi
import jakarta.persistence.EntityNotFoundException;

public interface CargoService {

    CargoResponse createCargoAndStartProcess(CreateCargoRequest request);

    void cancelCargoProcess(String trackingNumber);

    void completeUserTaskAndPrepareNextStep(String trackingNumber);

    /**
     * Belirtilen takip numarasına ait kargonun güncel durumunu ve
     * süreç geçmişini getirir.
     *
     * @param trackingNumber Sorgulanacak kargonun takip numarası.
     * @return Kargo takip bilgilerini içeren DTO.
     * @throws EntityNotFoundException Kargo bulunamazsa.
     */
    TrackingInfoResponse getTrackingInfo(String trackingNumber); // Yeni metot eklendi
}