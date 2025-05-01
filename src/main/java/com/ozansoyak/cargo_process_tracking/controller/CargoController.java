package com.ozansoyak.cargo_process_tracking.controller;

// ApproveNextStepRequest importu kaldırıldı
import com.ozansoyak.cargo_process_tracking.dto.CreateCargoRequest;
import com.ozansoyak.cargo_process_tracking.dto.CargoResponse;
import com.ozansoyak.cargo_process_tracking.service.CargoService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
// TaskNotFoundException importu KALDIRILDI
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/cargos")
@RequiredArgsConstructor
@Slf4j
public class CargoController {

    private final CargoService cargoService;

    @PostMapping
    public ResponseEntity<?> createCargoAndStartProcess(@Valid @RequestBody CreateCargoRequest request) {
        // ... (Aynı)
        log.info("POST /api/cargos isteği alındı: {}", request.getReceiverName());
        try {
            CargoResponse response = cargoService.createCargoAndStartProcess(request);
            log.info("Kargo başarıyla oluşturuldu ve süreç başlatıldı: Takip No: {}", response.getTrackingNumber());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            log.error("Kargo oluşturulurken hata oluştu: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Kargo oluşturulurken bir hata oluştu: " + e.getMessage());
        }
    }

    @PutMapping("/{trackingNumber}/cancel")
    public ResponseEntity<?> cancelCargoProcess(@PathVariable(name = "trackingNumber") String trackingNumber) {
        // ... (Aynı)
        log.info("PUT /api/cargos/{}/cancel isteği alındı.", trackingNumber);
        try {
            cargoService.cancelCargoProcess(trackingNumber);
            log.info("Takip numarası {} olan kargo için iptal işlemi başarıyla tetiklendi/tamamlandı.", trackingNumber);
            return ResponseEntity.ok().body("Kargo süreci iptal edildi veya iptal işlemi başlatıldı.");
        } catch (EntityNotFoundException e) {
            log.warn("Kargo iptal edilemedi, bulunamadı: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (IllegalStateException e) {
            log.warn("Kargo iptal edilemedi, geçersiz durum: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
        catch (Exception e) {
            log.error("Kargo iptali sırasında beklenmedik hata oluştu (Takip No: {}): {}", trackingNumber, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Kargo iptal edilirken bir hata oluştu: " + e.getMessage());
        }
    }

    // YENİ ENDPOINT: Aktif görevi tamamlar ve sonraki adımı hazırlar
    @PutMapping("/{trackingNumber}/complete-step")
    public ResponseEntity<?> completeUserTaskAndPrepareNextStep(@PathVariable(name = "trackingNumber") String trackingNumber) {
        log.info("PUT /api/cargos/{}/complete-step isteği alındı.", trackingNumber);
        try {
            cargoService.completeUserTaskAndPrepareNextStep(trackingNumber);
            log.info("Takip numarası {} için aktif görev tamamlandı ve sonraki adım hazırlandı.", trackingNumber);
            return ResponseEntity.ok().body("Aktif görev tamamlandı, süreç ilerletildi.");
        } catch (EntityNotFoundException e) { // Kargo veya Aktif Görev bulunamadığında bu dönebilir
            log.warn("Görev tamamlanamadı, kargo veya aktif görev bulunamadı: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
            // ------- DÜZELTME: TaskNotFoundException yerine IllegalStateException veya genel Exception yakala --------
        } catch (IllegalArgumentException e) { // Tanımsız görev veya durum için
            log.warn("Görev tamamlanamadı, geçersiz istek/durum: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (IllegalStateException e) { // Aktif süreç yoksa veya Camunda görev tamamlama hatası
            log.warn("Görev tamamlanamadı, geçersiz süreç durumu: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (Exception e) { // Diğer beklenmedik hatalar
            log.error("Görev tamamlama sırasında beklenmedik hata oluştu (Takip No: {}): {}", trackingNumber, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Görev tamamlanırken bir hata oluştu: " + e.getMessage());
        }
    }

    // approve-step ENDPOINT'İ KALDIRILDI

    @GetMapping("/track")
    public ResponseEntity<?> trackCargo(@RequestParam String trackingNumber) {
        // ... (Aynı)
        log.warn("GET /track endpoint'i henüz implemente edilmedi.");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body("Takip özelliği henüz aktif değil.");
    }
}