package com.ozansoyak.cargo_process_tracking.service;

import com.ozansoyak.cargo_process_tracking.dto.CreateCargoRequest;
import com.ozansoyak.cargo_process_tracking.dto.CargoResponse;
import jakarta.persistence.EntityNotFoundException; // Veya kendi exception'ınız

public interface CargoService {

    /**
     * Yeni bir kargo oluşturur ve ilgili Camunda sürecini başlatır.
     * @param request Kargo oluşturma bilgilerini içeren DTO.
     * @return Oluşturulan kargo bilgilerini ve süreç ID'sini içeren DTO.
     */
    CargoResponse createCargoAndStartProcess(CreateCargoRequest request);

    /**
     * Belirtilen takip numarasına sahip kargo sürecini iptal eder.
     * Sürecin iptal edilmesi için Camunda'daki ilgili süreç değişkenini günceller.
     * Sürecin bir sonraki kontrol noktasında (gateway) iptal yoluna girmesi beklenir.
     *
     * @param trackingNumber İptal edilecek kargonun takip numarası.
     * @throws EntityNotFoundException Kargo veya ilgili süreç bulunamazsa.
     * @throws IllegalStateException Süreç iptal edilemeyecek bir durumdaysa (örn. zaten bitmişse).
     */
    void cancelCargoProcess(String trackingNumber);

    // TODO: Takip bilgisi getirme metodu eklenebilir
    // TrackingInfoResponse getTrackingInfo(String trackingNumber);
}