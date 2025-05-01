package com.ozansoyak.cargo_process_tracking.service.impl;

// ApproveNextStepRequest importu kaldırıldı
import com.ozansoyak.cargo_process_tracking.dto.CreateCargoRequest;
import com.ozansoyak.cargo_process_tracking.dto.CargoResponse;
import com.ozansoyak.cargo_process_tracking.exception.TrackingNumberGenerationException;
import com.ozansoyak.cargo_process_tracking.model.Cargo;
import com.ozansoyak.cargo_process_tracking.model.enums.CargoStatus;
import com.ozansoyak.cargo_process_tracking.repository.CargoRepository;
import com.ozansoyak.cargo_process_tracking.service.CargoService;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService; // TaskService eklendi
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task; // Task eklendi
// TaskNotFoundException importu KALDIRILDI
// import org.camunda.bpm.engine.exception.NotFoundException; // İsterseniz bunu yakalayabilirsiniz

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class CargoServiceImpl implements CargoService {

    private final CargoRepository cargoRepository;
    private final RuntimeService runtimeService;
    private final TaskService taskService;

    private static final String CAMUNDA_PROCESS_DEFINITION_KEY = "cargoTrackingProcessConditionalAndUserTaskAfterV2";

    private static final int MAX_TRACKING_NUMBER_ATTEMPTS = 10;

    private static final Map<String, CargoStatus> TASK_KEY_TO_NEXT_STATUS_MAP = Map.ofEntries(
            Map.entry("userTask_ConfirmReceived", CargoStatus.LOADED_ON_VEHICLE_1),
            Map.entry("userTask_ConfirmLoaded1", CargoStatus.AT_TRANSFER_CENTER),
            Map.entry("userTask_ConfirmTransfer", CargoStatus.LOADED_ON_VEHICLE_2),
            Map.entry("userTask_ConfirmLoaded2", CargoStatus.AT_DISTRIBUTION_HUB),
            Map.entry("userTask_ConfirmDistribution", CargoStatus.OUT_FOR_DELIVERY),
            Map.entry("userTask_ConfirmOutDelivery", CargoStatus.DELIVERED)
            //Map.entry("userTask_ConfirmDelivered", null)
    );

    private static final Map<CargoStatus, String> NEXT_STATUS_TO_VARIABLE_MAP = Map.ofEntries(
            Map.entry(CargoStatus.LOADED_ON_VEHICLE_1, "canProceedToLoaded1"),
            Map.entry(CargoStatus.AT_TRANSFER_CENTER, "canProceedToTransfer"),
            Map.entry(CargoStatus.LOADED_ON_VEHICLE_2, "canProceedToLoaded2"),
            Map.entry(CargoStatus.AT_DISTRIBUTION_HUB, "canProceedToDistribution"),
            Map.entry(CargoStatus.OUT_FOR_DELIVERY, "canProceedToOutForDelivery"),
            Map.entry(CargoStatus.DELIVERED, "canProceedToDelivered")
    );

    @Override
    @Transactional
    public CargoResponse createCargoAndStartProcess(CreateCargoRequest request) {
        // ... (öncekiyle aynı)
        log.info("Yeni kargo oluşturma ve '{}' süreci başlatma isteği.", CAMUNDA_PROCESS_DEFINITION_KEY);

        String trackingNumber = generateUniqueTrackingNumber();
        log.debug("Takip numarası üretildi: {}", trackingNumber);

        Cargo cargo = Cargo.builder()
                .trackingNumber(trackingNumber)
                .senderName(request.getSenderName())
                .senderAddress(request.getSenderAddress())
                .senderCity(request.getSenderCity())
                .senderPhone(request.getSenderPhone())
                .senderEmail(request.getSenderEmail())
                .receiverName(request.getReceiverName())
                .receiverAddress(request.getReceiverAddress())
                .receiverCity(request.getReceiverCity())
                .receiverPhone(request.getReceiverPhone())
                .receiverEmail(request.getReceiverEmail())
                .weight(request.getWeight())
                .dimensions(request.getDimensions())
                .contentDescription(request.getContentDescription())
                .currentStatus(CargoStatus.PENDING)
                .build();

        Cargo savedCargo = cargoRepository.save(cargo);
        log.info("Kargo veritabanına kaydedildi. ID: {}", savedCargo.getId());

        Map<String, Object> processVariables = new HashMap<>();
        String businessKey = savedCargo.getTrackingNumber();
        processVariables.put("cargoId", savedCargo.getId());
        processVariables.put("trackingNumber", savedCargo.getTrackingNumber());
        processVariables.put("isCancelled", false);
        NEXT_STATUS_TO_VARIABLE_MAP.values().forEach(varName -> processVariables.put(varName, false));

        ProcessInstance processInstance;
        try {
            processInstance = runtimeService.startProcessInstanceByKey(
                    CAMUNDA_PROCESS_DEFINITION_KEY,
                    businessKey,
                    processVariables
            );
            log.info("Camunda süreci başlatıldı. Process Instance ID: {}, Business Key: {}",
                    processInstance.getProcessInstanceId(), businessKey);
        } catch (Exception e) {
            log.error("Camunda süreci başlatılamadı (key={}): {}", CAMUNDA_PROCESS_DEFINITION_KEY, e.getMessage(), e);
            throw new RuntimeException("Kargo süreci başlatılamadı: " + e.getMessage(), e);
        }

        savedCargo.setProcessInstanceId(processInstance.getProcessInstanceId());
        cargoRepository.save(savedCargo);

        return new CargoResponse(
                savedCargo.getId(),
                savedCargo.getTrackingNumber(),
                savedCargo.getCurrentStatus().name(),
                processInstance.getProcessInstanceId()
        );
    }

    @Override
    @Transactional
    public void cancelCargoProcess(String trackingNumber) {
        // ... (öncekiyle aynı)
        log.info("{} takip numaralı kargo için iptal işlemi başlatıldı.", trackingNumber);

        Cargo cargo = cargoRepository.findByTrackingNumber(trackingNumber)
                .orElseThrow(() -> {
                    log.warn("İptal işlemi: Takip numarası ({}) ile kargo bulunamadı.", trackingNumber);
                    return new EntityNotFoundException("Takip numarası ile kargo bulunamadı: " + trackingNumber);
                });

        String processInstanceId = cargo.getProcessInstanceId();
        if (processInstanceId == null) {
            log.warn("İptal işlemi: Kargo (ID: {}) için Camunda Process Instance ID bulunamadı.", cargo.getId());
            if(cargo.getCurrentStatus() != CargoStatus.CANCELLED && cargo.getCurrentStatus() != CargoStatus.DELIVERED) {
                cargo.setCurrentStatus(CargoStatus.CANCELLED);
                cargoRepository.save(cargo);
                log.info("Kargo (ID: {}) durumu Process Instance ID olmadığı için direkt CANCELLED yapıldı.", cargo.getId());
            } else {
                log.info("Kargo (ID: {}) zaten iptal edilmiş veya teslim edilmiş durumda (Süreç ID'si yok).", cargo.getId());
            }
            return;
        }

        ProcessInstance processInstance = null;
        try {
            processInstance = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .active()
                    .singleResult();
        } catch(ProcessEngineException pee) {
            log.error("Camunda process instance sorgulanırken hata (ID: {}): {}", processInstanceId, pee.getMessage(), pee);
            throw new RuntimeException("Süreç sorgulanırken Camunda hatası: " + pee.getMessage(), pee);
        }

        if (processInstance == null) {
            log.warn("İptal işlemi: Aktif Camunda süreci bulunamadı (ID: {}). Kargo durumu: {}", processInstanceId, cargo.getCurrentStatus());
            if (cargo.getCurrentStatus() != CargoStatus.CANCELLED && cargo.getCurrentStatus() != CargoStatus.DELIVERED) {
                log.info("Aktif süreç yok, kargo durumu (ID: {}) manuel olarak CANCELLED yapılıyor.", cargo.getId());
                cargo.setCurrentStatus(CargoStatus.CANCELLED);
                cargoRepository.save(cargo);
            } else {
                log.info("Süreç (ID: {}) zaten tamamlanmış ({}) veya iptal edilmiş.", processInstanceId, cargo.getCurrentStatus());
            }
            return;
        }

        try {
            Object currentCancelFlag = runtimeService.getVariable(processInstanceId, "isCancelled");
            if (currentCancelFlag != null && Boolean.TRUE.equals(currentCancelFlag)) {
                log.info("Süreç (ID: {}) zaten iptal olarak işaretlenmiş.", processInstanceId);
                if(cargo.getCurrentStatus() != CargoStatus.CANCELLED) {
                    log.warn("Süreç iptal edilmiş ama DB durumu farklı ({}). DB durumu CANCELLED yapılıyor.", cargo.getCurrentStatus());
                    Optional<Cargo> latestCargo = cargoRepository.findById(cargo.getId());
                    if(latestCargo.isPresent() && latestCargo.get().getCurrentStatus() != CargoStatus.CANCELLED) {
                        latestCargo.get().setCurrentStatus(CargoStatus.CANCELLED);
                        cargoRepository.save(latestCargo.get());
                    }
                }
                return;
            }

            runtimeService.setVariable(processInstanceId, "isCancelled", true);
            log.info("Camunda süreci (ID: {}) için 'isCancelled' değişkeni 'true' olarak ayarlandı.", processInstanceId);

        } catch (ProcessEngineException e) {
            log.error("Camunda süreci (ID: {}) iptal değişkeni ayarlanırken hata: {}", processInstanceId, e.getMessage(), e);
            throw new RuntimeException("Süreç iptal edilirken Camunda hatası: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public void completeUserTaskAndPrepareNextStep(String trackingNumber) { // Düzeltilmiş Metot
        log.info("{} takip numaralı kargo için aktif görevi tamamlama ve sonraki adımı hazırlama isteği.", trackingNumber);

        Cargo cargo = cargoRepository.findByTrackingNumber(trackingNumber)
                .orElseThrow(() -> new EntityNotFoundException("Takip numarası ile kargo bulunamadı: " + trackingNumber));

        String processInstanceId = cargo.getProcessInstanceId();
        if (processInstanceId == null) {
            throw new IllegalStateException("Kargo (ID: " + cargo.getId() + ") için bir süreç ID'si bulunamadı.");
        }

        ProcessInstance processInstance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .active()
                .singleResult();
        if (processInstance == null) {
            log.warn("Aktif Camunda süreci bulunamadı (ID: {}). Kargo durumu: {}", processInstanceId, cargo.getCurrentStatus());
            throw new IllegalStateException("Kargo (Takip No: " + trackingNumber + ") için aktif Camunda süreci bulunamadı.");
        }

        Task activeTask = null;
        try {
            activeTask = taskService.createTaskQuery()
                    .processInstanceId(processInstanceId)
                    .active()
                    .singleResult();
        } catch(ProcessEngineException e) {
            log.error("Aktif görev sorgulanırken hata oluştu (Process Instance ID: {}): {}", processInstanceId, e.getMessage(), e);
            throw new RuntimeException("Aktif görev sorgulanamadı: " + e.getMessage(), e);
        }

        // ------- DÜZELTME: Task null kontrolü eklendi --------
        if (activeTask == null) {
            log.warn("Takip numarası {} için tamamlanacak aktif kullanıcı görevi bulunamadı (Process Instance ID: {}). Süreç zaten ilerlemiş veya bitmiş olabilir.", trackingNumber, processInstanceId);
            // TaskNotFoundException yerine daha uygun bir exception fırlatabiliriz
            // veya sadece işlemi sonlandırabiliriz.
            throw new EntityNotFoundException("Bu kargo için tamamlanacak aktif görev bulunamadı (Süreç ilerlemiş olabilir).");
        }

        String taskId = activeTask.getId();
        String taskDefinitionKey = activeTask.getTaskDefinitionKey();
        log.info("Aktif görev bulundu: Task ID: {}, Task Definition Key: {}", taskId, taskDefinitionKey);

        // ------- DÜZELTME: Map.get() null dönebilir, kontrol ediliyor --------
        CargoStatus nextStatus = TASK_KEY_TO_NEXT_STATUS_MAP.get(taskDefinitionKey);
        // Son görev tamamlanıyorsa nextStatus null olabilir, bu normal.
        if (nextStatus == null && !TASK_KEY_TO_NEXT_STATUS_MAP.containsKey(taskDefinitionKey) && !"userTask_ConfirmDelivered".equals(taskDefinitionKey) ) {
            // Eğer map'te key yoksa VE bu beklenen son görev değilse hata ver.
            log.error("Bilinmeyen task definition key '{}' için sonraki durum belirlenemedi.", taskDefinitionKey);
            throw new IllegalArgumentException("Tamamlanan görev ('" + taskDefinitionKey + "') için bir sonraki adım tanımlı değil.");
        }

        String variableNameToSet = null;
        if (nextStatus != null) {
            variableNameToSet = NEXT_STATUS_TO_VARIABLE_MAP.get(nextStatus);
            if (variableNameToSet == null) {
                log.error("'{}' hedef durumu için ayarlanacak süreç değişkeni bulunamadı.", nextStatus);
                throw new IllegalArgumentException("Belirlenen sonraki durum ('" + nextStatus + "') için ilerleme değişkeni tanımlı değil.");
            }
        } else {
            log.info("Son kullanıcı görevi ('{}') tamamlanıyor, ayarlanacak ilerleme değişkeni yok.", taskDefinitionKey);
        }

        if (variableNameToSet != null) {
            try {
                Object currentVariableValue = runtimeService.getVariable(processInstanceId, variableNameToSet);
                if (currentVariableValue == null || Boolean.FALSE.equals(currentVariableValue)) {
                    runtimeService.setVariable(processInstanceId, variableNameToSet, true);
                    log.info("Camunda süreci (ID: {}) için '{}' değişkeni 'true' olarak ayarlandı.", processInstanceId, variableNameToSet);
                } else {
                    log.info("Süreç (ID: {}) için '{}' değişkeni zaten 'true' idi.", processInstanceId, variableNameToSet);
                }
            } catch (ProcessEngineException e) {
                log.error("Camunda süreci (ID: {}) ilerleme değişkeni ('{}') ayarlanırken hata: {}",
                        processInstanceId, variableNameToSet, e.getMessage(), e);
                throw new RuntimeException("Süreç ilerleme onayı verilirken Camunda hatası: " + e.getMessage(), e);
            }
        }

        try {
            taskService.complete(taskId);
            log.info("Aktif görev (Task ID: {}) başarıyla tamamlandı. Sürecin ilerlemesi bekleniyor.", taskId);
            // ------- DÜZELTME: Catch bloğu güncellendi --------
        } catch (ProcessEngineException e){ // Daha genel Camunda hatasını yakala
            log.error("Görev (Task ID: {}) tamamlanırken Camunda hatası: {}. Task bulunamadı veya başka bir sorun olabilir.", taskId, e.getMessage(), e);
            // Hatanın nedenini loglamak önemli. Tekrar fırlatabiliriz.
            throw new IllegalStateException("Görev tamamlanamadı: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Görev (Task ID: {}) tamamlanırken beklenmedik hata: {}", taskId, e.getMessage(), e);
            throw e;
        }
    }

    // approveNextStep METODU KALDIRILDI

    private String generateUniqueTrackingNumber() {
        // ... (öncekiyle aynı)
        for (int i = 0; i < MAX_TRACKING_NUMBER_ATTEMPTS; i++) {
            String prefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
            long randomSuffix = ThreadLocalRandom.current().nextLong(1000, 10000);
            String trackingNumber = prefix + randomSuffix;

            if (!cargoRepository.existsByTrackingNumber(trackingNumber)) {
                return trackingNumber;
            }
            log.warn("Takip numarası çakışması: {}. Deneme: {}/{}", trackingNumber, i + 1, MAX_TRACKING_NUMBER_ATTEMPTS);
        }
        throw new TrackingNumberGenerationException("Benzersiz takip numarası " + MAX_TRACKING_NUMBER_ATTEMPTS + " denemede üretilemedi.");
    }
}