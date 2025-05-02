package com.ozansoyak.cargo_process_tracking.controller;

import com.ozansoyak.cargo_process_tracking.dto.PanelDataDto; // Yeni DTO importu
import com.ozansoyak.cargo_process_tracking.service.CargoService; // Service importu
import lombok.RequiredArgsConstructor; // Constructor injection için
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

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

    @GetMapping("/yeni-kargo")
    public String showNewCargoForm(Model model, Authentication authentication) { // Authentication ekleyebiliriz
        addUserAuthInfoToModel(model, authentication); // Kullanıcı bilgilerini ekle
        model.addAttribute("activePage", "yeniKargo");
        return "panel-yeni-kargo";
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