package com.ozansoyak.cargo_process_tracking.service.impl;

import com.ozansoyak.cargo_process_tracking.dto.*;
import com.ozansoyak.cargo_process_tracking.exception.TrackingNumberGenerationException;
import com.ozansoyak.cargo_process_tracking.model.Cargo;
import com.ozansoyak.cargo_process_tracking.model.enums.CargoStatus;
import com.ozansoyak.cargo_process_tracking.repository.CargoRepository;
import com.ozansoyak.cargo_process_tracking.repository.CargoSpecification;
import com.ozansoyak.cargo_process_tracking.service.CargoService;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.*;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.IdentityLink;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.engine.task.TaskQuery;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.UserTask;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperties;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperty;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
@Slf4j
public class CargoServiceImpl implements CargoService {

    private final CargoRepository cargoRepository;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final RepositoryService repositoryService;
    private final HistoryService historyService;

    public static final String CAMUNDA_PROCESS_DEFINITION_KEY = "cargoTrackingProcessV3";
    private static final int MAX_TRACKING_NUMBER_ATTEMPTS = 10;
    private static final String NEXT_STEP_VARIABLE_PROPERTY_NAME = "nextStepVariable";

    public static final String PHYSICAL_RECEPTION_TASK_KEY = "userTask_PhysicalReception";
    public static final String INVOICE_CREATION_TASK_KEY = "userTask_InvoiceCreation";

    private static final Set<String> TRACKING_ACTIVITY_IDS = Set.of(
            "task_UpdateStatusReceived", "task_UpdateStatusLoaded1", "task_UpdateStatusTransferCenter",
            "task_UpdateStatusLoaded2", "task_UpdateStatusDistributionArea", "task_UpdateStatusOutForDelivery",
            "task_UpdateStatusDelivered", "task_UpdateStatusCancelled"
    );

    @Override
    @Transactional
    public CargoResponse createCargoAndStartProcess(CreateCargoRequest request) {
        long methodStartTime = System.currentTimeMillis();
        log.info("Yeni kargo oluşturma ve '{}' süreci başlatma isteği. Alıcı: {}", CAMUNDA_PROCESS_DEFINITION_KEY, request.getReceiverName());

        String trackingNumber = generateUniqueTrackingNumber();
        log.debug("Takip numarası üretildi: {}", trackingNumber);

        Cargo cargo = Cargo.builder()
                .trackingNumber(trackingNumber)
                .senderName(request.getSenderName()).senderAddress(request.getSenderAddress()).senderCity(request.getSenderCity())
                .senderPhone(request.getSenderPhone()).senderEmail(request.getSenderEmail())
                .receiverName(request.getReceiverName()).receiverAddress(request.getReceiverAddress()).receiverCity(request.getReceiverCity())
                .receiverPhone(request.getReceiverPhone()).receiverEmail(request.getReceiverEmail())
                .weight(request.getWeight()).dimensions(request.getDimensions()).contentDescription(request.getContentDescription())
                .currentStatus(CargoStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .lastUpdatedAt(LocalDateTime.now())
                .build();

        Cargo savedCargo = cargoRepository.save(cargo);
        log.debug("Kargo veritabanına ilk kez kaydedildi (ID: {}).", savedCargo.getId());

        Map<String, Object> processVariables = new HashMap<>();
        processVariables.put("cargoId", savedCargo.getId());
        processVariables.put("trackingNumber", savedCargo.getTrackingNumber());
        processVariables.put("isCancelled", false);

        ProcessInstance processInstance;
        try {
            processInstance = runtimeService.startProcessInstanceByKey(CAMUNDA_PROCESS_DEFINITION_KEY, savedCargo.getTrackingNumber(), processVariables);
            log.info("Camunda süreci başlatıldı. PI_ID: {}, BusinessKey: {}.",
                    processInstance.getProcessInstanceId(), savedCargo.getTrackingNumber());
        } catch (Exception e) {
            log.error("Camunda süreci başlatılamadı (key={}): {}", CAMUNDA_PROCESS_DEFINITION_KEY, e.getMessage(), e);
            throw new RuntimeException("Kargo için Camunda süreci başlatılamadı: " + e.getMessage(), e);
        }

        savedCargo.setProcessInstanceId(processInstance.getProcessInstanceId());
        cargoRepository.save(savedCargo);
        log.debug("Kargo, süreç ID'si ({}) ile veritabanına güncellendi.", processInstance.getProcessInstanceId());

        CargoResponse responseDto = new CargoResponse(
                savedCargo.getId(),
                savedCargo.getTrackingNumber(),
                savedCargo.getCurrentStatus().name(),
                processInstance.getProcessInstanceId()
        );
        log.info("Kargo oluşturma ve süreç başlatma tamamlandı. Toplam Süre: {} ms. Takip No: {}",
                (System.currentTimeMillis() - methodStartTime), responseDto.getTrackingNumber());
        return responseDto;
    }

    @Override
    @Transactional
    public void cancelCargoProcess(String trackingNumber) {
        log.info("{} takip numaralı kargo için iptal işlemi başlatıldı.", trackingNumber);
        Cargo cargo = cargoRepository.findByTrackingNumber(trackingNumber)
                .orElseThrow(() -> new EntityNotFoundException("Takip numarası ile kargo bulunamadı: " + trackingNumber));

        if (cargo.getCurrentStatus() == CargoStatus.CANCELLED || cargo.getCurrentStatus() == CargoStatus.DELIVERED) {
            log.info("Kargo (ID: {}) zaten '{}' durumunda. İptal işlemi atlanıyor.", cargo.getId(), cargo.getCurrentStatus());
            throw new IllegalStateException("Kargo zaten " + cargo.getCurrentStatus() + " durumunda, iptal edilemez.");
        }

        String processInstanceId = cargo.getProcessInstanceId();
        try {
            runtimeService.setVariable(processInstanceId, "isCancelled", true);
            log.info("Camunda süreci (ID: {}) için 'isCancelled' değişkeni 'true' olarak ayarlandı.", processInstanceId);

            List<Task> activeTasks = taskService.createTaskQuery().processInstanceId(processInstanceId).active().list();
            if (!activeTasks.isEmpty()) {
                for (Task activeTask : activeTasks) {
                    log.info("Süreç iptali için aktif görev (Task ID: {}, Key: {}) programatik olarak tamamlanıyor.", activeTask.getId(), activeTask.getTaskDefinitionKey());
                    try {
                        taskService.complete(activeTask.getId());
                        log.info("Aktif görev (Task ID: {}) iptal nedeniyle başarıyla tamamlandı.", activeTask.getId());
                    } catch (ProcessEngineException e) {
                        log.error("İptal sırasında aktif görev (Task ID: {}) tamamlanırken hata: {}", activeTask.getId(), e.getMessage(), e);
                    }
                }
            } else {
                log.info("İptal sırasında tamamlanacak aktif kullanıcı görevi bulunamadı (PI_ID: {}).", processInstanceId);
            }
        } catch (org.camunda.bpm.engine.exception.NullValueException | org.camunda.bpm.engine.exception.NotFoundException e){
            log.warn("İptal işlemi: Aktif Camunda süreci bulunamadı (ID: {}). Muhtemelen süreç zaten bitmiş/bulunamıyor.", processInstanceId);
            if (cargo.getCurrentStatus() != CargoStatus.CANCELLED && cargo.getCurrentStatus() != CargoStatus.DELIVERED) {
                cargo.setCurrentStatus(CargoStatus.CANCELLED);
                cargo.setLastUpdatedAt(LocalDateTime.now());
                cargoRepository.save(cargo);
                log.info("Süreç bulunamadığı/bitmiş olduğu için kargo (ID:{}) durumu manuel CANCELLED yapıldı.", cargo.getId());
            }
        } catch (ProcessEngineException e) {
            log.error("Kargo iptali sırasında Camunda hatası (Takip No: {}): {}", trackingNumber, e.getMessage(), e);
            throw new RuntimeException("Süreç iptal edilirken bir hata oluştu: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public TaskCompletionResponse completeUserTaskAndPrepareNextStep(String trackingNumber, String taskDefinitionKeyToComplete, Map<String, Object> taskVariables) {
        long methodStartTime = System.currentTimeMillis();
        log.info("{} takip numaralı kargo için '{}' görevini tamamlama isteği. Ek değişkenler: {}", trackingNumber, taskDefinitionKeyToComplete, taskVariables != null ? taskVariables.keySet() : "Yok");

        Cargo cargo = cargoRepository.findByTrackingNumber(trackingNumber)
                .orElseThrow(() -> new EntityNotFoundException("Takip numarası ile kargo bulunamadı: " + trackingNumber));

        String processInstanceId = cargo.getProcessInstanceId();
        Task activeTaskToComplete;
        try {
            List<Task> tasks = taskService.createTaskQuery()
                    .processInstanceId(processInstanceId)
                    .taskDefinitionKey(taskDefinitionKeyToComplete)
                    .active()
                    .list();
            if (tasks.isEmpty()) {
                log.warn("complete-step: Belirtilen anahtarla ('{}') aktif görev bulunamadı (PI_ID: {}).",
                        taskDefinitionKeyToComplete, processInstanceId);
                throw new EntityNotFoundException("Bu kargo için '" + taskDefinitionKeyToComplete + "' anahtarlı tamamlanacak aktif görev bulunamadı.");
            }
            activeTaskToComplete = tasks.get(0);
            if (tasks.size() > 1) {
                log.warn("PI_ID {} için '{}' anahtarlı birden fazla aktif görev bulundu. İlki (ID: {}) kullanılacak.", processInstanceId, taskDefinitionKeyToComplete, activeTaskToComplete.getId());
            }
        } catch (ProcessEngineException pee) {
            log.error("complete-step: Aktif görev sorgulama hatası (PI_ID: {}): {}", processInstanceId, pee.getMessage(), pee);
            throw new RuntimeException("Aktif görev durumu sorgulanamadı: " + pee.getMessage(), pee);
        }

        String taskId = activeTaskToComplete.getId();
        String actualTaskDefinitionKey = activeTaskToComplete.getTaskDefinitionKey();
        String taskName = StringUtils.hasText(activeTaskToComplete.getName()) ? activeTaskToComplete.getName() : actualTaskDefinitionKey;
        String processDefinitionId = activeTaskToComplete.getProcessDefinitionId();
        log.info("Tamamlanacak aktif görev bulundu: Task ID: {}, Task Key: {}, Process Definition ID: {}", taskId, actualTaskDefinitionKey, processDefinitionId);

        Map<String, Object> variablesToSetForNextStep = new HashMap<>();
        String nextStepVarNameFromBpmn = getNextStepVariableFromBpmn(processDefinitionId, actualTaskDefinitionKey);
        if (StringUtils.hasText(nextStepVarNameFromBpmn)) {
            variablesToSetForNextStep.put(nextStepVarNameFromBpmn, true);
            log.info("Süreç (ID: {}) için '{}' değişkeni 'true' olarak görevle birlikte ayarlanacak.", processInstanceId, nextStepVarNameFromBpmn);
        }

        Map<String, Object> variablesToCompleteTaskWith = new HashMap<>(variablesToSetForNextStep);
        if (taskVariables != null && !taskVariables.isEmpty()) {
            variablesToCompleteTaskWith.putAll(taskVariables);
            log.info("Görev tamamlama için ek değişkenler eklendi: {}", taskVariables.keySet());
        }
        if (INVOICE_CREATION_TASK_KEY.equals(actualTaskDefinitionKey)) {
            variablesToCompleteTaskWith.putIfAbsent("invoiceGenerated", true);
        }

        try {
            long startTime = System.currentTimeMillis();
            if (variablesToCompleteTaskWith.isEmpty()) {
                taskService.complete(taskId);
            } else {
                taskService.complete(taskId, variablesToCompleteTaskWith);
            }
            cargo.setLastUpdatedAt(LocalDateTime.now());
            cargoRepository.save(cargo);
            log.info("Aktif görev (Task ID: {}) başarıyla tamamlandı. Kullanılan değişkenler: {}. Süre: {}ms", taskId, variablesToCompleteTaskWith.keySet(), (System.currentTimeMillis() - startTime));
        } catch (ProcessEngineException e){
            log.error("Görev (Task ID: {}) tamamlanırken Camunda hatası: {}", taskId, e.getMessage(), e);
            throw new IllegalStateException("Görev (Task ID: " + taskId +") tamamlanamadı: " + e.getMessage(), e);
        }
        log.info("completeUserTaskAndPrepareNextStep metodu tamamlandı. Toplam Süre: {}ms", (System.currentTimeMillis() - methodStartTime));

        return new TaskCompletionResponse(
                "'" + taskName + "' adlı görev başarıyla tamamlandı.",
                taskName,
                actualTaskDefinitionKey
        );
    }

    private String getNextStepVariableFromBpmn(String processDefinitionId, String taskDefinitionKey) {
        try {
            BpmnModelInstance modelInstance = repositoryService.getBpmnModelInstance(processDefinitionId);
            if (modelInstance == null) { log.error("BPMN modeli bulunamadı: {}", processDefinitionId); return null; }
            UserTask userTaskElement = modelInstance.getModelElementById(taskDefinitionKey);
            if (userTaskElement == null) { log.error("BPMN'de görev tanımı bulunamadı: {} (PD_ID: {})", taskDefinitionKey, processDefinitionId); return null; }
            ExtensionElements extensionElements = userTaskElement.getExtensionElements();
            if (extensionElements != null) {
                List<CamundaProperties> propertiesList = extensionElements.getElementsQuery().filterByType(CamundaProperties.class).list();
                if (!propertiesList.isEmpty()) {
                    CamundaProperties camundaProperties = propertiesList.get(0);
                    Optional<CamundaProperty> nextStepProp = camundaProperties.getCamundaProperties().stream()
                            .filter(prop -> NEXT_STEP_VARIABLE_PROPERTY_NAME.equals(prop.getCamundaName())).findFirst();
                    if (nextStepProp.isPresent() && StringUtils.hasText(nextStepProp.get().getCamundaValue())) {
                        return nextStepProp.get().getCamundaValue();
                    }
                }
            }
        } catch (Exception e) {
            log.error("BPMN modelinden '{}' property okunurken hata (Task Key: {}, PD_ID: {}): {}",
                    NEXT_STEP_VARIABLE_PROPERTY_NAME, taskDefinitionKey, processDefinitionId, e.getMessage(), e);
        }
        return null;
    }

    @Override
    @Transactional(readOnly = true)
    public TrackingInfoResponse getTrackingInfo(String trackingNumber) {
        log.info("Takip numarası '{}' için kargo bilgisi (detaylı) sorgulanıyor.", trackingNumber);
        Cargo cargo = cargoRepository.findByTrackingNumber(trackingNumber)
                .orElseThrow(() -> new EntityNotFoundException("Takip numarası ile kargo bulunamadı: " + trackingNumber));

        List<TrackingHistoryEvent> historyEvents = getCargoHistory(cargo);
        List<ActiveTaskInfo> activeUserTasksInfo = new ArrayList<>();
        boolean isCancellable = cargo.getCurrentStatus() != CargoStatus.DELIVERED &&
                cargo.getCurrentStatus() != CargoStatus.CANCELLED;

        if (StringUtils.hasText(cargo.getProcessInstanceId())) {
            ProcessInstance piRuntimeCheck = null;
            try {
                piRuntimeCheck = runtimeService.createProcessInstanceQuery()
                        .processInstanceId(cargo.getProcessInstanceId())
                        .active()
                        .singleResult();
            } catch (ProcessEngineException e) {
                log.warn("getTrackingInfo: PI_ID {} sorgulanırken hata (muhtemelen birden fazla aktif süreç var?): {}", cargo.getProcessInstanceId(), e.getMessage());
            }

            if (isCancellable && piRuntimeCheck != null) {
                try {
                    List<Task> activeCamundaTasks = taskService.createTaskQuery()
                            .processInstanceId(cargo.getProcessInstanceId())
                            .active()
                            .list();

                    if (activeCamundaTasks.isEmpty()) {
                        log.warn("getTrackingInfo: PI_ID {} (Cargo Status: {}) için aktif Camunda görevi bulunamadı. " +
                                        "Süreç aktifse, bu BPMN akışında bir bekleme noktası (örn: timer) veya bir sorun olabilir.",
                                cargo.getProcessInstanceId(), cargo.getCurrentStatus());
                    } else {
                        log.info("getTrackingInfo: PI_ID {} için {} adet aktif Camunda görevi bulundu: {}",
                                cargo.getProcessInstanceId(),
                                activeCamundaTasks.size(),
                                activeCamundaTasks.stream().map(t -> t.getTaskDefinitionKey() + "[name=" + t.getName() + ",id=" + t.getId() + "]").collect(Collectors.toList()));

                        for (Task task : activeCamundaTasks) {
                            boolean requiresInputForThisTask = INVOICE_CREATION_TASK_KEY.equals(task.getTaskDefinitionKey());
                            activeUserTasksInfo.add(ActiveTaskInfo.builder()
                                    .taskDefinitionKey(task.getTaskDefinitionKey())
                                    .taskName(StringUtils.hasText(task.getName()) ? task.getName() : task.getTaskDefinitionKey())
                                    .isCompletable(true)
                                    .requiresInput(requiresInputForThisTask)
                                    .build());
                        }
                    }
                } catch (ProcessEngineException e) {
                    log.error("getTrackingInfo: Aktif Camunda görevleri sorgulanırken hata (PI_ID: {}): {}", cargo.getProcessInstanceId(), e.getMessage(), e);
                }
            } else {
                log.info("getTrackingInfo: Kargo (TN: {}) iptal/teslim edilmiş (Status: {}) olduğu için aktif görevler sorgulanmadı.", trackingNumber, cargo.getCurrentStatus());
            }
        } else {
            log.warn("getTrackingInfo: Kargo (TN: {}) için Camunda processInstanceId bulunamadı. Aktif görevler sorgulanamadı.", trackingNumber);
        }

        return TrackingInfoResponse.builder()
                .trackingNumber(cargo.getTrackingNumber())
                .currentStatus(getStatusDisplayName(cargo.getCurrentStatus()))
                .currentStatusBadgeClass(getStatusBadgeClass(cargo.getCurrentStatus()))
                .senderCity(cargo.getSenderCity()).receiverCity(cargo.getReceiverCity())
                .processInstanceId(cargo.getProcessInstanceId()).historyEvents(historyEvents).found(true)
                .senderName(cargo.getSenderName()).receiverName(cargo.getReceiverName())
                .senderPhone(cargo.getSenderPhone()).receiverPhone(cargo.getReceiverPhone())
                .senderAddress(cargo.getSenderAddress()).receiverAddress(cargo.getReceiverAddress())
                .weight(cargo.getWeight()).dimensions(cargo.getDimensions()).contentDescription(cargo.getContentDescription())
                .activeUserTasks(activeUserTasksInfo)
                .cancellable(isCancellable)
                .build();
    }

    private List<TrackingHistoryEvent> getCargoHistory(Cargo cargo) {
        if (!StringUtils.hasText(cargo.getProcessInstanceId())) {
            log.warn("Takip numarası '{}' için süreç ID'si bulunamadığından geçmiş bilgisi alınamadı.", cargo.getTrackingNumber());
            return List.of();
        }
        try {
            List<HistoricActivityInstance> activityInstances = historyService.createHistoricActivityInstanceQuery()
                    .processInstanceId(cargo.getProcessInstanceId())
                    .activityType("serviceTask")
                    .orderByHistoricActivityInstanceStartTime().asc()
                    .list();
            return activityInstances.stream()
                    .filter(activity -> TRACKING_ACTIVITY_IDS.contains(activity.getActivityId()))
                    .map(activity -> {
                        String activityName = StringUtils.hasText(activity.getActivityName()) ? activity.getActivityName() : "Bilinmeyen Aktivite";
                        String statusDesc = extractStatusFromActivityName(activityName);
                        String location = extractLocationFromActivityName(activityName);
                        LocalDateTime timestamp = convertDateToLocalDateTime(activity.getStartTime());
                        CargoStatus statusEnum = findStatusByActivityName(activityName);
                        String badgeClass = getStatusBadgeClass(statusEnum);
                        return new TrackingHistoryEvent(timestamp, statusDesc, badgeClass, location);
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Takip numarası '{}', PI_ID '{}' için Camunda geçmişi alınırken hata: {}",
                    cargo.getTrackingNumber(), cargo.getProcessInstanceId(), e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PanelDataDto getPanelData() {
        log.debug("Panel verileri sorgulanıyor...");
        long beklemedeAlinan = cargoRepository.countByCurrentStatusIn(List.of(CargoStatus.PENDING, CargoStatus.RECEIVED));
        List<CargoStatus> tasinmaTransferDurumlari = List.of(
                CargoStatus.LOADED_ON_VEHICLE_1, CargoStatus.AT_TRANSFER_CENTER,
                CargoStatus.LOADED_ON_VEHICLE_2, CargoStatus.AT_DISTRIBUTION_HUB
        );
        long tasiniyorTransferde = cargoRepository.countByCurrentStatusIn(tasinmaTransferDurumlari);
        long dagitimda = cargoRepository.countByCurrentStatus(CargoStatus.OUT_FOR_DELIVERY);
        long teslimEdilen = cargoRepository.countByCurrentStatus(CargoStatus.DELIVERED);
        long iptalEdilen = cargoRepository.countByCurrentStatus(CargoStatus.CANCELLED);

        List<RecentActivityDto> recentActivities = new ArrayList<>();
        try {
            List<HistoricActivityInstance> lastFinishedServiceTasks = historyService.createHistoricActivityInstanceQuery()
                    .activityType("serviceTask").finished().orderByHistoricActivityInstanceEndTime().desc().listPage(0, 20);
            List<HistoricActivityInstance> filteredActivities = lastFinishedServiceTasks.stream()
                    .filter(activity -> TRACKING_ACTIVITY_IDS.contains(activity.getActivityId()))
                    .limit(10).toList();
            if (!filteredActivities.isEmpty()) {
                Set<String> processInstanceIds = filteredActivities.stream().map(HistoricActivityInstance::getProcessInstanceId).collect(Collectors.toSet());
                List<HistoricProcessInstance> processInstances = historyService.createHistoricProcessInstanceQuery().processInstanceIds(processInstanceIds).list();
                Map<String, String> piIdToBusinessKeyMap = processInstances.stream()
                        .filter(pi -> StringUtils.hasText(pi.getBusinessKey()))
                        .collect(Collectors.toMap(HistoricProcessInstance::getId, HistoricProcessInstance::getBusinessKey, (key1, key2) -> key1));
                recentActivities = filteredActivities.stream().map(activity -> {
                    String activityName = StringUtils.hasText(activity.getActivityName()) ? activity.getActivityName() : "Bilinmeyen İşlem";
                    String statusDesc = extractStatusFromActivityName(activityName);
                    LocalDateTime timestamp = convertDateToLocalDateTime(activity.getEndTime());
                    String trackingNo = piIdToBusinessKeyMap.getOrDefault(activity.getProcessInstanceId(), "-");
                    CargoStatus statusEnum = findStatusByActivityName(activityName);
                    String badgeClass = getStatusBadgeClass(statusEnum);
                    return new RecentActivityDto(trackingNo, statusDesc, badgeClass, timestamp);
                }).collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.error("Son işlemler alınırken Camunda geçmiş sorgusunda hata oluştu: {}", e.getMessage(), e);
        }
        return PanelDataDto.builder()
                .beklemedeAlinanCount(beklemedeAlinan).tasiniyorTransferdeCount(tasiniyorTransferde)
                .dagitimdaCount(dagitimda).teslimEdilenCount(teslimEdilen)
                .iptalEdilenCount(iptalEdilen).recentActivities(recentActivities).build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CargoSearchResultDto> searchCargos(CargoSearchCriteria criteria, Pageable pageable) {
        Specification<Cargo> spec = CargoSpecification.findByCriteria(criteria);
        Page<Cargo> cargoPage = cargoRepository.findAll(spec, pageable);
        List<CargoSearchResultDto> resultList = cargoPage.getContent().stream()
                .map(this::mapCargoToSearchResultDto)
                .collect(Collectors.toList());
        return new PageImpl<>(resultList, pageable, cargoPage.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActiveTaskDto> getActiveUserTasks(String username, List<String> userGroups) {
        TaskQuery query = taskService.createTaskQuery().active();

        log.info("TÜM aktif kullanıcı görevleri sorgulanıyor (kullanıcı/grup filtresi yok).");

        List<Task> tasks = query.orderByTaskCreateTime().desc().list();

        if (tasks.isEmpty()) {
            log.info("Sistemde hiç aktif Camunda görevi bulunamadı.");
            return List.of();
        }

        log.info("Camunda sorgusu sonucu {} aktif görev bulundu.", tasks.size());

        Set<String> processDefinitionIds = tasks.stream()
                .map(Task::getProcessDefinitionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, ProcessDefinition> pdMap = new HashMap<>();
        if (!processDefinitionIds.isEmpty()) {
            List<ProcessDefinition> pds = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionIdIn(processDefinitionIds.toArray(new String[0]))
                    .list();
            pdMap.putAll(pds.stream().collect(Collectors.toMap(ProcessDefinition::getId, pd -> pd)));
        }

        return tasks.stream()
                .map(task -> {
                    ProcessInstance pi = null;
                    if (task.getProcessInstanceId() != null) {
                        List<ProcessInstance> instances = runtimeService.createProcessInstanceQuery()
                                .processInstanceId(task.getProcessInstanceId())
                                .list();
                        if (!instances.isEmpty()) {
                            pi = instances.get(0);
                        }
                    }
                    ProcessDefinition pd = pdMap.get(task.getProcessDefinitionId());
                    String processDefinitionName = "Bilinmeyen Süreç";
                    if (pd != null) {
                        processDefinitionName = StringUtils.hasText(pd.getName()) ? pd.getName() : pd.getKey();
                    } else if (task.getProcessDefinitionId() != null) {
                        processDefinitionName = "Tanım ID: " + task.getProcessDefinitionId();
                    }

                    String businessKeyFromPI = (pi != null) ? pi.getBusinessKey() : null;

                    List<String> candidateGroupList = Collections.emptyList(); // Başlangıçta boş
                    try {
                        candidateGroupList = taskService.getIdentityLinksForTask(task.getId()).stream()
                                .filter(link -> link.getGroupId() != null && "candidate".equals(link.getType()))
                                .map(IdentityLink::getGroupId)
                                .collect(Collectors.toList());
                    } catch (ProcessEngineException e) {
                        log.warn("Task ID {} için IdentityLink alınırken hata: {}", task.getId(), e.getMessage());
                    }


                    return ActiveTaskDto.builder()
                            .taskId(task.getId())
                            .taskName(StringUtils.hasText(task.getName()) ? task.getName() : task.getTaskDefinitionKey())
                            .taskDefinitionKey(task.getTaskDefinitionKey())
                            .processInstanceId(task.getProcessInstanceId())
                            .processDefinitionName(processDefinitionName)
                            .businessKey(businessKeyFromPI)
                            .createTime(task.getCreateTime() != null ? task.getCreateTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime() : null)
                            .assignee(task.getAssignee())
                            .candidateGroups(candidateGroupList)
                            .build();
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void completeTaskByIdAndPrepareNextStep(String taskId) {
        log.info("Task ID '{}' ile görev tamamlama isteği (genel).", taskId);
        Task task = taskService.createTaskQuery().taskId(taskId).active().singleResult();
        if (task == null) {
            throw new EntityNotFoundException("Task ID '" + taskId + "' ile aktif görev bulunamadı.");
        }

        String processInstanceId = task.getProcessInstanceId();
        String taskDefinitionKey = task.getTaskDefinitionKey();
        String processDefinitionId = task.getProcessDefinitionId();
        String taskName = StringUtils.hasText(task.getName()) ? task.getName() : taskDefinitionKey;

        log.info("Tamamlanacak genel görev: Task ID: {}, Adı: '{}', Task Key: {}, PI_ID: {}", taskId, taskName, taskDefinitionKey, processInstanceId);

        Map<String, Object> variablesToCompleteWith = new HashMap<>();
        if (processDefinitionId != null) {
            String nextStepVarNameFromBpmn = getNextStepVariableFromBpmn(processDefinitionId, taskDefinitionKey);
            if (StringUtils.hasText(nextStepVarNameFromBpmn)) {
                variablesToCompleteWith.put(nextStepVarNameFromBpmn, true);
                log.info("Süreç (ID: {}) için '{}' değişkeni 'true' olarak görevle birlikte ayarlanacak.", processInstanceId, nextStepVarNameFromBpmn);
            }
        }

        try {
            if (variablesToCompleteWith.isEmpty()) {
                taskService.complete(taskId);
            } else {
                taskService.complete(taskId, variablesToCompleteWith);
            }

            if (processInstanceId != null) {
                ProcessInstance pi = runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
                if (pi != null && StringUtils.hasText(pi.getBusinessKey())) {
                    cargoRepository.findByTrackingNumber(pi.getBusinessKey()).ifPresent(cargo -> {
                        cargo.setLastUpdatedAt(LocalDateTime.now());
                        cargoRepository.save(cargo);
                        log.info("Kargo (Takip No: {}) son güncellenme zamanı güncellendi, görev '{}' tamamlandı.", pi.getBusinessKey(), taskName);
                    });
                }
            }
            log.info("Görev (Task ID: {}, Adı: '{}') başarıyla tamamlandı.", taskId, taskName);
        } catch (ProcessEngineException e){
            log.error("Görev (Task ID: {}, Adı: '{}') tamamlanırken Camunda hatası: {}", taskId, taskName, e.getMessage(), e);
            throw new IllegalStateException("Görev (Task ID: " + taskId +", Adı: " + taskName + ") tamamlanamadı: " + e.getMessage(), e);
        }
    }

    private CargoSearchResultDto mapCargoToSearchResultDto(Cargo cargo) {
        boolean isCompletableInList = false;
        boolean isCancellable = cargo.getCurrentStatus() != CargoStatus.DELIVERED &&
                cargo.getCurrentStatus() != CargoStatus.CANCELLED;
        if (isCancellable && StringUtils.hasText(cargo.getProcessInstanceId())) {
            try {
                ProcessInstance piRuntimeCheck = runtimeService.createProcessInstanceQuery()
                        .processInstanceId(cargo.getProcessInstanceId())
                        .active()
                        .singleResult();
                if (piRuntimeCheck != null) {
                    long activeTaskCount = taskService.createTaskQuery()
                            .processInstanceId(cargo.getProcessInstanceId())
                            .active()
                            .count();
                    isCompletableInList = activeTaskCount > 0;
                } else {
                    log.trace("mapCargoToSearchResultDto for TN {}: PI_ID {} is not active. 'completable' will be false.",
                            cargo.getTrackingNumber(), cargo.getProcessInstanceId());
                }
                log.trace("mapCargoToSearchResultDto for TN {}: PI_ID {}, isCompletableInList: {}",
                        cargo.getTrackingNumber(), cargo.getProcessInstanceId(), isCompletableInList);
            } catch (ProcessEngineException e) {
                log.warn("mapCargoToSearchResultDto: Aktif görev sayısı sorgulanırken hata (TN: {}, PI_ID: {}): {}. 'completable' false olarak ayarlanıyor.",
                        cargo.getTrackingNumber(), cargo.getProcessInstanceId(), e.getMessage());
            }
        } else if (!StringUtils.hasText(cargo.getProcessInstanceId()) && isCancellable) {
            log.trace("mapCargoToSearchResultDto for TN {}: No PI_ID. 'completable' will be false.", cargo.getTrackingNumber());
        }
        return CargoSearchResultDto.builder()
                .trackingNumber(cargo.getTrackingNumber()).senderName(cargo.getSenderName())
                .receiverName(cargo.getReceiverName()).receiverCity(cargo.getReceiverCity())
                .currentStatus(getStatusDisplayName(cargo.getCurrentStatus()))
                .currentStatusBadgeClass(getStatusBadgeClass(cargo.getCurrentStatus()))
                .lastUpdatedAt(cargo.getLastUpdatedAt())
                .cancellable(isCancellable).completable(isCompletableInList).build();
    }

    private CargoStatus findStatusByActivityName(String activityName) {
        if (!StringUtils.hasText(activityName)) return null;
        String lowerActivityName = activityName.toLowerCase();
        if (lowerActivityName.contains("kargo alındı")) return CargoStatus.RECEIVED;
        if (lowerActivityName.contains("ilk araca yüklendi")) return CargoStatus.LOADED_ON_VEHICLE_1;
        if (lowerActivityName.contains("transfer merkezinde")) return CargoStatus.AT_TRANSFER_CENTER;
        if (lowerActivityName.contains("son araca yüklendi")) return CargoStatus.LOADED_ON_VEHICLE_2;
        if (lowerActivityName.contains("dağıtım bölgesinde")) return CargoStatus.AT_DISTRIBUTION_HUB;
        if (lowerActivityName.contains("dağıtımda")) return CargoStatus.OUT_FOR_DELIVERY;
        if (lowerActivityName.contains("teslim edildi")) return CargoStatus.DELIVERED;
        if (lowerActivityName.contains("iptal edildi")) return CargoStatus.CANCELLED;
        return null;
    }

    private String extractStatusFromActivityName(String activityName) {
        if (!StringUtils.hasText(activityName)) return "Bilinmeyen Durum";
        if (activityName.startsWith("Durumu Güncelle: ")) return activityName.substring("Durumu Güncelle: ".length()).trim();
        if (activityName.startsWith("Tamamla: ")) return activityName.substring("Tamamla: ".length()).trim() + " Tamamlandı";
        return activityName;
    }

    private String extractLocationFromActivityName(String activityName) {
        if (!StringUtils.hasText(activityName)) return "-";
        String lowerActivityName = activityName.toLowerCase();
        if (lowerActivityName.contains("transfer merkezi")) return "Transfer Merkezi";
        if (lowerActivityName.contains("dağıtım bölgesi")) return "Dağıtım Bölgesi";
        if (lowerActivityName.contains("ilk araca yüklendi")) return "Kalkış Noktası";
        if (lowerActivityName.contains("son araca yüklendi")) return "Ara Transfer Noktası";
        if (lowerActivityName.contains("dağıtımda")) return "Müşteri Adresine Yakın Bölge";
        if (lowerActivityName.contains("teslim edildi")) return "Teslimat Adresi";
        if (lowerActivityName.contains("kargo alındı")) return "Gönderici Şube";
        if (lowerActivityName.contains("iptal edildi")) return "İşlem Merkezi";
        return "-";
    }

    private LocalDateTime convertDateToLocalDateTime(Date dateToConvert) {
        return dateToConvert == null ? null : dateToConvert.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private String getStatusDisplayName(CargoStatus status) {
        if (status == null) return "Bilinmiyor";
        return switch (status) {
            case PENDING -> "Beklemede"; case RECEIVED -> "Kargo Alındı";
            case LOADED_ON_VEHICLE_1 -> "İlk Araca Yüklendi"; case AT_TRANSFER_CENTER -> "Transfer Merkezinde";
            case LOADED_ON_VEHICLE_2 -> "Son Araca Yüklendi"; case AT_DISTRIBUTION_HUB -> "Dağıtım Bölgesinde";
            case OUT_FOR_DELIVERY -> "Dağıtımda"; case DELIVERED -> "Teslim Edildi";
            case CANCELLED -> "İptal Edildi"; default -> status.name().replace("_", " ").toLowerCase();
        };
    }

    private String getStatusBadgeClass(CargoStatus status) {
        if (status == null) return "bg-secondary text-white";
        return switch (status) {
            case PENDING, RECEIVED -> "bg-secondary text-white";
            case LOADED_ON_VEHICLE_1, AT_TRANSFER_CENTER, LOADED_ON_VEHICLE_2, AT_DISTRIBUTION_HUB -> "bg-primary text-white";
            case OUT_FOR_DELIVERY -> "bg-info text-dark";
            case DELIVERED -> "bg-success text-white"; case CANCELLED -> "bg-danger text-white";
            default -> "bg-dark text-white";
        };
    }

    private String generateUniqueTrackingNumber() {
        for (int i = 0; i < MAX_TRACKING_NUMBER_ATTEMPTS; i++) {
            String prefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMM"));
            int randomPart = ThreadLocalRandom.current().nextInt(100000, 1000000);
            String trackingNumber = prefix + randomPart;
            if (!cargoRepository.existsByTrackingNumber(trackingNumber)) {
                log.debug("Üretilen Takip Numarası: {}", trackingNumber);
                return trackingNumber;
            }
            log.warn("Takip numarası çakışması: {}. Deneme: {}/{}", trackingNumber, i + 1, MAX_TRACKING_NUMBER_ATTEMPTS);
        }
        log.error("{} denemede benzersiz takip numarası üretilemedi.", MAX_TRACKING_NUMBER_ATTEMPTS);
        throw new TrackingNumberGenerationException("Benzersiz takip numarası " + MAX_TRACKING_NUMBER_ATTEMPTS + " denemede üretilemedi.");
    }
}