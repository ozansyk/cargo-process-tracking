package com.ozansoyak.cargo_process_tracking.service;

// ApproveNextStepRequest importu kaldırıldı
import com.ozansoyak.cargo_process_tracking.dto.CreateCargoRequest;
import com.ozansoyak.cargo_process_tracking.dto.CargoResponse;
import jakarta.persistence.EntityNotFoundException;

public interface CargoService {

    CargoResponse createCargoAndStartProcess(CreateCargoRequest request);

    void cancelCargoProcess(String trackingNumber);

    /**
     * Belirtilen takip numarasına ait süreçteki mevcut aktif kullanıcı görevini tamamlar
     * ve bir sonraki adıma geçiş için gerekli koşulu ayarlar.
     *
     * @param trackingNumber Süreci ilerletilecek kargonun takip numarası.
     * @throws EntityNotFoundException Kargo bulunamazsa.
     * @throws IllegalStateException Süreç aktif değilse veya beklenmedik bir durumdaysa.
     * @throws IllegalArgumentException Tamamlanan göreve göre bir sonraki adım belirlenemezse.
     */
    void completeUserTaskAndPrepareNextStep(String trackingNumber); // Yeni metot

    // approveNextStep METODU KALDIRILDI

    // TrackingInfoResponse getTrackingInfo(String trackingNumber);
}