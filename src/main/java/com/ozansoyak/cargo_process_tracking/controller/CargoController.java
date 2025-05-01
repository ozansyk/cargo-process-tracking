package com.ozansoyak.cargo_process_tracking.controller;

import com.ozansoyak.cargo_process_tracking.dto.CreateCargoRequest;
import com.ozansoyak.cargo_process_tracking.dto.CargoResponse;
import com.ozansoyak.cargo_process_tracking.service.CargoService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
// import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cargos")
@RequiredArgsConstructor
@Slf4j
public class CargoController {

    private final CargoService cargoService;

    @PostMapping
    // @PreAuthorize("hasRole('ROLE_EMPLOYEE') or hasRole('ROLE_ADMIN')")
    public ResponseEntity<?> createCargoAndStartProcess(@Valid @RequestBody CreateCargoRequest request) {
        log.info("POST /api/cargos isteği alındı: {}", request.getReceiverName()); // Loglamada hassas veri olmamasına dikkat
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
    // @PreAuthorize("hasRole('ROLE_EMPLOYEE') or hasRole('ROLE_ADMIN')")
    public ResponseEntity<?> cancelCargoProcess(@PathVariable String trackingNumber) {
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
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage()); // 409 Conflict
        }
        catch (Exception e) {
            log.error("Kargo iptali sırasında beklenmedik hata oluştu (Takip No: {}): {}", trackingNumber, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Kargo iptal edilirken bir hata oluştu: " + e.getMessage());
        }
    }


    @GetMapping("/track")
    public ResponseEntity<?> trackCargo(@RequestParam String trackingNumber) {
        // TODO: CargoService'e getTrackingInfo(trackingNumber) metodu eklenecek
        log.warn("GET /track endpoint'i henüz implemente edilmedi.");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body("Takip özelliği henüz aktif değil.");
    }

}