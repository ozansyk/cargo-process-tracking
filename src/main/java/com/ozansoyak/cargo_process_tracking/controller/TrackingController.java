package com.ozansoyak.cargo_process_tracking.controller;

import com.ozansoyak.cargo_process_tracking.dto.TrackingInfoResponse;
import com.ozansoyak.cargo_process_tracking.service.CargoService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/track")
@RequiredArgsConstructor
@Slf4j
public class TrackingController {

    private final CargoService cargoService;

    @GetMapping
    public String showTrackingPage(Model model) {
        return "track";
    }

    @GetMapping(params = "trackingNumber")
    public String trackCargoByNumber(@RequestParam String trackingNumber, Model model) {
        log.info("Kargo takip isteği alındı: {}", trackingNumber);
        model.addAttribute("submittedTrackingNumber", trackingNumber);

        try {
            if (trackingNumber == null || trackingNumber.isBlank()) {
                model.addAttribute("errorMessage", "Lütfen geçerli bir takip numarası giriniz.");
            } else {
                TrackingInfoResponse trackingInfo = cargoService.getTrackingInfo(trackingNumber.trim());
                model.addAttribute("trackingInfo", trackingInfo);
                log.info("Takip bilgisi bulundu: {}", trackingNumber);
            }
        } catch (EntityNotFoundException e) {
            log.warn("Takip numarası bulunamadı: {}", trackingNumber);
            model.addAttribute("errorMessage", e.getMessage());
        } catch (Exception e) {
            log.error("Kargo takip sırasında beklenmedik hata oluştu (Takip No: {}): {}", trackingNumber, e.getMessage(), e);
            model.addAttribute("errorMessage", "Kargo bilgileri alınırken bir hata oluştu. Lütfen tekrar deneyin.");
        }

        return "track";
    }
}