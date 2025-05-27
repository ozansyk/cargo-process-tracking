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

    // Sıralı ve Türkçe Durum İsimleri için Map
    // Bu map, sorgulama sayfasındaki dropdown'ı doldurmak için kullanılacak.
    private static final Map<CargoStatus, String> ORDERED_STATUS_DISPLAY_NAMES;
    static {
        ORDERED_STATUS_DISPLAY_NAMES = new LinkedHashMap<>();
        // Enum sabitlerinin tanımlandığı sırayla ekleyelim
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
                default -> status.name(); // Eşleşme yoksa enum adını kullan
            };
            ORDERED_STATUS_DISPLAY_NAMES.put(status, displayName);
        });
    }

    // Kullanıcı bilgilerini ve aktif menü öğesini model'e ekleyen yardımcı metot
    private void addCommonPanelAttributes(Model model, Authentication authentication, String activeMenuItem) {
        String username = "Kullanıcı";
        String roles = "Bilinmiyor";
        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof UserDetails) {
                username = ((UserDetails) principal).getUsername();
                roles = ((UserDetails) principal).getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .map(role -> role.startsWith("ROLE_") ? role.substring(5) : role) // "ROLE_" prefix'ini kaldır
                        .collect(Collectors.joining(", "));
            } else {
                // Eğer principal UserDetails değilse, getName() genellikle kullanıcı adını verir
                username = authentication.getName();
            }
        }
        model.addAttribute("username", username);
        model.addAttribute("userRoles", roles);
        model.addAttribute("activeMenuItem", activeMenuItem); // Layout için aktif menü öğesi
    }

    @GetMapping
    public String showPanel(Model model, Authentication authentication) {
        addCommonPanelAttributes(model, authentication, "anaPanel"); // Aktif menü: anaPanel
        try {
            PanelDataDto panelData = cargoService.getPanelData();
            model.addAttribute("panelData", panelData);
        } catch (Exception e) {
            log.error("Panel verileri alınırken hata oluştu: {}", e.getMessage(), e);
            model.addAttribute("panelError", "Panel verileri yüklenirken bir sorun oluştu.");
            // Hata durumunda bile boş bir panelData nesnesi gönderelim ki Thymeleaf'te null check azalsın
            model.addAttribute("panelData", PanelDataDto.builder()
                    .beklemedeAlinanCount(0)
                    .tasiniyorTransferdeCount(0)
                    .dagitimdaCount(0)
                    .teslimEdilenCount(0)
                    .iptalEdilenCount(0)
                    .recentActivities(List.of())
                    .build());
        }
        return "panel"; // templates/panel.html
    }

    @GetMapping("/yeni-kargo")
    public String showNewCargoForm(Model model, Authentication authentication) {
        addCommonPanelAttributes(model, authentication, "yeniKargo"); // Aktif menü: yeniKargo
        // Eğer redirect sonrası model'de zaten bir "createCargoRequest" varsa (PRG sonrası), onu kullanır.
        // Yoksa (ilk GET isteği), yeni bir tane oluşturur.
        if (!model.containsAttribute("createCargoRequest")) {
            model.addAttribute("createCargoRequest", new CreateCargoRequest());
        }
        return "panel-yeni-kargo"; // templates/panel-yeni-kargo.html
    }

    @PostMapping("/yeni-kargo")
    public String processNewCargoForm(@Valid @ModelAttribute("createCargoRequest") CreateCargoRequest request,
                                      BindingResult bindingResult,
                                      Model model, // Hata durumunda aynı sayfaya dönmek için Model
                                      Authentication authentication,
                                      RedirectAttributes redirectAttributes) { // Başarı durumunda redirect için
        // Hata durumunda sayfaya geri dönerken model'e tekrar eklenmeli
        addCommonPanelAttributes(model, authentication, "yeniKargo");

        if (bindingResult.hasErrors()) {
            log.warn("Yeni kargo formu validation hataları içeriyor: {}", bindingResult.getAllErrors());
            // createCargoRequest zaten model'de @ModelAttribute sayesinde
            return "panel-yeni-kargo"; // Validasyon hatalarıyla formu tekrar göster
        }
        try {
            CargoResponse response = cargoService.createCargoAndStartProcess(request);
            log.info("Yeni kargo başarıyla oluşturuldu. Takip No: {}", response.getTrackingNumber());

            // PRG pattern: Başarı mesajını ve veriyi RedirectAttributes ile taşı
            redirectAttributes.addFlashAttribute("showSuccessModal", true);
            redirectAttributes.addFlashAttribute("successMessage", "Kargo başarıyla kaydedildi!");
            redirectAttributes.addFlashAttribute("trackingNumber", response.getTrackingNumber());
            // Başarılı işlem sonrası formu temizlemek için yeni bir DTO gönderilebilir veya GET handler'da yapılabilir.
            // redirectAttributes.addFlashAttribute("createCargoRequest", new CreateCargoRequest()); // Bu, GET /yeni-kargo'da ele alınacak.

            return "redirect:/panel/yeni-kargo"; // Başarılı işlem sonrası GET isteğiyle aynı sayfaya yönlendir
        } catch (Exception e) {
            log.error("Yeni kargo kaydedilirken hata oluştu: {}", e.getMessage(), e);
            model.addAttribute("formError", "Kargo kaydedilirken beklenmedik bir hata oluştu: " + e.getMessage());
            // createCargoRequest zaten model'de
            return "panel-yeni-kargo"; // Hata mesajıyla formu tekrar göster
        }
    }

    @GetMapping("/sorgula")
    public String showCargoQueryPage(@ModelAttribute("searchCriteria") CargoSearchCriteria criteria, // Formdan gelen veya default
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "10") int size,
                                     Model model, Authentication authentication) {
        addCommonPanelAttributes(model, authentication, "kargoSorgula"); // Aktif menü: kargoSorgula

        // Eğer searchCriteria'da null alanlar varsa ve bu sorun yaratıyorsa, burada default değerler atanabilir.
        // Örneğin: if (criteria.getTrackingNo() == null) criteria.setTrackingNo("");

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "lastUpdatedAt")); // Veya "id"
        Page<CargoSearchResultDto> cargoPage = cargoService.searchCargos(criteria, pageable);

        model.addAttribute("kargoPage", cargoPage);
        model.addAttribute("statusDisplayMap", ORDERED_STATUS_DISPLAY_NAMES); // Dropdown için durum listesi
        // searchCriteria zaten model'e @ModelAttribute ile eklenmiş durumda.
        // Eğer ilk GET isteğinde boş bir criteria nesnesi göndermek isterseniz:
        // if (!model.containsAttribute("searchCriteria")) {
        //     model.addAttribute("searchCriteria", new CargoSearchCriteria());
        // }
        // Ama @ModelAttribute bunu genellikle halleder.

        return "panel-sorgula"; // templates/panel-sorgula.html
    }

    @GetMapping("/kullanici-yonetimi")
    public String showUserManagementPage(Model model, Authentication authentication) { /* ... Önceki kod ... */
        addUserAuthInfoToModel(model, authentication);
        model.addAttribute("activePage", "kullaniciYonetimi");
        return "panel-kullanici-yonetimi";
    }

    // --- YENİ: Aktif Görevler Sayfası ---
    @GetMapping("/aktif-gorevler")
    public String showActiveTasksPage(Model model, Authentication authentication) {
        addUserAuthInfoToModel(model, authentication); // Navbar için kullanıcı bilgisi
        model.addAttribute("activePage", "aktifGorevler"); // Sidebar için

        String username = null;
        List<String> userGroups = new ArrayList<>();

        if (authentication != null && authentication.getPrincipal() instanceof UserDetails) {
            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            username = userDetails.getUsername();
            userGroups = userDetails.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    // Spring Security genellikle "ROLE_" prefix'i ekler,
                    // Camunda'da grup adı direkt kullanılır. Bu kısmı
                    // Camunda'daki grup isimlerinizle eşleşecek şekilde ayarlayın.
                    // Örneğin, eğer Camunda'da grup adı "kargo-calisanlari" ise
                    // ve Spring Security rolü "ROLE_KARGO_CALISANLARI" ise,
                    // burada bir dönüşüm yapmanız gerekebilir veya Camunda'da
                    // grupları "ROLE_" prefix'i olmadan oluşturabilirsiniz.
                    // Şimdilik, Camunda'daki grup adlarının Spring Security rollerinden
                    // (prefixsiz) geldiğini varsayalım.
                    .map(role -> role.startsWith("ROLE_") ? role.substring(5) : role)
                    .collect(Collectors.toList());
        }

        List<ActiveTaskDto> activeTasks = cargoService.getActiveUserTasks(username, userGroups);
        model.addAttribute("activeTasks", activeTasks);

        return "panel-aktif-gorevler"; // resources/templates/panel-aktif-gorevler.html
    }

    // Yardımcı metot
    public void addUserAuthInfoToModel(Model model, Authentication authentication) { /* ... Önceki kod ... */
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