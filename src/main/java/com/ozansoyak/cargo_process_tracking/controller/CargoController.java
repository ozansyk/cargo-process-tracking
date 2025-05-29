package com.ozansoyak.cargo_process_tracking.controller;

import com.ozansoyak.cargo_process_tracking.dto.CreateCargoRequest;
import com.ozansoyak.cargo_process_tracking.dto.CargoResponse;
import com.ozansoyak.cargo_process_tracking.dto.TaskCompletionResponse;
import com.ozansoyak.cargo_process_tracking.dto.TrackingInfoResponse;
import com.ozansoyak.cargo_process_tracking.service.CargoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.StringUtils;

import java.util.Map;

@RestController
@RequestMapping("/api/cargos")
@RequiredArgsConstructor
@Slf4j
public class CargoController {

    private final CargoService cargoService;

    @PostMapping
    public ResponseEntity<?> createCargoAndStartProcess(@Valid @RequestBody CreateCargoRequest request) {
        log.info("POST /api/cargos isteği alındı. Alıcı: {}", request.getReceiverName());
        try {
            CargoResponse response = cargoService.createCargoAndStartProcess(request);
            log.info("Kargo başarıyla oluşturuldu ve süreç başlatıldı: Takip No: {}", response.getTrackingNumber());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            log.error("Kargo oluşturulurken hata oluştu: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Kargo oluşturulurken bir hata oluştu: " + e.getMessage()));
        }
    }

    @PutMapping("/{trackingNumber}/cancel")
    public ResponseEntity<?> cancelCargoProcess(@PathVariable String trackingNumber) {
        log.info("PUT /api/cargos/{}/cancel isteği alındı.", trackingNumber);
        if (!StringUtils.hasText(trackingNumber)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "Takip numarası boş olamaz."));
        }
        try {
            cargoService.cancelCargoProcess(trackingNumber.trim());
            log.info("Takip numarası {} olan kargo için iptal işlemi başarıyla tetiklendi/tamamlandı.", trackingNumber);
            return ResponseEntity.ok().body(Map.of("message", "Kargo süreci iptal edildi veya iptal işlemi başlatıldı."));
        } catch (Exception e) {
            log.error("Kargo iptali sırasında beklenmedik hata oluştu (Takip No: {}): {}", trackingNumber, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Kargo iptal edilirken bir hata oluştu: " + e.getMessage()));
        }
    }

    @PutMapping("/{trackingNumber}/complete-step/{taskDefinitionKey}")
    public ResponseEntity<?> completeUserTaskAndPrepareNextStep(
            @PathVariable String trackingNumber,
            @PathVariable String taskDefinitionKey,
            @RequestBody(required = false) Map<String, Object> taskVariables) {
        log.info("PUT /api/cargos/{}/complete-step/{} isteği alındı. Değişkenler: {}",
                trackingNumber, taskDefinitionKey, (taskVariables != null && !taskVariables.isEmpty()) ? taskVariables.keySet() : "Yok");
        try {
            TaskCompletionResponse completionResponse = cargoService.completeUserTaskAndPrepareNextStep(trackingNumber.trim(), taskDefinitionKey.trim(), taskVariables);
            log.info("Takip numarası {} için '{}' görevi tamamlandı ve sonraki adım hazırlandı.", trackingNumber, taskDefinitionKey);
            return ResponseEntity.ok(completionResponse);
        } catch (Exception e) {
            log.error("Görev ('{}') tamamlama sırasında beklenmedik hata (Takip No: {}): {}", taskDefinitionKey, trackingNumber, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Görev tamamlanırken bir sunucu hatası oluştu: " + e.getMessage()));
        }
    }

    @GetMapping("/details/{trackingNumber}")
    public ResponseEntity<?> getCargoDetailsForModal(@PathVariable String trackingNumber) {
        log.info("GET /api/cargos/details/{} isteği alındı (Modal için).", trackingNumber);
        try {
            TrackingInfoResponse trackingInfo = cargoService.getTrackingInfo(trackingNumber.trim());
            log.debug("Takip bilgisi bulundu (API-Detay): {} -> {}", trackingNumber, trackingInfo.getCurrentStatus());
            return ResponseEntity.ok(trackingInfo);
        } catch (Exception e) {
            log.error("Kargo detay (API) alınırken beklenmedik hata (Takip No: {}): {}", trackingNumber, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Detay bilgileri alınırken sunucu hatası oluştu."));
        }
    }

    @PutMapping("/tasks/{taskId}/complete")
    public ResponseEntity<?> completeTaskById(@PathVariable String taskId) {
        log.info("PUT /api/cargos/tasks/{}/complete isteği alındı.", taskId);
        try {
            cargoService.completeTaskByIdAndPrepareNextStep(taskId);
            return ResponseEntity.ok(Map.of("message", "Görev (ID: " + taskId + ") başarıyla tamamlandı."));
        } catch (Exception e) {
            log.error("Görev (ID: {}) tamamlanırken beklenmedik hata: {}", taskId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Görev tamamlanırken bir hata oluştu: " + e.getMessage()));
        }
    }
}