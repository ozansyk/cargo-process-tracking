package com.ozansoyak.cargo_process_tracking.controller;

import com.ozansoyak.cargo_process_tracking.dto.CreateCargoRequest;
import com.ozansoyak.cargo_process_tracking.dto.CargoResponse;
import com.ozansoyak.cargo_process_tracking.service.CargoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
// import org.springframework.security.access.prepost.PreAuthorize; // Yetkilendirme için
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
        log.info("POST /api/cargos isteği alındı: {}", request.getReceiverName());
        try {
            CargoResponse response = cargoService.createCargoAndStartProcess(request);
            log.info("Kargo başarıyla oluşturuldu ve süreç başlatıldı: {}", response);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            log.error("Kargo oluşturulurken hata oluştu: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Kargo oluşturulurken bir hata oluştu: " + e.getMessage());
        }
    }

    @GetMapping("/track")
    public ResponseEntity<?> trackCargo(@RequestParam String trackingNumber) {
        // TODO: CargoService'e getTrackingInfo(trackingNumber) metodu eklenecek
        return null;
    }

}