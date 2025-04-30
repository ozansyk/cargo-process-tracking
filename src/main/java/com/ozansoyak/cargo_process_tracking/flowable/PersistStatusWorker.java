// Paketi güncellediyseniz: package com.ozansoyak.cargo_process_tracking.camunda.delegates;
package com.ozansoyak.cargo_process_tracking.flowable; // Şimdilik eski paket kalsın, siz taşıyabilirsiniz

import com.ozansoyak.cargo_process_tracking.model.Cargo;
import com.ozansoyak.cargo_process_tracking.model.enums.CargoStatus;
import com.ozansoyak.cargo_process_tracking.repository.CargoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Camunda importları
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component("persistStatusWorker") // Bean ismi BPMN'deki ifadeyle eşleşmeli
@RequiredArgsConstructor
@Slf4j
public class PersistStatusWorker implements JavaDelegate { // Camunda JavaDelegate implementasyonu

    private final CargoRepository cargoRepository;

    @Override
    @Transactional // İşlemin transactional olmasını sağlar
    public void execute(DelegateExecution execution) {
        log.info("Camunda Delegate 'persistStatusWorker' execute method called. Execution ID: {}, Process Instance ID: {}",
                execution.getId(), execution.getProcessInstanceId());

        // Süreç değişkenlerini al
        final Long cargoId = (Long) execution.getVariable("cargoId"); // Cast gerekebilir
        final CargoStatus newStatus = CargoStatus.RECEIVED; // Bu adımın amacı durumu RECEIVED yapmak

        if (cargoId == null) {
            log.error("Execution Id {}: Gerekli süreç değişkeni (cargoId) alınamadı.", execution.getId());
            // Hata yönetimi: Süreci hata durumuyla sonlandırabilir veya başka bir işlem yapabilirsiniz.
            // throw new BpmnError("MISSING_VARIABLE_CARGO_ID", "Gerekli süreç değişkeni eksik: cargoId.");
            throw new RuntimeException("Gerekli süreç değişkeni eksik: cargoId.");
        }

        log.debug("Execution Id {} için değişkenler: cargoId={}", execution.getId(), cargoId);

        try {
            // Veritabanından ilgili kargo nesnesini bul
            Cargo cargo = cargoRepository.findById(cargoId)
                    .orElseThrow(() -> {
                        log.error("Camunda Worker: Kargo bulunamadı Id: {}", cargoId);
                        return new RuntimeException("Camunda Worker: Kargo bulunamadı Id: " + cargoId);
                        // Veya return new BpmnError("CARGO_NOT_FOUND", "Kargo bulunamadı: " + cargoId);
                    });

            // Durumu güncelle ve kaydet
            cargo.setCurrentStatus(newStatus);
            // ProcessInstanceId zaten servis tarafından set edilmiş olmalı, ama yine de kontrol edebiliriz.
            if (cargo.getProcessInstanceId() == null || !cargo.getProcessInstanceId().equals(execution.getProcessInstanceId())) {
                log.warn("Cargo ID {} için ProcessInstanceId güncelleniyor. Eski: {}, Yeni: {}", cargoId, cargo.getProcessInstanceId(), execution.getProcessInstanceId());
                cargo.setProcessInstanceId(execution.getProcessInstanceId());
            }

            cargoRepository.save(cargo);
            log.info("Camunda Worker: Kargo Id {} durumu veritabanında {} olarak güncellendi.", cargoId, newStatus);

            // Başka süreç değişkenleri set edilebilir
            // execution.setVariable("statusPersistedTime", LocalDateTime.now());

        } catch (Exception e) {
            log.error("Camunda Worker 'persistStatus' hatası Execution Id: {}, Cargo Id: {}: {}", execution.getId(), cargoId, e.getMessage(), e);
            // Hatanın tekrar denenip denenmeyeceğini veya süreci nasıl etkileyeceğini yönetmek için:
            // throw new RuntimeException("Durum persist edilirken hata: " + e.getMessage(), e); // Tekrar denemeyi tetiklemez
            // throw new BpmnError("PERSISTENCE_ERROR", "DB Hatası: " + e.getMessage()); // Hata olayına yönlendirebilir
            throw e; // Orijinal hatayı fırlat
        }
    }
}