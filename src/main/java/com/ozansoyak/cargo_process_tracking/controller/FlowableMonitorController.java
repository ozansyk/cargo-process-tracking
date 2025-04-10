package com.ozansoyak.cargo_process_tracking.controller;

import lombok.RequiredArgsConstructor;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
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
@RequestMapping("/monitor")
@RequiredArgsConstructor
public class FlowableMonitorController {

    private static final Logger log = LoggerFactory.getLogger(FlowableMonitorController.class);

    private final RuntimeService runtimeService;

    private final HistoryService historyService;

    private static final String PROCESS_DEFINITION_KEY = "simpleCargoProcess";
    private static final String TRACKING_NUMBER_VAR = "trackingNumber";

    @GetMapping
    public String showMonitorPage(Model model) {
        List<ProcessInstance> activeInstances = runtimeService.createProcessInstanceQuery()
                .processDefinitionKey(PROCESS_DEFINITION_KEY)
                .active()
                .orderByProcessInstanceId().desc()
                .list();

        List<HistoricProcessInstance> completedInstances = historyService.createHistoricProcessInstanceQuery()
                .processDefinitionKey(PROCESS_DEFINITION_KEY)
                .finished()
                .orderByProcessInstanceId().desc()
                .list();

        Map<String, String> activeActivityMap = new HashMap<>();
        Map<String, Set<String>> completedActivityMap = new HashMap<>();

        for (ProcessInstance instance : activeInstances) {
            List<String> currentActivities = runtimeService.getActiveActivityIds(instance.getId());
            if (!currentActivities.isEmpty()) {
                activeActivityMap.put(instance.getId(), currentActivities.get(0));
            }

            Set<String> completedActs = historyService.createHistoricActivityInstanceQuery()
                    .processInstanceId(instance.getId())
                    .finished()
                    .list()
                    .stream()
                    .map(HistoricActivityInstance::getActivityId)
                    .collect(Collectors.toSet());
            completedActivityMap.put(instance.getId(), completedActs);
        }

        for (HistoricProcessInstance hInstance : completedInstances) {
            Set<String> completedActs = historyService.createHistoricActivityInstanceQuery()
                    .processInstanceId(hInstance.getId())
                    .finished()
                    .list()
                    .stream()
                    .map(HistoricActivityInstance::getActivityId)
                    .collect(Collectors.toSet());
            completedActs.add("startEvent");
            completedActs.add("persistReceivedTask");
            completedActs.add("endEvent");
            completedActivityMap.put(hInstance.getId(), completedActs);
        }

        model.addAttribute("activeInstances", activeInstances);
        model.addAttribute("completedInstances", completedInstances);
        model.addAttribute("activeActivityMap", activeActivityMap);
        model.addAttribute("completedActivityMap", completedActivityMap);

        return "flowable-monitor-visual"; // HTML dosyamızın adı
    }

    // --- startNewProcessInstance metodu ---
    @PostMapping("/start")
    public String startNewProcessInstance(@RequestParam(required = false) String trackingNumber) {
        String tkNumber = (trackingNumber != null && !trackingNumber.isEmpty())
                ? trackingNumber
                : "TK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Map<String, Object> variables = new HashMap<>();
        variables.put(TRACKING_NUMBER_VAR, tkNumber);
        variables.put("receivedTimestamp", System.currentTimeMillis());
        try {
            ProcessInstance instance = runtimeService.startProcessInstanceByKey(PROCESS_DEFINITION_KEY, variables);
            log.info("Started new process instance: {} with tracking number: {}", instance.getId(), tkNumber);
        } catch (Exception e) {
            log.error("Could not start process instance for key: {}", PROCESS_DEFINITION_KEY, e);
            // Hata yönetimi eklenebilir
        }
        return "redirect:/monitor";
    }
}