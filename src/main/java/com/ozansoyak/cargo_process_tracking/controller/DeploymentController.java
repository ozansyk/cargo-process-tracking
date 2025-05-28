package com.ozansoyak.cargo_process_tracking.controller;

import com.ozansoyak.cargo_process_tracking.dto.ProcessDefinitionDto;
import com.ozansoyak.cargo_process_tracking.dto.StartProcessInstanceRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils; // StringUtils importu eklendi
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.InputStream;
import java.util.HashMap; // HashMap importu eklendi
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// Eğer CAMUNDA_PROCESS_DEFINITION_KEY CargoServiceImpl'de public static final ise:
// import static com.ozansoyak.cargo_process_tracking.service.impl.CargoServiceImpl.CAMUNDA_PROCESS_DEFINITION_KEY;
// Aksi halde, bu controller'da tanımlamanız gerekebilir veya başka bir yerden almalısınız.
// Şimdilik bir String sabiti olarak burada tanımlayalım (projenize göre güncelleyin):
// private static final String CAMUNDA_PROCESS_DEFINITION_KEY = "cargoTrackingProcessV3"; // Örnek değer

@Controller
@RequestMapping("/panel/deployments") // Base path güncellendi
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')") // Controller seviyesinde yetkilendirme
public class DeploymentController {

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;

    // PanelController'daki addUserAuthInfoToModel metodunun kopyası
    private void addCommonPanelAttributes(Model model, Authentication authentication, String activeMenuItem) {
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
                username = authentication.getName(); // Fallback
            }
        }
        model.addAttribute("username", username);
        model.addAttribute("userRoles", roles);
        model.addAttribute("activeMenuItem", activeMenuItem); // 'activePage' yerine 'activeMenuItem'
    }


    // --- BPMN Yükleme Formunu Göster ---
    @GetMapping("/new-bpmn") // Path güncellendi
    public String showDeploymentForm(Model model, Authentication authentication) {
        addCommonPanelAttributes(model, authentication, "deployBPMN"); // activeMenuItem güncellendi
        // Eğer HTML dosyanız templates/panel/ altında ise return "panel/deploy-form";
        return "deploy-form"; // resources/templates/deploy-form.html
    }

    // --- BPMN Dosyasını Yükle ve Deploy Et ---
    @PostMapping("/upload") // Bu path formdaki action ile eşleşmeli
    public String handleFileUpload(@RequestParam("file") MultipartFile file,
                                   @RequestParam(name = "deploymentName", required = false) String deploymentNameFromRequest, // İsim değişikliği
                                   RedirectAttributes redirectAttributes,
                                   Model model, // Hata durumunda sayfaya geri dönmek için
                                   Authentication authentication) {

        // Hata veya başarı sonrası sayfa yenilendiğinde model attribute'ları lazım olabilir
        addCommonPanelAttributes(model, authentication, "deployBPMN");

        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lütfen yüklenecek bir BPMN dosyası seçin.");
            return "redirect:/panel/deployments/new-bpmn"; // Güncellenmiş redirect path
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || (!originalFilename.toLowerCase().endsWith(".bpmn") && !originalFilename.toLowerCase().endsWith(".bpmn20.xml"))) {
            redirectAttributes.addFlashAttribute("errorMessage", "Geçersiz dosya türü. Lütfen .bpmn veya .bpmn20.xml uzantılı bir dosya yükleyin.");
            return "redirect:/panel/deployments/new-bpmn";
        }

        // deploymentName boşsa dosya adını kullan, doluysa gelen değeri kullan.
        final String deploymentName = StringUtils.hasText(deploymentNameFromRequest) ? deploymentNameFromRequest.trim() : originalFilename;

        try (InputStream inputStream = file.getInputStream()) {
            Deployment deployment = repositoryService.createDeployment()
                    .addInputStream(originalFilename, inputStream) // Dosya adını kaynak olarak kullan
                    .name(deploymentName) // Kullanıcının girdiği veya dosya adı
                    .tenantId(null) // Çoklu kiracılık yoksa null
                    .deploy();

            log.info("Yeni BPMN deploy edildi. Deployment ID: {}, Adı: {}", deployment.getId(), deployment.getName());
            redirectAttributes.addFlashAttribute("successMessage",
                    "BPMN dosyası ('" + deployment.getName() + "') başarıyla deploy edildi! Deployment ID: " + deployment.getId());
            return "redirect:/panel/deployments/new-bpmn"; // Güncellenmiş redirect path

        } catch (Exception e) {
            log.error("BPMN dosyası deploy edilirken hata oluştu: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Dosya deploy edilirken bir hata oluştu: " + e.getMessage());
            return "redirect:/panel/deployments/new-bpmn";
        }
    }

    // --- Süreç Başlatma Formunu Göster ---
    @GetMapping("/start-instance")
    public String showStartInstanceForm(Model model, Authentication authentication) {
        addCommonPanelAttributes(model, authentication, "startInstance"); // activeMenuItem güncellendi

        List<ProcessDefinition> processDefinitions = repositoryService.createProcessDefinitionQuery()
                .active().latestVersion().orderByProcessDefinitionKey().asc().list();
        List<ProcessDefinitionDto> definitionDtos = processDefinitions.stream()
                .map(pd -> new ProcessDefinitionDto(pd.getId(), pd.getKey(), pd.getName(), pd.getVersion(), pd.getDeploymentId()))
                .collect(Collectors.toList());
        model.addAttribute("processDefinitions", definitionDtos);
        if (!model.containsAttribute("startRequest")) {
            model.addAttribute("startRequest", new StartProcessInstanceRequest());
        }
        // Eğer HTML dosyanız templates/panel/ altında ise return "panel/start-instance-form";
        return "start-instance-form"; // resources/templates/start-instance-form.html
    }

    // --- Seçilen Süreçten Yeni Bir Örnek Başlat ---
    @PostMapping("/start-instance") // Bu path formdaki action ile eşleşmeli
    public String startProcessInstance(@Valid @ModelAttribute("startRequest") StartProcessInstanceRequest request,
                                       BindingResult bindingResult,
                                       @RequestParam(name = "variablesString", required = false) String variablesString, // Formdaki name ile eşleşmeli
                                       RedirectAttributes redirectAttributes,
                                       Model model, Authentication authentication) {

        addCommonPanelAttributes(model, authentication, "startInstance");

        if (bindingResult.hasErrors()) {
            // Hata durumunda süreç listesini tekrar yükle
            List<ProcessDefinition> processDefinitions = repositoryService.createProcessDefinitionQuery().active().latestVersion().orderByProcessDefinitionKey().asc().list();
            List<ProcessDefinitionDto> definitionDtos = processDefinitions.stream()
                    .map(pd -> new ProcessDefinitionDto(pd.getId(), pd.getKey(), pd.getName(), pd.getVersion(), pd.getDeploymentId()))
                    .collect(Collectors.toList());
            model.addAttribute("processDefinitions", definitionDtos);
            // startRequest zaten modelde BindingResult ile var.
            return "start-instance-form";
        }

        try {
            String processDefinitionKey = request.getProcessDefinitionKey();
            // Business Key boş string ise null yapalım
            String businessKey = StringUtils.hasText(request.getBusinessKey()) ? request.getBusinessKey().trim() : null;
            Map<String, Object> variables = new HashMap<>(); // Başlangıçta boş Map

            if (request.getVariables() != null) { // DTO'dan gelen map'i al (eğer varsa)
                variables.putAll(request.getVariables());
            }

            // String'den değişkenleri parse et ve mevcutlara ekle/üzerine yaz
            // Formdaki textarea'dan gelen `variablesString` (name="variablesString")
            if (StringUtils.hasText(variablesString)) {
                // Kullanıcı değişkenleri satır sonlarıyla ayırdıysa, onları noktalı virgüle çevir
                String parsableVariablesString = variablesString.replace("\r\n", ";").replace("\n", ";");
                try {
                    String[] pairs = parsableVariablesString.split(";");
                    for (String pair : pairs) {
                        if (StringUtils.hasText(pair)) { // Boş satırları atla
                            String[] keyValue = pair.split("=", 2);
                            if (keyValue.length == 2) {
                                String key = keyValue[0].trim();
                                String valueStr = keyValue[1].trim();
                                Object value = valueStr; // Varsayılan tip String
                                if ("true".equalsIgnoreCase(valueStr) || "false".equalsIgnoreCase(valueStr)) {
                                    value = Boolean.parseBoolean(valueStr);
                                } else {
                                    try { value = Long.parseLong(valueStr); }
                                    catch (NumberFormatException e) {
                                        try { value = Double.parseDouble(valueStr); }
                                        catch (NumberFormatException e2) { /* String olarak kalır */ }
                                    }
                                }
                                if (StringUtils.hasText(key)) {
                                    variables.put(key, value);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Başlangıç değişkenleri (variablesString) parse edilirken hata: {}", e.getMessage());
                    redirectAttributes.addFlashAttribute("warningMessage", "Değişkenler parse edilirken sorun oluştu, bazıları eklenmemiş olabilir.");
                }
            }
            // request.setVariables(variables); // DTO'yu güncellemek gerekiyorsa, ama zaten 'variables' map'ini kullanıyoruz.

            ProcessInstance processInstance;
            if (businessKey != null) { // businessKey null değilse kullan
                processInstance = runtimeService.startProcessInstanceByKey(processDefinitionKey, businessKey, variables);
            } else {
                processInstance = runtimeService.startProcessInstanceByKey(processDefinitionKey, variables);
            }

            log.info("Yeni süreç örneği başlatıldı. Key: {}, Instance ID: {}, Business Key: {}",
                    processDefinitionKey, processInstance.getId(), processInstance.getBusinessKey());

            // Süreç adını almak için
            ProcessDefinition pd = repositoryService.createProcessDefinitionQuery().processDefinitionKey(processDefinitionKey).latestVersion().singleResult();
            String processName = (pd != null && StringUtils.hasText(pd.getName())) ? pd.getName() : processDefinitionKey;

            redirectAttributes.addFlashAttribute("successMessage",
                    "'" + processName + "' süreci başarıyla başlatıldı! Süreç ID: " + processInstance.getId() +
                            (processInstance.getBusinessKey() != null ? ", İş Anahtarı: " + processInstance.getBusinessKey() : ""));

            // Eğer bizim kargo süreciyse (CAMUNDA_PROCESS_DEFINITION_KEY ile kontrol edilebilir)
            // ve takip no varsa (businessKey), onu da ekleyebiliriz.
            // String kargoProcessKey = "cargoTrackingProcessV3"; // Bu sabiti doğru yerden almalısınız
            // if (kargoProcessKey.equals(processDefinitionKey) && StringUtils.hasText(businessKey)) {
            //    redirectAttributes.addFlashAttribute("trackingNumberInfo", "Kargo Takip No: " + businessKey);
            // }

            return "redirect:/panel/deployments/start-instance"; // Güncellenmiş redirect path

        } catch (Exception e) {
            log.error("Süreç örneği başlatılırken hata: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Süreç başlatılırken bir hata oluştu: " + e.getMessage());
            // Hata durumunda formu ve listeyi tekrar hazırla
            List<ProcessDefinition> processDefinitions = repositoryService.createProcessDefinitionQuery().active().latestVersion().orderByProcessDefinitionKey().asc().list();
            List<ProcessDefinitionDto> definitionDtos = processDefinitions.stream()
                    .map(pDef -> new ProcessDefinitionDto(pDef.getId(), pDef.getKey(), pDef.getName(), pDef.getVersion(), pDef.getDeploymentId()))
                    .collect(Collectors.toList());
            model.addAttribute("processDefinitions", definitionDtos);
            model.addAttribute("startRequest", request); // Formdaki veriler kaybolmasın
            return "start-instance-form";
        }
    }
}