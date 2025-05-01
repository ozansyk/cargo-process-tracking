package com.ozansoyak.cargo_process_tracking.camundaworker;

import com.ozansoyak.cargo_process_tracking.model.Cargo;
import com.ozansoyak.cargo_process_tracking.model.enums.CargoStatus;
import com.ozansoyak.cargo_process_tracking.repository.CargoRepository;
import jakarta.persistence.EntityNotFoundException; // Veya kendi exception sınıfınız
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

// Component adı BPMN'deki delegateExpression ile eşleşmeli
@Component("cargoStatusUpdater")
@RequiredArgsConstructor
@Slf4j
public class CargoStatusUpdateWorker implements JavaDelegate {

    private final CargoRepository cargoRepository;

    // Activity ID'lerini CargoStatus enum'larına eşleştiren map
    private static final Map<String, CargoStatus> ACTIVITY_ID_TO_STATUS_MAP = Map.ofEntries(
            Map.entry("task_UpdateStatusReceived", CargoStatus.RECEIVED),
            Map.entry("task_UpdateStatusLoaded1", CargoStatus.LOADED_ON_VEHICLE_1),
            Map.entry("task_UpdateStatusTransferCenter", CargoStatus.AT_TRANSFER_CENTER),
            Map.entry("task_UpdateStatusLoaded2", CargoStatus.LOADED_ON_VEHICLE_2),
            Map.entry("task_UpdateStatusDistributionArea", CargoStatus.AT_DISTRIBUTION_HUB),
            Map.entry("task_UpdateStatusOutForDelivery", CargoStatus.OUT_FOR_DELIVERY),
            Map.entry("task_UpdateStatusDelivered", CargoStatus.DELIVERED),
            Map.entry("task_UpdateStatusCancelled", CargoStatus.CANCELLED)
    );

    @Override
    @Transactional
    public void execute(DelegateExecution execution) {
        String activityId = execution.getCurrentActivityId();
        String executionId = execution.getId();
        String processInstanceId = execution.getProcessInstanceId();

        log.info("CargoStatusUpdateWorker tetiklendi. Activity ID: {}, Execution ID: {}, Process Instance ID: {}",
                activityId, executionId, processInstanceId);

        // Bu aktivite ID'sine karşılık gelen hedef durumu belirle
        CargoStatus targetStatus = ACTIVITY_ID_TO_STATUS_MAP.get(activityId);

        if (targetStatus == null) {
            log.error("Execution ID {}: Bu aktivite ID ({}) için tanımlı bir hedef kargo durumu bulunamadı.", executionId, activityId);
            throw new BpmnError("INVALID_ACTIVITY_ID", "Tanımsız aktivite ID'si: " + activityId);
        }

        // Gerekli süreç değişkenini al (cargoId)
        Long cargoId = null; // Değişkeni burada tanımla
        try {
            Object cargoIdObj = execution.getVariable("cargoId");
            if (cargoIdObj instanceof Long) {
                cargoId = (Long) cargoIdObj;
            } else if (cargoIdObj instanceof Number) {
                cargoId = ((Number) cargoIdObj).longValue();
            } else if (cargoIdObj instanceof String) {
                try {
                    cargoId = Long.parseLong((String) cargoIdObj);
                } catch (NumberFormatException nfe) {
                    log.error("Execution ID {}: 'cargoId' değişkeni Long'a çevrilemedi: {}", executionId, cargoIdObj);
                    throw new BpmnError("INVALID_VARIABLE_TYPE", "'cargoId' Long olmalı.");
                }
            }

            if (cargoId == null) {
                log.error("Execution ID {}: 'cargoId' süreç değişkeni alınamadı veya null.", executionId);
                throw new BpmnError("MISSING_VARIABLE_CARGO_ID", "Gerekli süreç değişkeni eksik: cargoId.");
            }

            // --------> DÜZELTME: Lambda içinde kullanmak için final değişken oluştur <--------
            final Long finalCargoId = cargoId;

            log.debug("Execution ID {} için kargo durumu güncellenecek: cargoId={}, hedefDurum={}", executionId, finalCargoId, targetStatus);

            // Veritabanından kargoyu bul ve durumu güncelle
            // --------> DÜZELTME: Lambda içinde final değişkeni kullan <--------
            Cargo cargo = cargoRepository.findById(finalCargoId)
                    .orElseThrow(() -> {
                        log.error("Execution ID {}: Kargo bulunamadı, ID: {}", executionId, finalCargoId);
                        return new BpmnError("CARGO_NOT_FOUND", "Kargo bulunamadı: " + finalCargoId);
                    });

            // Mevcut durum zaten hedef durumsa, tekrar güncelleme (opsiyonel iyileştirme)
            if (cargo.getCurrentStatus() == targetStatus) {
                log.warn("Execution ID {}: Kargo (ID: {}) zaten '{}' durumunda. Tekrar güncellenmiyor.", executionId, finalCargoId, targetStatus);
                return; // İşlem yapmadan çık
            }

            cargo.setCurrentStatus(targetStatus);
            if (cargo.getProcessInstanceId() == null || !cargo.getProcessInstanceId().equals(processInstanceId)) {
                log.warn("Cargo ID {} için ProcessInstanceId güncelleniyor. Eski: {}, Yeni: {}", finalCargoId, cargo.getProcessInstanceId(), processInstanceId);
                cargo.setProcessInstanceId(processInstanceId);
            }
            cargoRepository.save(cargo);

            log.info("Execution ID {}: Kargo durumu başarıyla {} olarak güncellendi (Cargo ID: {}).", executionId, targetStatus, finalCargoId);

        } catch (BpmnError bpmnError) {
            log.error("Execution ID {}: BPMN Hatası oluştu (Kod: {}): {}", executionId, bpmnError.getErrorCode(), bpmnError.getMessage());
            throw bpmnError;
        }
        catch (Exception e) {
            // cargoId null değilse log mesajına ekle
            String cargoIdForLog = (cargoId != null) ? cargoId.toString() : "[Bilinmiyor]";
            log.error("Execution ID {}: Kargo durumu güncellenirken beklenmedik hata (cargoId={}): {}", executionId, cargoIdForLog, e.getMessage(), e);
            throw new BpmnError("STATUS_UPDATE_FAILED", "Kargo durumu güncellenemedi: " + e.getMessage());
        }
    }
}