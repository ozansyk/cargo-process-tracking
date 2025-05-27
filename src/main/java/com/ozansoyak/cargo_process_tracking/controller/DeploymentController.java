package com.ozansoyak.cargo_process_tracking.controller;

import com.ozansoyak.cargo_process_tracking.dto.ProcessDefinitionDto;
import com.ozansoyak.cargo_process_tracking.dto.StartProcessInstanceRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.security.core.Authentication; // Kullanıcı bilgisi için
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import jakarta.validation.Valid; // @Valid için


import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.ozansoyak.cargo_process_tracking.service.impl.CargoServiceImpl.CAMUNDA_PROCESS_DEFINITION_KEY;

@Controller
@RequestMapping("/deployments") // Base path
@RequiredArgsConstructor
@Slf4j
public class DeploymentController {

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    // PanelController'daki addUserAuthInfoToModel metodunu buraya da taşıyabiliriz veya ortak bir utility yapabiliriz.
    // Şimdilik PanelController'daki varsayımıyla devam ediyorum. PanelController'ı da inject edebiliriz.
    private final PanelController panelControllerUtil; // Yardımcı metot için


    // --- BPMN Yükleme Formunu Göster ---
    @GetMapping("/new")
    public String showDeploymentForm(Model model, Authentication authentication) {
        panelControllerUtil.addUserAuthInfoToModel(model, authentication); // Navbar için kullanıcı bilgisi
        model.addAttribute("activePage", "deployBPMN"); // Sidebar için
        return "deploy-form"; // resources/templates/deploy-form.html
    }

    // --- BPMN Dosyasını Yükle ve Deploy Et ---
    @PostMapping
    public String handleFileUpload(@RequestParam("file") MultipartFile file,
                                   @RequestParam(defaultValue = "UploadedBPMN") String deploymentName,
                                   RedirectAttributes redirectAttributes,
                                   Model model, Authentication authentication) {
        panelControllerUtil.addUserAuthInfoToModel(model, authentication);
        model.addAttribute("activePage", "deployBPMN");

        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lütfen yüklenecek bir BPMN dosyası seçin.");
            return "redirect:/deployments/new";
        }

        // Dosya adını ve uzantısını kontrol et (basit kontrol)
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || (!originalFilename.endsWith(".bpmn") && !originalFilename.endsWith(".bpmn20.xml"))) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lütfen geçerli bir .bpmn veya .bpmn20.xml dosyası yükleyin.");
            return "redirect:/deployments/new";
        }

        try (InputStream inputStream = file.getInputStream()) {
            Deployment deployment = repositoryService.createDeployment()
                    .addInputStream(originalFilename, inputStream)
                    .name(deploymentName.isBlank() ? originalFilename : deploymentName)
                    .tenantId(null) // Çoklu kiracılık yoksa null
                    .deploy();

            log.info("Yeni BPMN deploy edildi. Deployment ID: {}, Adı: {}", deployment.getId(), deployment.getName());
            redirectAttributes.addFlashAttribute("successMessage",
                    "BPMN dosyası başarıyla deploy edildi! Deployment ID: " + deployment.getId());
            return "redirect:/deployments/new"; // Veya /process-instances/new'e yönlendir

        } catch (Exception e) {
            log.error("BPMN dosyası deploy edilirken hata oluştu: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Dosya deploy edilirken bir hata oluştu: " + e.getMessage());
            return "redirect:/deployments/new";
        }
    }

    // --- Süreç Başlatma Formunu Göster ---
    @GetMapping("/start-instance")
    public String showStartInstanceForm(Model model, Authentication authentication) {
        panelControllerUtil.addUserAuthInfoToModel(model, authentication);
        model.addAttribute("activePage", "startInstance"); // Sidebar için

        // En son versiyonlarına göre gruplanmış, aktif süreç tanımlarını al
        List<ProcessDefinition> processDefinitions = repositoryService.createProcessDefinitionQuery()
                .active() // Sadece aktif olanlar
                .latestVersion() // Her key için sadece en son versiyon
                .orderByProcessDefinitionKey().asc()
                .list();

        List<ProcessDefinitionDto> definitionDtos = processDefinitions.stream()
                .map(pd -> new ProcessDefinitionDto(pd.getId(), pd.getKey(), pd.getName(), pd.getVersion(), pd.getDeploymentId()))
                .collect(Collectors.toList());

        model.addAttribute("processDefinitions", definitionDtos);
        // Forma bağlanacak boş bir DTO
        if (!model.containsAttribute("startRequest")) {
            model.addAttribute("startRequest", new StartProcessInstanceRequest());
        }
        return "start-instance-form"; // resources/templates/start-instance-form.html
    }

    // --- Seçilen Süreçten Yeni Bir Örnek Başlat ---
    @PostMapping("/start-instance")
    public String startProcessInstance(@Valid @ModelAttribute("startRequest") StartProcessInstanceRequest request,
                                       BindingResult bindingResult,
                                       @RequestParam(name = "variablesString", required = false) String variablesString,
                                       RedirectAttributes redirectAttributes,
                                       Model model, Authentication authentication) {

        panelControllerUtil.addUserAuthInfoToModel(model, authentication);
        model.addAttribute("activePage", "startInstance");

        // Validation hatası varsa formu tekrar göster
        if (bindingResult.hasErrors()) {
            // Süreç tanımlarını tekrar modele eklememiz lazım, çünkü sayfa yeniden yükleniyor
            List<ProcessDefinition> processDefinitions = repositoryService.createProcessDefinitionQuery().active().latestVersion().orderByProcessDefinitionKey().asc().list();
            List<ProcessDefinitionDto> definitionDtos = processDefinitions.stream()
                    .map(pd -> new ProcessDefinitionDto(pd.getId(), pd.getKey(), pd.getName(), pd.getVersion(), pd.getDeploymentId()))
                    .collect(Collectors.toList());
            model.addAttribute("processDefinitions", definitionDtos);
            return "start-instance-form";
        }

        try {
            String processDefinitionKey = request.getProcessDefinitionKey();
            String businessKey = request.getBusinessKey();
            Map<String, Object> variables = request.getVariables() != null ? request.getVariables() : Map.of();

            // Camunda'nın tüm standart değişkenleri ve kendi değişkenlerimizi ekleyelim
            // Örneğin, bizim kargo süreci 'cargoId', 'trackingNumber', 'isCancelled' bekliyor olabilir.
            // Bu genel başlatma formu için bu değişkenleri dinamik olarak almak zor.
            // Şimdilik sadece kullanıcının girdiği değişkenlerle başlatalım.
            // Gerçek bir senaryoda, seçilen processDefinitionKey'e göre
            // beklenen başlangıç değişkenleri için dinamik bir form oluşturulabilir.

            if (variablesString != null && !variablesString.isBlank()) {
                try {
                    String[] pairs = variablesString.split(";");
                    for (String pair : pairs) {
                        String[] keyValue = pair.split("=", 2);
                        if (keyValue.length == 2) {
                            String key = keyValue[0].trim();
                            String valueStr = keyValue[1].trim();
                            // Basit tip dönüşümü (geliştirilebilir)
                            Object value = valueStr;
                            if ("true".equalsIgnoreCase(valueStr) || "false".equalsIgnoreCase(valueStr)) {
                                value = Boolean.parseBoolean(valueStr);
                            } else {
                                try {
                                    value = Long.parseLong(valueStr);
                                } catch (NumberFormatException e) {
                                    try {
                                        value = Double.parseDouble(valueStr);
                                    } catch (NumberFormatException e2) {
                                        // String olarak kalsın
                                    }
                                }
                            }
                            variables.put(key, value);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Başlangıç değişkenleri parse edilirken hata: {}", e.getMessage());
                    // Hata durumunda kullanıcıya bilgi verilebilir
                }
            }
            request.setVariables(variables); // Parse edilen map'i DTO'ya set et

            ProcessInstance processInstance;
            if (businessKey != null && !businessKey.isBlank()) {
                processInstance = runtimeService.startProcessInstanceByKey(processDefinitionKey, businessKey, variables);
            } else {
                processInstance = runtimeService.startProcessInstanceByKey(processDefinitionKey, variables);
            }

            log.info("Yeni süreç örneği başlatıldı. Key: {}, Instance ID: {}, Business Key: {}",
                    processDefinitionKey, processInstance.getId(), processInstance.getBusinessKey());

            redirectAttributes.addFlashAttribute("successMessage",
                    "Süreç başarıyla başlatıldı! Süreç ID: " + processInstance.getId() +
                            (processInstance.getBusinessKey() != null ? ", Takip No: " + processInstance.getBusinessKey() : ""));

            // Eğer bizim kargo süreciyse ve takip no varsa, onu da ekleyebiliriz.
            if (CAMUNDA_PROCESS_DEFINITION_KEY.equals(processDefinitionKey) && variables.containsKey("trackingNumber")) {
                redirectAttributes.addFlashAttribute("trackingNumberInfo", "Kargo Takip No: " + variables.get("trackingNumber"));
            }


            return "redirect:/deployments/start-instance"; // Aynı sayfaya başarı mesajıyla dön

        } catch (Exception e) {
            log.error("Süreç örneği başlatılırken hata: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Süreç başlatılırken bir hata oluştu: " + e.getMessage());
            // Hata durumunda da süreç tanımlarını ve formu tekrar gönder
            List<ProcessDefinition> processDefinitions = repositoryService.createProcessDefinitionQuery().active().latestVersion().orderByProcessDefinitionKey().asc().list();
            List<ProcessDefinitionDto> definitionDtos = processDefinitions.stream()
                    .map(pd -> new ProcessDefinitionDto(pd.getId(), pd.getKey(), pd.getName(), pd.getVersion(), pd.getDeploymentId()))
                    .collect(Collectors.toList());
            model.addAttribute("processDefinitions", definitionDtos);
            model.addAttribute("startRequest", request); // Kullanıcının girdiği veriler kaybolmasın
            return "start-instance-form";
        }
    }
}