package com.ozansoyak.cargo_process_tracking.camundaworker;

import com.ozansoyak.cargo_process_tracking.model.Cargo;
import com.ozansoyak.cargo_process_tracking.model.enums.CargoStatus;
import com.ozansoyak.cargo_process_tracking.repository.CargoRepository;
import com.ozansoyak.cargo_process_tracking.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
// Camunda Model API importları
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.ServiceTask; // ServiceTask olmalı
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperties;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collection; // Eklendi/Kontrol edildi
import java.util.List;      // Eklendi/Kontrol edildi
import java.util.Optional;

@Component("cargoStatusUpdater") // BPMN'deki delegateExpression ile aynı olmalı
@RequiredArgsConstructor
@Slf4j
public class CargoStatusUpdateWorker implements JavaDelegate {

    private final CargoRepository cargoRepository;
    private final EmailService emailService;
    private static final String TARGET_STATUS_PROPERTY_NAME = "targetStatus"; // Extension Property adı

    @Override
    @Transactional // Veritabanı işlemi olduğu için transactional olmalı
    public void execute(DelegateExecution execution) {
        String activityId = execution.getCurrentActivityId();
        String activityName = execution.getCurrentActivityName(); // Loglama için daha açıklayıcı olabilir
        String executionId = execution.getId();
        String processInstanceId = execution.getProcessInstanceId();

        log.info("CargoStatusUpdateWorker tetiklendi. Activity: '{}' ({}), Execution ID: {}, PI ID: {}",
                activityName, activityId, executionId, processInstanceId);

        CargoStatus targetStatus = null;
        Long cargoId = null;

        try {
            // 1. Hedef Durumu BPMN Extension Property'den Al
            ServiceTask serviceTask = (ServiceTask) execution.getBpmnModelElementInstance();
            ExtensionElements extensionElements = serviceTask.getExtensionElements();

            if (extensionElements == null) {
                log.error("[{}] Activity '{}' için ExtensionElements bulunamadı.", executionId, activityId);
                throw new BpmnError("CONFIG_ERROR_NO_EXTENSIONS", "ExtensionElements eksik: " + activityId);
            }

            // CamundaProperties listesini al
            List<CamundaProperties> propertiesList = extensionElements.getElementsQuery()
                    .filterByType(CamundaProperties.class)
                    .list(); // list() kullanıldı

            if (propertiesList.isEmpty()) {
                log.error("[{}] Activity '{}' için <camunda:properties> bulunamadı.", executionId, activityId);
                throw new BpmnError("CONFIG_ERROR_NO_PROPERTIES", "<camunda:properties> eksik: " + activityId);
            }
            if (propertiesList.size() > 1) {
                log.warn("[{}] Activity '{}' için birden fazla <camunda:properties> bulundu. İlki kullanılacak.", executionId, activityId);
            }
            CamundaProperties camundaProperties = propertiesList.get(0); // İlkini al

            // İlgili property'yi collection içinde ara
            Collection<CamundaProperty> propsCollection = camundaProperties.getCamundaProperties();
            Optional<CamundaProperty> targetStatusProperty = propsCollection.stream() // stream() collection üzerinde çağrıldı
                    .filter(prop -> TARGET_STATUS_PROPERTY_NAME.equals(prop.getCamundaName())) // getCamundaName doğru yerde
                    .findFirst();

            if (targetStatusProperty.isEmpty()) {
                log.error("[{}] Activity '{}' için '{}' Camunda Property bulunamadı.", executionId, activityId, TARGET_STATUS_PROPERTY_NAME);
                throw new BpmnError("CONFIG_ERROR_NO_TARGET_STATUS", "Gerekli property eksik: " + TARGET_STATUS_PROPERTY_NAME);
            }

            String targetStatusStr = targetStatusProperty.get().getCamundaValue();
            if (targetStatusStr == null || targetStatusStr.isBlank()) {
                log.error("[{}] Activity '{}' için '{}' property değeri boş.", executionId, activityId, TARGET_STATUS_PROPERTY_NAME);
                throw new BpmnError("CONFIG_ERROR_EMPTY_TARGET_STATUS", "'" + TARGET_STATUS_PROPERTY_NAME + "' değeri boş olamaz.");
            }

            // 2. String Değeri CargoStatus Enum'una Çevir
            try {
                targetStatus = CargoStatus.valueOf(targetStatusStr.trim().toUpperCase());
                log.debug("[{}] Hedeflenen kargo durumu BPMN'den okundu: {}", executionId, targetStatus);
            } catch (IllegalArgumentException e) {
                log.error("[{}] Geçersiz kargo durumu değeri ('{}') BPMN'de tanımlanmış (Activity: {}).", executionId, targetStatusStr, activityId);
                throw new BpmnError("CONFIG_ERROR_INVALID_STATUS_ENUM", "Geçersiz kargo durumu enum değeri: " + targetStatusStr);
            }

            // 3. Süreç Değişkeninden cargoId'yi Al
            Object cargoIdObj = execution.getVariable("cargoId");
            if (cargoIdObj == null) {
                log.error("[{}] 'cargoId' süreç değişkeni bulunamadı veya null.", executionId);
                throw new BpmnError("PROCESS_VARIABLE_MISSING", "Gerekli süreç değişkeni eksik: cargoId");
            }

            // Farklı tipleri Long'a çevir
            if (cargoIdObj instanceof Long) {
                cargoId = (Long) cargoIdObj;
            } else if (cargoIdObj instanceof Number) { // Integer vs. olabilir
                cargoId = ((Number) cargoIdObj).longValue();
            } else if (cargoIdObj instanceof String) {
                try {
                    cargoId = Long.parseLong((String) cargoIdObj);
                } catch (NumberFormatException nfe) {
                    log.error("[{}] 'cargoId' değişkeni ('{}') Long'a çevrilemedi.", executionId, cargoIdObj);
                    throw new BpmnError("PROCESS_VARIABLE_INVALID_TYPE", "'cargoId' Long'a çevrilemiyor.");
                }
            } else {
                log.error("[{}] 'cargoId' değişkeninin tipi beklenmedik: {}", executionId, cargoIdObj.getClass().getName());
                throw new BpmnError("PROCESS_VARIABLE_UNEXPECTED_TYPE", "'cargoId' tipi geçersiz.");
            }

            log.debug("[{}] Güncellenecek kargo ID: {}", executionId, cargoId);
            final Long finalCargoId = cargoId; // Lambda için

            // 4. Veritabanından Kargoyu Bul
            Cargo cargo = cargoRepository.findById(finalCargoId)
                    .orElseThrow(() -> {
                        log.error("[{}] Veritabanında kargo bulunamadı, ID: {}", executionId, finalCargoId);
                        // Süreci durdurmalı, BpmnError fırlat
                        return new BpmnError("DATA_ERROR_CARGO_NOT_FOUND", "Kargo bulunamadı: ID " + finalCargoId);
                    });

            // 5. Durumu Güncelle (Eğer zaten aynı değilse)
            if (cargo.getCurrentStatus() == targetStatus) {
                log.warn("[{}] Kargo (ID: {}) zaten '{}' durumunda. Güncelleme atlanıyor.", executionId, finalCargoId, targetStatus);
            } else {
                log.info("[{}] Kargo (ID: {}) durumu '{}' -> '{}' olarak güncelleniyor.", executionId, finalCargoId, cargo.getCurrentStatus(), targetStatus);
                cargo.setCurrentStatus(targetStatus);
                // ProcessInstanceId'yi de kontrol edip güncellemek iyi bir pratik olabilir
                if (cargo.getProcessInstanceId() == null || !cargo.getProcessInstanceId().equals(processInstanceId)) {
                    log.warn("[{}] Cargo ID {} için ProcessInstanceId güncelleniyor. Eski: {}, Yeni: {}", executionId, finalCargoId, cargo.getProcessInstanceId(), processInstanceId);
                    cargo.setProcessInstanceId(processInstanceId);
                }
                cargoRepository.save(cargo);
                log.info("[{}] Kargo (ID: {}) durumu başarıyla {} olarak güncellendi.", executionId, finalCargoId, targetStatus);
                if (StringUtils.hasText(cargo.getReceiverEmail())) {
                    emailService.sendChangedCargoStatusToReceiver(
                            cargo.getReceiverEmail(),
                            cargo.getTrackingNumber(),
                            targetStatus,
                            cargo
                    );
                } else {
                    log.warn("Kargo ID {} için alıcı e-posta adresi bulunamadığından durum güncelleme e-postası gönderilemedi.", cargo.getId());
                }
            }

        } catch (BpmnError bpmnError) {
            // Yakalanan BpmnError'ları logla ve tekrar fırlat (Camunda bunları yönetir)
            log.error("[{}] BPMN Hatası: Kod='{}', Mesaj='{}'", executionId, bpmnError.getErrorCode(), bpmnError.getMessage(), bpmnError);
            throw bpmnError;
        } catch (Exception e) {
            // Diğer beklenmedik hataları (DB hatası vs.) logla ve genel bir BpmnError fırlat
            String cargoIdForLog = (cargoId != null) ? cargoId.toString() : "[Bilinmiyor]";
            String targetStatusForLog = (targetStatus != null) ? targetStatus.name() : "[Belirlenemedi]";
            log.error("[{}] Kargo durumu güncellenirken (hedef: {}, cargoId: {}) beklenmedik hata: {}",
                    executionId, targetStatusForLog, cargoIdForLog, e.getMessage(), e);
            // Süreci durdurmak için BpmnError fırlat
            throw new BpmnError("WORKER_UNEXPECTED_ERROR", "Durum güncellenemedi: " + e.getMessage());
        }
    }
}