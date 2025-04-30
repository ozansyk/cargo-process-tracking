package com.ozansoyak.cargo_process_tracking.controller;

import lombok.RequiredArgsConstructor;
// Camunda importları
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/monitor") // URL aynı kalabilir
@RequiredArgsConstructor
public class CamundaMonitorController { // Sınıf adı güncellendi

    private static final Logger log = LoggerFactory.getLogger(CamundaMonitorController.class);

    // Camunda servisleri enjekte edilecek
    private final RuntimeService runtimeService;
    private final HistoryService historyService;

    // Process Definition Key (BPMN ile eşleşmeli)
    private static final String PROCESS_DEFINITION_KEY = "simpleCargoProcess";
    private static final String TRACKING_NUMBER_VAR = "trackingNumber"; // Bu değişken ismi BPMN veya Service'de kullanılana göre ayarlanmalı

    @GetMapping
    public String showMonitorPage(Model model) {
        // Aktif süreç örneklerini sorgula
        List<ProcessInstance> activeInstances = runtimeService.createProcessInstanceQuery()
                .processDefinitionKey(PROCESS_DEFINITION_KEY)
                .active()
                .orderByProcessInstanceId().desc()
                .list();

        // Tamamlanmış süreç örneklerini sorgula
        List<HistoricProcessInstance> completedInstances = historyService.createHistoricProcessInstanceQuery()
                .processDefinitionKey(PROCESS_DEFINITION_KEY)
                .finished()
                .orderByProcessInstanceId().desc()
                .list();

        // Aktif süreçlerin mevcut aktivitelerini bulma (Camunda API)
        Map<String, List<String>> activeActivityMap = new HashMap<>();
        for (ProcessInstance instance : activeInstances) {
            List<String> currentActivities = runtimeService.getActiveActivityIds(instance.getId());
            activeActivityMap.put(instance.getId(), currentActivities);
        }

        // Süreçlerin tamamlanmış aktivitelerini bulma (Camunda API)
        Map<String, Set<String>> completedActivityMap = new HashMap<>();
        List<HistoricProcessInstance> allInstancesForHistory = historyService.createHistoricProcessInstanceQuery()
                .processDefinitionKey(PROCESS_DEFINITION_KEY)
                // .processInstanceIds(allInstanceIds) // Performans için filtreleme yapılabilir
                .list();

        for (HistoricProcessInstance histInstance : allInstancesForHistory) {
            Set<String> completedActs = historyService.createHistoricActivityInstanceQuery()
                    .processInstanceId(histInstance.getId())
                    .finished() // Sadece bitmiş aktiviteler
                    .list()
                    .stream()
                    .map(HistoricActivityInstance::getActivityId) // Aktivite ID'sini al
                    .collect(Collectors.toSet());
            completedActivityMap.put(histInstance.getId(), completedActs);
        }


        model.addAttribute("activeInstances", activeInstances);
        model.addAttribute("completedInstances", completedInstances);
        model.addAttribute("activeActivityMap", activeActivityMap); // Aktif aktiviteler (liste olabilir)
        model.addAttribute("completedActivityMap", completedActivityMap); // Tamamlanmış aktiviteler (set)

        return "camunda-monitor-visual"; // HTML dosyasının adı güncellendi
    }

    // Test amacıyla yeni süreç başlatma endpoint'i (Opsiyonel)
    @PostMapping("/start")
    public String startNewProcessInstance(@RequestParam(required = false) String trackingNumber) {
        String tkNumber = (trackingNumber != null && !trackingNumber.isEmpty())
                ? trackingNumber
                : "TK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Map<String, Object> variables = new HashMap<>();
        variables.put(TRACKING_NUMBER_VAR, tkNumber);
        // Dikkat: Bu endpoint CargoService'i çağırmadığı için cargoId gibi değişkenler set edilmez!
        // Sadece basit bir süreç başlatır. Gerçek kullanım CargoService üzerinden olmalı.
        // variables.put("cargoId", 0L); // Dummy value - Delegate hata verir!

        try {
            // Camunda RuntimeService ile başlat
            ProcessInstance instance = runtimeService.startProcessInstanceByKey(PROCESS_DEFINITION_KEY, tkNumber, variables); // Business key eklendi
            log.info("Monitor'dan test süreci başlatıldı: {} with tracking number: {}", instance.getId(), tkNumber);
        } catch (Exception e) {
            log.error("Monitor'dan test süreci başlatılamadı (key={}): {}", PROCESS_DEFINITION_KEY, e.getMessage(), e);
            // Hata yönetimi eklenebilir (örn: Model'e hata mesajı ekle)
        }
        return "redirect:/monitor";
    }
}