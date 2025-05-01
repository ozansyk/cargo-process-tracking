package com.ozansoyak.cargo_process_tracking.service.impl;

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
import org.camunda.bpm.engine.runtime.ProcessInstance;
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

    // !!! BU DEĞERİ BPMN DOSYASINDAKİ process id İLE EŞLEŞTİRİN !!!
    private static final String CAMUNDA_PROCESS_DEFINITION_KEY = "cargoTrackingProcessExplicitCancel";

    private static final int MAX_TRACKING_NUMBER_ATTEMPTS = 10;

    @Override
    @Transactional
    public CargoResponse createCargoAndStartProcess(CreateCargoRequest request) {
        log.info("Yeni kargo oluşturma ve '{}' süreci başlatma isteği.", CAMUNDA_PROCESS_DEFINITION_KEY);

        String trackingNumber = generateUniqueTrackingNumber();
        log.debug("Takip numarası üretildi: {}", trackingNumber);

        // CreateCargoRequest'ten Cargo entity'sine map'leme (Builder veya MapStruct kullanılabilir)
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
                .currentStatus(CargoStatus.PENDING) // Başlangıç durumu PENDING
                .build();

        Cargo savedCargo = cargoRepository.save(cargo);
        log.info("Kargo veritabanına kaydedildi. ID: {}", savedCargo.getId());

        Map<String, Object> processVariables = new HashMap<>();
        String businessKey = savedCargo.getTrackingNumber();
        processVariables.put("cargoId", savedCargo.getId());
        processVariables.put("trackingNumber", savedCargo.getTrackingNumber());
        processVariables.put("isCancelled", false); // İptal bayrağını başlangıçta false yapalım

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
        // Durumu PENDING bırakıyoruz, ilk worker RECEIVED yapacak.
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
                // İsteğe bağlı: Zaten bitmişse IllegalStateException fırlatılabilir.
                // throw new IllegalStateException("Süreç zaten tamamlanmış veya iptal edilmiş.");
            }
            return;
        }


        try {
            Object currentCancelFlag = runtimeService.getVariable(processInstanceId, "isCancelled");
            if (currentCancelFlag != null && Boolean.TRUE.equals(currentCancelFlag)) {
                log.info("Süreç (ID: {}) zaten iptal olarak işaretlenmiş.", processInstanceId);
                // DB durumunu da kontrol et (garanti için)
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


    private String generateUniqueTrackingNumber() {
        for (int i = 0; i < MAX_TRACKING_NUMBER_ATTEMPTS; i++) {
            // Daha kısa ve belki daha okunaklı bir format
            String prefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
            // Daha fazla rastgelelik için daha büyük aralık
            long randomSuffix = ThreadLocalRandom.current().nextLong(1000, 10000);
            String trackingNumber = prefix + randomSuffix;

            if (!cargoRepository.existsByTrackingNumber(trackingNumber)) {
                return trackingNumber;
            }
            log.warn("Takip numarası çakışması: {}. Deneme: {}/{}", trackingNumber, i + 1, MAX_TRACKING_NUMBER_ATTEMPTS);
            // Küçük bir bekleme eklemek çakışma olasılığını azaltabilir (çok yüksek trafik yoksa gereksiz)
            // try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        }
        throw new TrackingNumberGenerationException("Benzersiz takip numarası " + MAX_TRACKING_NUMBER_ATTEMPTS + " denemede üretilemedi.");
    }
}