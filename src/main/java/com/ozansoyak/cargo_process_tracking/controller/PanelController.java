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

import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/panel")
@RequiredArgsConstructor
@Slf4j
public class PanelController {

    private final CargoService cargoService;

    private static final Map<CargoStatus, String> ORDERED_STATUS_DISPLAY_NAMES;
    static {
        ORDERED_STATUS_DISPLAY_NAMES = new LinkedHashMap<>();
        Arrays.stream(CargoStatus.values()).forEach(status -> {
            String displayName = switch (status) {
                case PENDING -> "Beklemede";
                case RECEIVED -> "Kargo Alındı";
                case LOADED_ON_VEHICLE_1 -> "İlk Araca Yüklendi";
                case AT_TRANSFER_CENTER -> "Transfer Merkezinde";
                case LOADED_ON_VEHICLE_2 -> "Son Araca Yüklendi";
                case AT_DISTRIBUTION_HUB -> "Dağıtım Bölgesinde";
                case OUT_FOR_DELIVERY -> "Dağıtımda";
                case DELIVERED -> "Teslim Edildi";
                case CANCELLED -> "İptal Edildi";
                default -> status.name();
            };
            ORDERED_STATUS_DISPLAY_NAMES.put(status, displayName);
        });
    }

    // Bu metot PanelController içinde kalmalı veya bir utility sınıfına taşınmalı.
    // DeploymentController'da kopyası vardı, eğer ortak bir utility'ye taşınırsa oradan çağrılabilir.
    public void addCommonPanelAttributes(Model model, Authentication authentication, String activeMenuItem) {
        String username = "Kullanıcı";
        String roles = "Bilinmiyor";
        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof UserDetails) {
                username = ((UserDetails) principal).getUsername();
                roles = ((UserDetails) principal).getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .map(role -> role.startsWith("ROLE_") ? role.substring(5) : role)
                        .collect(Collectors.joining(", "));
            } else {
                username = authentication.getName();
            }
        }
        model.addAttribute("username", username);
        model.addAttribute("userRoles", roles);
        model.addAttribute("activeMenuItem", activeMenuItem);
    }

    @GetMapping
    public String showPanel(Model model, Authentication authentication) {
        addCommonPanelAttributes(model, authentication, "anaPanel");
        try {
            PanelDataDto panelData = cargoService.getPanelData();
            model.addAttribute("panelData", panelData);
        } catch (Exception e) {
            log.error("Panel verileri alınırken hata oluştu: {}", e.getMessage(), e);
            model.addAttribute("panelError", "Panel verileri yüklenirken bir sorun oluştu.");
            model.addAttribute("panelData", PanelDataDto.builder()
                    .beklemedeAlinanCount(0).tasiniyorTransferdeCount(0).dagitimdaCount(0)
                    .teslimEdilenCount(0).iptalEdilenCount(0).recentActivities(List.of()).build());
        }
        return "panel";
    }

    @GetMapping("/yeni-kargo")
    public String showNewCargoForm(Model model, Authentication authentication) {
        addCommonPanelAttributes(model, authentication, "yeniKargo");
        if (!model.containsAttribute("createCargoRequest")) {
            model.addAttribute("createCargoRequest", new CreateCargoRequest());
        }
        return "panel-yeni-kargo";
    }

    @PostMapping("/yeni-kargo")
    public String processNewCargoForm(@Valid @ModelAttribute("createCargoRequest") CreateCargoRequest request,
                                      BindingResult bindingResult, Model model,
                                      Authentication authentication, RedirectAttributes redirectAttributes) {
        addCommonPanelAttributes(model, authentication, "yeniKargo");
        if (bindingResult.hasErrors()) {
            log.warn("Yeni kargo formu validation hataları içeriyor: {}", bindingResult.getAllErrors());
            return "panel-yeni-kargo";
        }
        try {
            CargoResponse response = cargoService.createCargoAndStartProcess(request);
            log.info("Yeni kargo başarıyla oluşturuldu. Takip No: {}", response.getTrackingNumber());
            redirectAttributes.addFlashAttribute("showSuccessModal", true);
            redirectAttributes.addFlashAttribute("successMessage", "Kargo başarıyla kaydedildi!");
            redirectAttributes.addFlashAttribute("trackingNumber", response.getTrackingNumber());
            return "redirect:/panel/yeni-kargo";
        } catch (Exception e) {
            log.error("Yeni kargo kaydedilirken hata oluştu: {}", e.getMessage(), e);
            model.addAttribute("formError", "Kargo kaydedilirken beklenmedik bir hata oluştu: " + e.getMessage());
            return "panel-yeni-kargo";
        }
    }

    @GetMapping("/sorgula")
    public String showCargoQueryPage(@ModelAttribute("searchCriteria") CargoSearchCriteria criteria,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "10") int size,
                                     Model model, Authentication authentication) {
        addCommonPanelAttributes(model, authentication, "kargoSorgula");
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "lastUpdatedAt"));
        Page<CargoSearchResultDto> cargoPage = cargoService.searchCargos(criteria, pageable);
        model.addAttribute("kargoPage", cargoPage);
        model.addAttribute("statusDisplayMap", ORDERED_STATUS_DISPLAY_NAMES);
        return "panel-sorgula";
    }

    @GetMapping("/kullanici-yonetimi")
    public String showUserManagementPage(Model model, Authentication authentication) {
        // DİKKAT: Burada 'addCommonPanelAttributes' kullanılmalı ve 'activeMenuItem' doğru set edilmeli.
        addCommonPanelAttributes(model, authentication, "kullaniciYonetimi");
        // TODO: Kullanıcı yönetimi için gerekli veriler model'e eklenebilir.
        return "panel-kullanici-yonetimi"; // Bu HTML dosyasının var olduğundan emin olun.
    }

    @GetMapping("/aktif-gorevler")
    public String showActiveTasksPage(Model model, Authentication authentication) {
        // DİKKAT: Burada 'addCommonPanelAttributes' kullanılmalı ve 'activeMenuItem' doğru set edilmeli.
        addCommonPanelAttributes(model, authentication, "aktifGorevler");

        String username = null;
        List<String> userGroups = new ArrayList<>();

        if (authentication != null && authentication.getPrincipal() instanceof UserDetails userDetails) {
            username = userDetails.getUsername(); // Camunda'daki kullanıcı ID'si ile eşleşmeli
            userGroups = userDetails.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    // ÖNEMLİ: Buradaki rol-grup eşleştirmesinin doğru olduğundan emin olun.
                    // Camunda'daki grup adlarınızla Spring Security rolleriniz arasındaki eşleşmeyi burada yapın.
                    // Örnek: Camunda'da grup "satis_ekibi", Spring rolü "ROLE_SATIS_EKIBI" ise,
                    // .map(role -> role.equals("ROLE_SATIS_EKIBI") ? "satis_ekibi" : (role.startsWith("ROLE_") ? role.substring(5).toLowerCase() : role.toLowerCase()))
                    .map(role -> {
                        // Bu map'lemeyi kendi Camunda grup isimlerinize göre güncelleyin.
                        if ("ROLE_ADMIN".equals(role)) return "camunda-admin"; // Camunda default admin grubu
                        if ("ROLE_KARGO_CALISANLARI".equals(role)) return "kargo-calisanlari";
                        if ("ROLE_MUHASEBE_EKIBI".equals(role)) return "muhasebe-ekibi";
                        // Diğer roller için genel bir dönüşüm veya özel map'lemeler
                        return role.startsWith("ROLE_") ? role.substring(5).toLowerCase() : role.toLowerCase();
                    })
                    .collect(Collectors.toList());
            log.info("Aktif görevler sorgulanıyor. Kullanıcı: '{}', Gruplar: {}", username, userGroups);
        } else {
            log.warn("Aktif görevler için kimlik doğrulanmış kullanıcı bulunamadı. Görev listesi boş olacak.");
            model.addAttribute("activeTasks", List.of());
            model.addAttribute("errorMessage", "Aktif görevleri listelemek için giriş yapmalısınız veya yetkiniz bulunmamaktadır.");
            return "panel-aktif-gorevler";
        }

        List<ActiveTaskDto> activeTasks = cargoService.getActiveUserTasks(username, userGroups);
        model.addAttribute("activeTasks", activeTasks);

        if (activeTasks.isEmpty()) {
            log.info("Kullanıcı '{}' veya grupları {} için gösterilecek aktif Camunda görevi bulunamadı.", username, userGroups);
        } else {
            log.info("{} adet aktif Camunda görevi bulundu ve modele eklendi.", activeTasks.size());
        }
        return "panel-aktif-gorevler";
    }
}