package com.ozansoyak.cargo_process_tracking.flowable;

import com.ozansoyak.cargo_process_tracking.model.Cargo;
import com.ozansoyak.cargo_process_tracking.model.enums.CargoStatus;
import com.ozansoyak.cargo_process_tracking.repository.CargoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component("persistStatusWorker")
@RequiredArgsConstructor
@Slf4j
public class PersistStatusWorker implements JavaDelegate {

    private final CargoRepository cargoRepository;

    @Override
    @Transactional
    public void execute(DelegateExecution execution) {
        log.info("Flowable Delegate 'persistStatusWorker' execute method called. Execution ID: {}, Process Instance ID: {}",
                execution.getId(), execution.getProcessInstanceId());

        final Long cargoId = execution.getVariable("cargoId", Long.class);
        //TODO Şimdilik 'newStatus' değişkenine gerek yok, direkt RECEIVED olacak
        // final String newStatusStr = execution.getVariable("newStatus", String.class);
        final CargoStatus newStatus = CargoStatus.RECEIVED;

        if (cargoId == null) {
            log.error("Execution Id {}: Gerekli değişken (cargoId) alınamadı.", execution.getId());
            throw new RuntimeException("Gerekli süreç değişkeni eksik: cargoId.");
        }

        log.debug("Execution Id {} için değişkenler: cargoId={}", execution.getId(), cargoId);

        try {
            Cargo cargo = cargoRepository.findById(cargoId)
                    .orElseThrow(() -> new RuntimeException("Flowable Worker: Kargo bulunamadı Id: " + cargoId));

            cargo.setCurrentStatus(newStatus);
            if (cargo.getProcessInstanceId() == null) {
                cargo.setProcessInstanceId(execution.getProcessInstanceId());
            }

            cargoRepository.save(cargo);
            log.info("Flowable Worker: Kargo Id {} durumu veritabanında {} olarak güncellendi.", cargoId, newStatus);

        } catch (Exception e) {
            log.error("Flowable Worker 'persistStatus' hatası Execution Id: {}, Cargo Id: {}: {}", execution.getId(), cargoId, e.getMessage(), e);
            throw new RuntimeException("Durum persist edilirken hata: " + e.getMessage(), e);
        }
    }
}