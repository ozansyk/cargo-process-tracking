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
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// Projenizdeki CAMUNDA_PROCESS_DEFINITION_KEY sabitinin doğru yerden import edilmesi veya burada tanımlanması gerekir.
// Örnek: private static final String CARGO_TRACKING_PROCESS_KEY = "cargoTrackingProcessV3";

@Controller
@RequestMapping("/panel/deployments") // Base path güncellendi
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class DeploymentController {

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;

    // Ortak UI attribute'larını ekleyen metot
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
                username = authentication.getName();
            }
        }
        model.addAttribute("username", username);
        model.addAttribute("userRoles", roles);
        model.addAttribute("activeMenuItem", activeMenuItem);
    }

    @GetMapping("/new-bpmn")
    public String showDeploymentForm(Model model, Authentication authentication) {
        addCommonPanelAttributes(model, authentication, "deployBPMN");
        return "deploy-form"; // templates/deploy-form.html (veya panel/deploy-form)
    }

    @PostMapping("/upload")
    public String handleFileUpload(@RequestParam("file") MultipartFile file,
                                   @RequestParam(name = "deploymentName", required = false) String deploymentNameFromRequest,
                                   RedirectAttributes redirectAttributes,
                                   Authentication authentication, Model model) { // Model eklendi

        addCommonPanelAttributes(model, authentication, "deployBPMN"); // Her durumda lazım

        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Lütfen yüklenecek bir BPMN dosyası seçin.");
            return "redirect:/panel/deployments/new-bpmn";
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || (!originalFilename.toLowerCase().endsWith(".bpmn") && !originalFilename.toLowerCase().endsWith(".bpmn20.xml"))) {
            redirectAttributes.addFlashAttribute("errorMessage", "Geçersiz dosya türü. Lütfen .bpmn veya .bpmn20.xml uzantılı bir dosya yükleyin.");
            return "redirect:/panel/deployments/new-bpmn";
        }

        final String deploymentName = StringUtils.hasText(deploymentNameFromRequest) ? deploymentNameFromRequest.trim() : originalFilename;

        try (InputStream inputStream = file.getInputStream()) {
            Deployment deployment = repositoryService.createDeployment()
                    .addInputStream(originalFilename, inputStream)
                    .name(deploymentName)
                    .deploy();

            log.info("Yeni BPMN deploy edildi. Deployment ID: {}, Adı: {}", deployment.getId(), deployment.getName());
            redirectAttributes.addFlashAttribute("successMessage",
                    "BPMN dosyası ('" + deployment.getName() + "') başarıyla deploy edildi! ID: " + deployment.getId());

        } catch (Exception e) {
            log.error("BPMN dosyası deploy edilirken hata oluştu: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Dosya deploy edilirken bir hata oluştu: " + e.getMessage());
        }
        return "redirect:/panel/deployments/new-bpmn";
    }

    @GetMapping("/start-instance")
    public String showStartInstanceForm(Model model, Authentication authentication) {
        addCommonPanelAttributes(model, authentication, "startInstance");

        List<ProcessDefinition> processDefinitions = repositoryService.createProcessDefinitionQuery()
                .active().latestVersion().orderByProcessDefinitionKey().asc().list();
        List<ProcessDefinitionDto> definitionDtos = processDefinitions.stream()
                .map(pd -> new ProcessDefinitionDto(pd.getId(), pd.getKey(), pd.getName(), pd.getVersion(), pd.getDeploymentId()))
                .collect(Collectors.toList());
        model.addAttribute("processDefinitions", definitionDtos);
        if (!model.containsAttribute("startRequest")) {
            model.addAttribute("startRequest", new StartProcessInstanceRequest());
        }
        return "start-instance-form"; // templates/start-instance-form.html
    }

    @PostMapping("/start-instance")
    public String startProcessInstance(@Valid @ModelAttribute("startRequest") StartProcessInstanceRequest request,
                                       BindingResult bindingResult,
                                       @RequestParam(name = "variablesString", required = false) String variablesString,
                                       RedirectAttributes redirectAttributes,
                                       Model model, Authentication authentication) {

        addCommonPanelAttributes(model, authentication, "startInstance");

        if (bindingResult.hasErrors()) {
            List<ProcessDefinition> processDefinitions = repositoryService.createProcessDefinitionQuery().active().latestVersion().orderByProcessDefinitionKey().asc().list();
            model.addAttribute("processDefinitions", processDefinitions.stream()
                    .map(pd -> new ProcessDefinitionDto(pd.getId(), pd.getKey(), pd.getName(), pd.getVersion(), pd.getDeploymentId()))
                    .collect(Collectors.toList()));
            return "start-instance-form";
        }

        try {
            String processDefinitionKey = request.getProcessDefinitionKey();
            String businessKey = StringUtils.hasText(request.getBusinessKey()) ? request.getBusinessKey().trim() : null;
            Map<String, Object> variables = new HashMap<>();
            if (request.getVariables() != null) {
                variables.putAll(request.getVariables());
            }

            if (StringUtils.hasText(variablesString)) {
                String parsableVariablesString = variablesString.replace("\r\n", ";").replace("\n", ";");
                try {
                    String[] pairs = parsableVariablesString.split(";");
                    for (String pair : pairs) {
                        if (StringUtils.hasText(pair)) {
                            String[] keyValue = pair.split("=", 2);
                            if (keyValue.length == 2) {
                                String key = keyValue[0].trim();
                                String valueStr = keyValue[1].trim();
                                Object value = valueStr;
                                if ("true".equalsIgnoreCase(valueStr) || "false".equalsIgnoreCase(valueStr)) {
                                    value = Boolean.parseBoolean(valueStr);
                                } else {
                                    try { value = Long.parseLong(valueStr); }
                                    catch (NumberFormatException e) {
                                        try { value = Double.parseDouble(valueStr); }
                                        catch (NumberFormatException e2) { /* String olarak kalır */ }
                                    }
                                }
                                if (StringUtils.hasText(key)) { variables.put(key, value); }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Başlangıç değişkenleri (variablesString) parse edilirken hata: {}", e.getMessage());
                    redirectAttributes.addFlashAttribute("warningMessage", "Değişkenler parse edilirken sorun oluştu.");
                }
            }

            ProcessInstance processInstance;
            if (businessKey != null) {
                processInstance = runtimeService.startProcessInstanceByKey(processDefinitionKey, businessKey, variables);
            } else {
                processInstance = runtimeService.startProcessInstanceByKey(processDefinitionKey, variables);
            }

            log.info("Yeni süreç örneği başlatıldı. Key: {}, Instance ID: {}, Business Key: {}",
                    processDefinitionKey, processInstance.getId(), processInstance.getBusinessKey());
            ProcessDefinition pd = repositoryService.createProcessDefinitionQuery().processDefinitionKey(processDefinitionKey).latestVersion().singleResult();
            String processName = (pd != null && StringUtils.hasText(pd.getName())) ? pd.getName() : processDefinitionKey;
            redirectAttributes.addFlashAttribute("successMessage",
                    "'" + processName + "' süreci başarıyla başlatıldı! Süreç ID: " + processInstance.getId() +
                            (processInstance.getBusinessKey() != null ? ", İş Anahtarı: " + processInstance.getBusinessKey() : ""));
            return "redirect:/panel/deployments/start-instance";

        } catch (Exception e) {
            log.error("Süreç örneği başlatılırken hata: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("errorMessage", "Süreç başlatılırken bir hata oluştu: " + e.getMessage());
            List<ProcessDefinition> processDefinitions = repositoryService.createProcessDefinitionQuery().active().latestVersion().orderByProcessDefinitionKey().asc().list();
            model.addAttribute("processDefinitions", processDefinitions.stream()
                    .map(pDef -> new ProcessDefinitionDto(pDef.getId(), pDef.getKey(), pDef.getName(), pDef.getVersion(), pDef.getDeploymentId()))
                    .collect(Collectors.toList()));
            model.addAttribute("startRequest", request);
            return "start-instance-form";
        }
    }
}