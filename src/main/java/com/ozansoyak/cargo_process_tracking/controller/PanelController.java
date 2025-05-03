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

    // --- YENİ: Yeni Kargo Formu Gösterme ---
    @GetMapping("/yeni-kargo")
    public String showNewCargoForm(Model model, Authentication authentication) {
        addUserAuthInfoToModel(model, authentication);
        model.addAttribute("activePage", "yeniKargo");
        // Forma bağlamak için boş bir DTO nesnesi ekleyelim
        if (!model.containsAttribute("createCargoRequest")) { // Redirect'ten gelmiyorsa yeni oluştur
            model.addAttribute("createCargoRequest", new CreateCargoRequest());
        }
        return "panel-yeni-kargo";
    }

    // --- YENİ: Yeni Kargo Formunu İşleme ---
    @PostMapping("/yeni-kargo")
    public String processNewCargoForm(@Valid @ModelAttribute("createCargoRequest") CreateCargoRequest request,
                                      BindingResult bindingResult, // Validation sonuçları
                                      RedirectAttributes redirectAttributes, // Yönlendirme sonrası mesaj için
                                      Model model, Authentication authentication) {

        addUserAuthInfoToModel(model, authentication); // Layout için gerekli
        model.addAttribute("activePage", "yeniKargo"); // Aktif sayfayı belirt

        // Validation hataları varsa formu tekrar göster
        if (bindingResult.hasErrors()) {
            log.warn("Yeni kargo formu validation hataları içeriyor.");
            // Hatalar zaten bindingResult ile forma otomatik gönderilir.
            // Sadece sayfayı tekrar render etmemiz yeterli.
            // Form tekrar gösterilirken modeldeki createCargoRequest kullanılır.
            return "panel-yeni-kargo";
        }

        // Validation başarılıysa kargoyu oluştur
        try {
            CargoResponse response = cargoService.createCargoAndStartProcess(request);
            log.info("Yeni kargo başarıyla oluşturuldu. Takip No: {}", response.getTrackingNumber());
            // Başarı mesajını ve takip numarasını sonraki sayfaya flash attribute olarak gönder
            redirectAttributes.addFlashAttribute("successMessage", "Kargo başarıyla kaydedildi!");
            redirectAttributes.addFlashAttribute("trackingNumber", response.getTrackingNumber());
            // Başarılı kayıt sonrası panele yönlendir (veya başka bir sayfaya)
            return "redirect:/panel"; // Veya "/panel/yeni-kargo?success=true" gibi yapılıp mesaj orada gösterilebilir
        } catch (Exception e) {
            log.error("Yeni kargo kaydedilirken hata oluştu: {}", e.getMessage(), e);
            // Genel bir hata mesajı ekle ve formu tekrar göster
            model.addAttribute("formError", "Kargo kaydedilirken beklenmedik bir hata oluştu: " + e.getMessage());
            // Gönderilen veriler modelde kalır, form tekrar doldurulur.
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