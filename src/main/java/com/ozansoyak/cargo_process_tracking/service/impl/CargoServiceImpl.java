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

import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
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
    private final RuntimeService runtimeService;

    private static final String FLOWABLE_PROCESS_DEFINITION_KEY = "simpleCargoProcess"; //TODO Burası processId'ye göre değişecek.

    private static final int MAX_TRACKING_NUMBER_ATTEMPTS = 10;

    @Override
    @Transactional
    public CargoResponse createCargoAndStartProcess(CreateCargoRequest request) {
        log.info("Yeni kargo oluşturma ve BASİT Flowable süreci başlatma isteği alındı.");

        String trackingNumber = generateUniqueTrackingNumber();
        log.debug("Takip numarası üretildi: {}", trackingNumber);

        Cargo cargo = Cargo.builder()
                .trackingNumber(trackingNumber)
                // Gönderici Bilgileri
                .senderName(request.getSenderName())
                .senderAddress(request.getSenderAddress())
                .senderCity(request.getSenderCity())
                .senderPhone(request.getSenderPhone())
                .senderEmail(request.getSenderEmail())
                // Alıcı Bilgileri
                .receiverName(request.getReceiverName())
                .receiverAddress(request.getReceiverAddress())
                .receiverCity(request.getReceiverCity())
                .receiverPhone(request.getReceiverPhone())
                .receiverEmail(request.getReceiverEmail())
                // Kargo Detayları
                .weight(request.getWeight())
                .dimensions(request.getDimensions())
                .contentDescription(request.getContentDescription())
                // Başlangıç durumu
                .currentStatus(CargoStatus.PENDING)
                .build();

        Cargo savedCargo = cargoRepository.save(cargo);
        log.info("Kargo veritabanına kaydedildi. ID: {}", savedCargo.getId());

        Map<String, Object> processVariables = new HashMap<>();
        String businessKey = savedCargo.getTrackingNumber();
        processVariables.put("cargoId", savedCargo.getId());
        processVariables.put("trackingNumber", savedCargo.getTrackingNumber());

        ProcessInstance processInstance;
        try {
            processInstance = runtimeService.startProcessInstanceByKey(
                    FLOWABLE_PROCESS_DEFINITION_KEY,
                    businessKey,
                    processVariables
            );
        } catch (Exception e) {
            throw new RuntimeException("Kargo süreci başlatılamadı: " + e.getMessage(), e);
        }

        String processInstanceId = processInstance.getProcessInstanceId();
        savedCargo.setProcessInstanceId(processInstanceId);
        savedCargo.setCurrentStatus(CargoStatus.RECEIVED);
        cargoRepository.save(savedCargo);

        return new CargoResponse(
                savedCargo.getId(),
                savedCargo.getTrackingNumber(),
                CargoStatus.RECEIVED.name(),
                processInstanceId
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