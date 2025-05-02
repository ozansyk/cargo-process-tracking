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
@RequestMapping("/track") // Bu path'i kullanacağız
@RequiredArgsConstructor
@Slf4j
public class TrackingController {

    private final CargoService cargoService;

    /**
     * Takip sayfasını ilk açan veya sorgu parametresi olmadan gelen istekleri karşılar.
     */
    @GetMapping
    public String showTrackingPage(Model model) {
        // Başlangıçta model'e bir şey eklemeye gerek yok,
        // sadece boş formu gösterelim.
        // model.addAttribute("trackingInfo", null); // Thymeleaf null kontrolü yapacak zaten
        // model.addAttribute("errorMessage", null);
        return "track"; // resources/templates/track.html dosyasını döndürür
    }

    /**
     * Takip numarası ile yapılan sorgu isteklerini karşılar.
     * Örn: /track?trackingNumber=XYZ123
     */
    @GetMapping(params = "trackingNumber") // trackingNumber parametresi varsa bu metot çalışır
    public String trackCargoByNumber(@RequestParam String trackingNumber, Model model) {
        log.info("Kargo takip isteği alındı: {}", trackingNumber);
        model.addAttribute("submittedTrackingNumber", trackingNumber); // Input'u tekrar doldurmak için

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
            model.addAttribute("errorMessage", e.getMessage()); // Servisten gelen hata mesajını kullan
            // model.addAttribute("trackingInfo", null); // Zaten null olacak
        } catch (Exception e) {
            log.error("Kargo takip sırasında beklenmedik hata oluştu (Takip No: {}): {}", trackingNumber, e.getMessage(), e);
            model.addAttribute("errorMessage", "Kargo bilgileri alınırken bir hata oluştu. Lütfen tekrar deneyin.");
            // model.addAttribute("trackingInfo", null);
        }

        return "track"; // Aynı sayfayı modeldeki verilerle tekrar döndürür
    }
}