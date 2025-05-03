package com.ozansoyak.cargo_process_tracking.controller;

import com.ozansoyak.cargo_process_tracking.dto.CargoResponse;
import com.ozansoyak.cargo_process_tracking.dto.CreateCargoRequest;
import com.ozansoyak.cargo_process_tracking.dto.PanelDataDto; // Yeni DTO importu
import com.ozansoyak.cargo_process_tracking.service.CargoService; // Service importu
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor; // Constructor injection için
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/panel")
@RequiredArgsConstructor // Lombok ile constructor injection
@Slf4j
public class PanelController {

    private final CargoService cargoService; // Servisi inject et

    @GetMapping // "/panel" için varsayılan metot
    public String showPanel(Model model, Authentication authentication) {
        String username = "Kullanıcı";
        String roles = "";

        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof UserDetails) {
                username = ((UserDetails) principal).getUsername();
                roles = ((UserDetails) principal).getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .map(role -> role.startsWith("ROLE_") ? role.substring(5) : role)
                        .collect(Collectors.joining(", "));
            } else {
                username = principal.toString();
            }
        }
        model.addAttribute("username", username);
        model.addAttribute("userRoles", roles);
        model.addAttribute("activePage", "panel"); // Aktif sayfa

        // --- YENİ: Panel verilerini servisten alıp modele ekle ---
        try {
            PanelDataDto panelData = cargoService.getPanelData();
            model.addAttribute("panelData", panelData);
        } catch (Exception e) {
            // Hata durumunda paneli yine de göster ama logla ve belki bir hata mesajı ekle
            log.error("Panel verileri alınırken hata oluştu: {}", e.getMessage(), e);
            model.addAttribute("panelError", "Panel verileri yüklenirken bir sorun oluştu.");
            // Boş bir DTO oluşturup gönderebiliriz ki Thymeleaf null hatası vermesin
            model.addAttribute("panelData", PanelDataDto.builder().recentActivities(List.of()).build());
        }
        // --------------------------------------------------------

        return "panel"; // resources/templates/panel.html
    }

    // --- Diğer Panel Sayfaları İçin Metodlar ---
    // (Bunlar önceki cevapta olduğu gibi kalabilir)

    // --- showNewCargoForm METODU GÜNCELLENDİ ---
    @GetMapping("/yeni-kargo")
    public String showNewCargoForm(Model model, Authentication authentication) {
        addUserAuthInfoToModel(model, authentication);
        model.addAttribute("activePage", "yeniKargo");

        // --- DÜZELTME: Forma bağlanacak nesneyi modele ekle ---
        // Eğer modelde zaten redirect'ten gelen bir attribute yoksa, yenisini ekle.
        // Bu, validation hatası sonrası geri dönüldüğünde girilen değerlerin korunmasını da sağlar.
        if (!model.containsAttribute("createCargoRequest")) {
            model.addAttribute("createCargoRequest", new CreateCargoRequest());
        }
        // -----------------------------------------------------

        return "panel-yeni-kargo"; // template adı doğru varsayılıyor
    }

    // --- processNewCargoForm METODU (Aynı kalır) ---
    @PostMapping("/yeni-kargo")
    public String processNewCargoForm(@Valid @ModelAttribute("createCargoRequest") CreateCargoRequest request,
                                      BindingResult bindingResult,
                                      RedirectAttributes redirectAttributes,
                                      Model model, Authentication authentication) {

        addUserAuthInfoToModel(model, authentication);
        model.addAttribute("activePage", "yeniKargo");

        if (bindingResult.hasErrors()) {
            log.warn("Yeni kargo formu validation hataları içeriyor.");
            // Model'e "createCargoRequest" ve "bindingResult" zaten otomatik eklenir,
            // bu yüzden formu tekrar göstermek yeterli.
            return "panel-yeni-kargo";
        }

        try {
            CargoResponse response = cargoService.createCargoAndStartProcess(request);
            log.info("Yeni kargo başarıyla oluşturuldu. Takip No: {}", response.getTrackingNumber());
            redirectAttributes.addFlashAttribute("successMessage", "Kargo başarıyla kaydedildi!");
            redirectAttributes.addFlashAttribute("trackingNumber", response.getTrackingNumber());
            return "redirect:/panel";
        } catch (Exception e) {
            log.error("Yeni kargo kaydedilirken hata oluştu: {}", e.getMessage(), e);
            model.addAttribute("formError", "Kargo kaydedilirken beklenmedik bir hata oluştu: " + e.getMessage());
            // Hata durumunda da formun bağlı olduğu nesne modelde kalmalı (ki girilenler kaybolmasın)
            // @ModelAttribute sayesinde bu zaten modelde mevcut.
            return "panel-yeni-kargo";
        }
    }

    @GetMapping("/sorgula")
    public String showCargoQueryPage(Model model, Authentication authentication) {
        addUserAuthInfoToModel(model, authentication);
        model.addAttribute("activePage", "sorgula");
        return "panel-sorgula";
    }

    @GetMapping("/kullanici-yonetimi")
    public String showUserManagementPage(Model model, Authentication authentication) {
        addUserAuthInfoToModel(model, authentication);
        model.addAttribute("activePage", "kullaniciYonetimi");
        return "panel-kullanici-yonetimi";
    }

    // Tekrarlanan kodu azaltmak için yardımcı metot
    private void addUserAuthInfoToModel(Model model, Authentication authentication) {
        String username = "Kullanıcı";
        String roles = "";
        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof UserDetails) {
                username = ((UserDetails) principal).getUsername();
                roles = ((UserDetails) principal).getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .map(role -> role.startsWith("ROLE_") ? role.substring(5) : role)
                        .collect(Collectors.joining(", "));
            } else {
                username = principal.toString();
            }
        }
        model.addAttribute("username", username);
        model.addAttribute("userRoles", roles);
    }

}