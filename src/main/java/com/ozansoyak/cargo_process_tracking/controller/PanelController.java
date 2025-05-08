package com.ozansoyak.cargo_process_tracking.controller;

import com.ozansoyak.cargo_process_tracking.dto.*;
import com.ozansoyak.cargo_process_tracking.model.enums.CargoStatus;
import com.ozansoyak.cargo_process_tracking.service.CargoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/panel")
@RequiredArgsConstructor
@Slf4j
public class PanelController {

    private final CargoService cargoService;

    @GetMapping
    public String showPanel(Model model, Authentication authentication) { /* ... Önceki kod ... */
        addUserAuthInfoToModel(model, authentication);
        model.addAttribute("activePage", "panel");
        try {
            PanelDataDto panelData = cargoService.getPanelData();
            model.addAttribute("panelData", panelData);
        } catch (Exception e) {
            log.error("Panel verileri alınırken hata oluştu: {}", e.getMessage(), e);
            model.addAttribute("panelError", "Panel verileri yüklenirken bir sorun oluştu.");
            model.addAttribute("panelData", PanelDataDto.builder().recentActivities(List.of()).build());
        }
        return "panel";
    }

    @GetMapping("/yeni-kargo")
    public String showNewCargoForm(Model model, Authentication authentication) { /* ... Önceki kod ... */
        addUserAuthInfoToModel(model, authentication);
        model.addAttribute("activePage", "yeniKargo");
        if (!model.containsAttribute("createCargoRequest")) {
            model.addAttribute("createCargoRequest", new CreateCargoRequest());
        }
        return "panel-yeni-kargo";
    }

    @PostMapping("/yeni-kargo")
    public String processNewCargoForm(@Valid @ModelAttribute("createCargoRequest") CreateCargoRequest request,
                                      BindingResult bindingResult,
                                      Model model,
                                      Authentication authentication) { /* ... Önceki kod ... */
        addUserAuthInfoToModel(model, authentication);
        model.addAttribute("activePage", "yeniKargo");
        if (bindingResult.hasErrors()) {
            log.warn("Yeni kargo formu validation hataları içeriyor.");
            return "panel-yeni-kargo";
        }
        try {
            CargoResponse response = cargoService.createCargoAndStartProcess(request);
            log.info("Yeni kargo başarıyla oluşturuldu. Takip No: {}", response.getTrackingNumber());
            model.addAttribute("showSuccessModal", true);
            model.addAttribute("successMessage", "Kargo başarıyla kaydedildi!");
            model.addAttribute("trackingNumber", response.getTrackingNumber());
            model.addAttribute("createCargoRequest", new CreateCargoRequest());
            return "panel-yeni-kargo";
        } catch (Exception e) {
            log.error("Yeni kargo kaydedilirken hata oluştu: {}", e.getMessage(), e);
            model.addAttribute("formError", "Kargo kaydedilirken beklenmedik bir hata oluştu: " + e.getMessage());
            return "panel-yeni-kargo";
        }
    }

    // --- Kargo Sorgulama Sayfası (Pageable ve Kriterler) ---
    @GetMapping("/sorgula")
    public String showCargoQueryPage(@ModelAttribute("searchCriteria") CargoSearchCriteria criteria,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "10") int size,
                                     Model model, Authentication authentication) {

        addUserAuthInfoToModel(model, authentication);
        model.addAttribute("activePage", "sorgula");

        // Sayfalama ve Sıralama (ID'ye göre tersten)
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));

        // Servisten sayfalanmış sonuçları al
        Page<CargoSearchResultDto> cargoPage = cargoService.searchCargos(criteria, pageable);

        model.addAttribute("kargoPage", cargoPage);
        model.addAttribute("cargoStatuses", CargoStatus.values()); // Select için enumlar
        // Arama kriterleri zaten @ModelAttribute ile modelde

        return "panel-sorgula";
    }
    // -------------------------------------------------------

    @GetMapping("/kullanici-yonetimi")
    public String showUserManagementPage(Model model, Authentication authentication) { /* ... Önceki kod ... */
        addUserAuthInfoToModel(model, authentication);
        model.addAttribute("activePage", "kullaniciYonetimi");
        return "panel-kullanici-yonetimi";
    }

    // Yardımcı metot
    private void addUserAuthInfoToModel(Model model, Authentication authentication) { /* ... Önceki kod ... */
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