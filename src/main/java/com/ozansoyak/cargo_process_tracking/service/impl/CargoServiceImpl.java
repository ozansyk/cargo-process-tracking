package com.ozansoyak.cargo_process_tracking.service.impl;

import com.ozansoyak.cargo_process_tracking.dto.CreateCargoRequest;
import com.ozansoyak.cargo_process_tracking.dto.CargoResponse;
import com.ozansoyak.cargo_process_tracking.exception.TrackingNumberGenerationException;
import com.ozansoyak.cargo_process_tracking.model.Cargo;
import com.ozansoyak.cargo_process_tracking.model.enums.CargoStatus;
import com.ozansoyak.cargo_process_tracking.repository.CargoRepository;
import com.ozansoyak.cargo_process_tracking.service.CargoService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Camunda importları
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class CargoServiceImpl implements CargoService {

    private final CargoRepository cargoRepository;
    private final RuntimeService runtimeService; // Camunda RuntimeService enjekte edilecek

    // Process Definition Key (BPMN dosyasındaki process id ile aynı olmalı)
    private static final String CAMUNDA_PROCESS_DEFINITION_KEY = "simpleCargoProcess";

    private static final int MAX_TRACKING_NUMBER_ATTEMPTS = 10;

    @Override
    @Transactional
    public CargoResponse createCargoAndStartProcess(CreateCargoRequest request) {
        log.info("Yeni kargo oluşturma ve CAMUNDA süreci başlatma isteği alındı."); // Log mesajı güncellendi

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
                .currentStatus(CargoStatus.PENDING) // Başlangıç durumu PENDING
                .build();

        Cargo savedCargo = cargoRepository.save(cargo);
        log.info("Kargo veritabanına kaydedildi. ID: {}", savedCargo.getId());

        // Süreç değişkenleri
        Map<String, Object> processVariables = new HashMap<>();
        String businessKey = savedCargo.getTrackingNumber(); // Business key olarak takip numarası
        processVariables.put("cargoId", savedCargo.getId()); // Delegate'in kullanması için ID
        processVariables.put("trackingNumber", savedCargo.getTrackingNumber()); // Gerekirse süreçte kullanmak için
        // Başlatan kullanıcıyı ekleyebiliriz (Spring Security entegrasyonu varsa)
        // Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // if (auth != null && auth.isAuthenticated()) {
        //     processVariables.put("initiator", auth.getName());
        // }

        ProcessInstance processInstance;
        try {
            // Camunda RuntimeService ile süreç başlatma
            processInstance = runtimeService.startProcessInstanceByKey(
                    CAMUNDA_PROCESS_DEFINITION_KEY,
                    businessKey,
                    processVariables
            );
            log.info("Camunda süreci başlatıldı. Process Instance ID: {}, Business Key: {}",
                    processInstance.getProcessInstanceId(), businessKey);
        } catch (Exception e) {
            // Hata durumunda daha detaylı loglama ve exception fırlatma
            log.error("Camunda süreci başlatılamadı (key={}): {}", CAMUNDA_PROCESS_DEFINITION_KEY, e.getMessage(), e);
            throw new RuntimeException("Kargo süreci başlatılamadı: " + e.getMessage(), e);
        }

        // Süreç ID'sini kargo nesnesine kaydet
        // Not: Süreç hemen ilk adıma geçeceği için durumu burada RECEIVED yapmak yerine
        // PersistStatusWorker'ın yapmasını beklemek daha doğru olabilir.
        // Ancak ilk adım senkron ise ve hemen çalışacaksa, burada da set edilebilir.
        // Şimdilik Worker'a bırakalım, create response'da başlangıç durumunu gösterelim.
        savedCargo.setProcessInstanceId(processInstance.getProcessInstanceId());
        // savedCargo.setCurrentStatus(CargoStatus.RECEIVED); // Worker yapsın
        cargoRepository.save(savedCargo); // Sadece processInstanceId'yi kaydet

        // İstemciye dönülecek yanıt
        return new CargoResponse(
                savedCargo.getId(),
                savedCargo.getTrackingNumber(),
                savedCargo.getCurrentStatus().name(), // Başlangıç durumu PENDING olarak dönecek
                processInstance.getProcessInstanceId()
        );
    }

    private String generateUniqueTrackingNumber() {
        for (int i = 0; i < MAX_TRACKING_NUMBER_ATTEMPTS; i++) {
            String prefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            long randomSuffix = ThreadLocalRandom.current().nextLong(100, 1000);
            String trackingNumber = prefix + randomSuffix;

            if (!cargoRepository.existsByTrackingNumber(trackingNumber)) {
                return trackingNumber;
            }
            log.warn("Takip numarası çakışması: {}. Deneme: {}/{}", trackingNumber, i + 1, MAX_TRACKING_NUMBER_ATTEMPTS);
        }
        throw new TrackingNumberGenerationException("Benzersiz takip numarası üretilemedi.");
    }
}