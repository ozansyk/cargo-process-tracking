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
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
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
import org.springframework.util.CollectionUtils; // Bu importu ekleyin
import org.springframework.util.StringUtils;

import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CargoServiceImpl implements CargoService {

    private final CargoRepository cargoRepository;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final RepositoryService repositoryService;
    private final HistoryService historyService;

    private static final String CAMUNDA_PROCESS_DEFINITION_KEY = "cargoTrackingProcessV3";
    private static final int MAX_TRACKING_NUMBER_ATTEMPTS = 10;
    private static final String NEXT_STEP_VARIABLE_PROPERTY_NAME = "nextStepVariable";

    private static final Set<String> TRACKING_ACTIVITY_IDS = Set.of(
            "task_UpdateStatusReceived",
            "task_UpdateStatusLoaded1",
            "task_UpdateStatusTransferCenter",
            "task_UpdateStatusLoaded2",
            "task_UpdateStatusDistributionArea",
            "task_UpdateStatusOutForDelivery",
            "task_UpdateStatusDelivered",
            "task_UpdateStatusCancelled"
    );

    // --- createCargoAndStartProcess Metodu GÜNCELLENDİ (Loglama Eklendi) ---
    @Override
    @Transactional
    public CargoResponse createCargoAndStartProcess(CreateCargoRequest request) {
        long methodStartTime = System.currentTimeMillis();
        log.info("Yeni kargo oluşturma ve '{}' süreci başlatma isteği. Talep: {}", CAMUNDA_PROCESS_DEFINITION_KEY, request.getReceiverName());

        long startTime = System.currentTimeMillis();
        String trackingNumber = generateUniqueTrackingNumber();
        log.info("Takip numarası üretildi: {}. Süre: {} saniye", trackingNumber, (System.currentTimeMillis() - startTime) / 1000.0);

        startTime = System.currentTimeMillis();
        Cargo cargo = Cargo.builder()
                .trackingNumber(trackingNumber)
                .senderName(request.getSenderName()).senderAddress(request.getSenderAddress()).senderCity(request.getSenderCity())
                .senderPhone(request.getSenderPhone()).senderEmail(request.getSenderEmail())
                .receiverName(request.getReceiverName()).receiverAddress(request.getReceiverAddress()).receiverCity(request.getReceiverCity())
                .receiverPhone(request.getReceiverPhone()).receiverEmail(request.getReceiverEmail())
                .weight(request.getWeight()).dimensions(request.getDimensions()).contentDescription(request.getContentDescription())
                .currentStatus(CargoStatus.PENDING)
                .build();
        log.info("Kargo nesnesi oluşturuldu. Süre: {} saniye", (System.currentTimeMillis() - startTime) / 1000.0);

        startTime = System.currentTimeMillis();
        Cargo savedCargo = cargoRepository.save(cargo);
        log.info("Kargo veritabanına ilk kez kaydedildi (ID: {}). Süre: {} saniye", savedCargo.getId(), (System.currentTimeMillis() - startTime) / 1000.0);

        Map<String, Object> processVariables = new HashMap<>();
        String businessKey = savedCargo.getTrackingNumber();
        processVariables.put("cargoId", savedCargo.getId());
        processVariables.put("trackingNumber", savedCargo.getTrackingNumber());
        processVariables.put("isCancelled", false);

        ProcessInstance processInstance;
        try {
            startTime = System.currentTimeMillis();
            processInstance = runtimeService.startProcessInstanceByKey(CAMUNDA_PROCESS_DEFINITION_KEY, businessKey, processVariables);
            log.info("Camunda süreci başlatıldı. PI_ID: {}, BusinessKey: {}. Süre: {} saniye",
                    processInstance.getProcessInstanceId(), businessKey, (System.currentTimeMillis() - startTime) / 1000.0);
        } catch (Exception e) {
            log.error("Camunda süreci başlatılamadı (key={}): {}", CAMUNDA_PROCESS_DEFINITION_KEY, e.getMessage(), e);
            throw new RuntimeException("Kargo süreci başlatılamadı: " + e.getMessage(), e);
        }

        startTime = System.currentTimeMillis();
        savedCargo.setProcessInstanceId(processInstance.getProcessInstanceId());
        cargoRepository.save(savedCargo); // Süreç ID'si ile tekrar kaydet
        log.info("Kargo, süreç ID'si ({}) ile veritabanına güncellendi. Süre: {} saniye",
                processInstance.getProcessInstanceId(), (System.currentTimeMillis() - startTime) / 1000.0);

        CargoResponse responseDto = new CargoResponse(
                savedCargo.getId(),
                savedCargo.getTrackingNumber(),
                savedCargo.getCurrentStatus().name(), // PENDING olarak dönecek
                processInstance.getProcessInstanceId()
        );
        log.info("Kargo oluşturma ve süreç başlatma tamamlandı. Toplam Süre: {} saniye. Dönen yanıt: Takip No: {}",
                (System.currentTimeMillis() - methodStartTime) / 1000.0, responseDto.getTrackingNumber());
        return responseDto;
    }

    // --- Diğer Metodlar (cancelCargoProcess, completeUserTaskAndPrepareNextStep, getTrackingInfo, getPanelData, searchCargos, vs.) ---
    // Bu metodlar ÖNCEKİ CEVAPLARDAKİ DOĞRU VE TAM HALLERİYLE KALMALIDIR.
    // Değişiklik yapılmadığı için tekrar eklemiyorum.

    @Override
    @Transactional
    public void cancelCargoProcess(String trackingNumber) {
        log.info("{} takip numaralı kargo için iptal işlemi başlatıldı.", trackingNumber);
        Cargo cargo = cargoRepository.findByTrackingNumber(trackingNumber)
                .orElseThrow(() -> new EntityNotFoundException("Takip numarası ile kargo bulunamadı: " + trackingNumber));
        if (cargo.getCurrentStatus() == CargoStatus.CANCELLED || cargo.getCurrentStatus() == CargoStatus.DELIVERED) {
            log.info("Kargo (ID: {}) zaten '{}' durumunda. İptal işlemi atlanıyor.", cargo.getId(), cargo.getCurrentStatus());
            return;
        }
        String processInstanceId = cargo.getProcessInstanceId();
        if (processInstanceId == null) {
            log.warn("İptal işlemi: Kargo (ID: {}) için Camunda PI_ID bulunamadı. Durum manuel CANCELLED yapılıyor.", cargo.getId());
            cargo.setCurrentStatus(CargoStatus.CANCELLED);
            cargoRepository.save(cargo);
            return;
        }
        ProcessInstance processInstance = null;
        try {
            processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).active().singleResult();
        } catch (org.camunda.bpm.engine.exception.NullValueException | org.camunda.bpm.engine.exception.NotFoundException e) {
            log.warn("İptal işlemi: Aktif Camunda süreci bulunamadı (ID: {}). Kargo durumu: {}", processInstanceId, cargo.getCurrentStatus());
            if (cargo.getCurrentStatus() != CargoStatus.CANCELLED && cargo.getCurrentStatus() != CargoStatus.DELIVERED) {
                log.warn("Süreç bitmiş/bulunamadı ama kargo durumu (ID: {}) '{}'. Manuel olarak CANCELLED yapılıyor.", cargo.getId(), cargo.getCurrentStatus());
                cargo.setCurrentStatus(CargoStatus.CANCELLED);
                cargoRepository.save(cargo);
            }
            return;
        } catch(ProcessEngineException pee) {
            log.error("Camunda PI sorgulanırken hata (ID: {}): {}", processInstanceId, pee.getMessage(), pee);
            throw new RuntimeException("Süreç durumu sorgulanırken Camunda hatası: " + pee.getMessage(), pee);
        }
        try {
            Object currentCancelVar = runtimeService.getVariable(processInstanceId, "isCancelled");
            if (currentCancelVar == null || !Boolean.TRUE.equals(currentCancelVar)) {
                runtimeService.setVariable(processInstanceId, "isCancelled", true);
                log.info("Camunda süreci (ID: {}) için 'isCancelled' 'true' olarak ayarlandı.", processInstanceId);
            } else {
                log.info("Camunda süreci (ID: {}) için 'isCancelled' zaten 'true' idi.", processInstanceId);
            }
        } catch (ProcessEngineException e) {
            log.error("Camunda süreci (ID: {}) iptal değişkeni ayarlanırken hata: {}", processInstanceId, e.getMessage(), e);
            throw new RuntimeException("Süreç iptal edilirken Camunda değişkeni ayarlanamadı: " + e.getMessage(), e);
        }
        Task activeTask = null;
        try {
            activeTask = taskService.createTaskQuery().processInstanceId(processInstanceId).active().singleResult();
        } catch (org.camunda.bpm.engine.exception.NullValueException | org.camunda.bpm.engine.exception.NotFoundException e) {
            log.info("İptal sırasında tamamlanacak aktif kullanıcı görevi bulunamadı.");
        } catch(ProcessEngineException e) {
            log.error("İptal sırasında aktif görev sorgulanırken hata (PI_ID: {}): {}", processInstanceId, e.getMessage(), e);
        }
        if (activeTask != null) {
            log.info("Süreç iptali için aktif görev (Task ID: {}) programatik olarak tamamlanıyor.", activeTask.getId());
            try {
                taskService.complete(activeTask.getId());
                log.info("Aktif görev (Task ID: {}) iptal nedeniyle başarıyla tamamlandı.", activeTask.getId());
            } catch (ProcessEngineException e){
                log.error("İptal sırasında aktif görev (Task ID: {}) tamamlanırken hata: {}", activeTask.getId(), e.getMessage(), e);
            }
        }
    }

    @Override
    @Transactional
    public void completeUserTaskAndPrepareNextStep(String trackingNumber) {
        long methodStartTime = System.currentTimeMillis();
        log.info("{} takip numaralı kargo için görevi tamamlama ve sonraki adımı hazırlama isteği.", trackingNumber);

        long startTime = System.currentTimeMillis();
        Cargo cargo = cargoRepository.findByTrackingNumber(trackingNumber)
                .orElseThrow(() -> new EntityNotFoundException("Takip numarası ile kargo bulunamadı: " + trackingNumber));
        log.info("Kargo bulundu (complete-step). Süre: {}ms", (System.currentTimeMillis() - startTime));

        if (cargo.getCurrentStatus() == CargoStatus.CANCELLED) {
            log.warn("complete-step: Kargo (Takip No: {}) zaten CANCELLED. Görev tamamlanamaz.", trackingNumber);
            throw new IllegalStateException("Kargo zaten CANCELLED durumunda olduğu için işlem yapılamaz.");
        }

        String processInstanceId = cargo.getProcessInstanceId();
        if (processInstanceId == null) {
            throw new IllegalStateException("Kargo (Takip No: " + trackingNumber + ") için süreç ID'si bulunamadı.");
        }

        ProcessInstance processInstance;
        Task activeTask;
        try {
            startTime = System.currentTimeMillis();
            processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).active().singleResult();
            log.info("Aktif süreç sorgulandı. Süre: {}ms", (System.currentTimeMillis() - startTime));

            startTime = System.currentTimeMillis();
            activeTask = taskService.createTaskQuery().processInstanceId(processInstanceId).active().singleResult();
            log.info("Aktif görev sorgulandı. Süre: {}ms", (System.currentTimeMillis() - startTime));

        } catch (org.camunda.bpm.engine.exception.NullValueException | org.camunda.bpm.engine.exception.NotFoundException e) {
            log.warn("complete-step: Aktif süreç veya görev bulunamadı (PI_ID: {}).", processInstanceId, e);
            throw new EntityNotFoundException("Bu kargo (Takip No: " + trackingNumber + ") için tamamlanacak aktif görev/süreç yok.");
        } catch (ProcessEngineException pee) {
            log.error("complete-step: Aktif süreç/görev sorgulama hatası (PI_ID: {}): {}", processInstanceId, pee.getMessage(), pee);
            throw new RuntimeException("Aktif süreç/görev durumu sorgulanamadı: " + pee.getMessage(), pee);
        }

        String taskId = activeTask.getId();
        String taskDefinitionKey = activeTask.getTaskDefinitionKey();
        String processDefinitionId = activeTask.getProcessDefinitionId();
        log.info("Aktif görev: Task ID: {}, Task Key: {}, Process Definition ID: {}", taskId, taskDefinitionKey, processDefinitionId);

        String variableNameToSet = null;
        try {
            startTime = System.currentTimeMillis();
            BpmnModelInstance modelInstance = repositoryService.getBpmnModelInstance(processDefinitionId);
            if (modelInstance == null) throw new IllegalStateException("BPMN modeli bulunamadı: " + processDefinitionId);
            UserTask userTaskElement = modelInstance.getModelElementById(taskDefinitionKey);
            if (userTaskElement == null) throw new IllegalStateException("BPMN'de görev tanımı bulunamadı: " + taskDefinitionKey);
            log.info("BPMN modelinden görev tanımı okundu. Süre: {}ms", (System.currentTimeMillis() - startTime));

            ExtensionElements extensionElements = userTaskElement.getExtensionElements();
            if (extensionElements != null) {
                List<CamundaProperties> propertiesList = extensionElements.getElementsQuery().filterByType(CamundaProperties.class).list();
                if (!propertiesList.isEmpty()) {
                    CamundaProperties camundaProperties = propertiesList.get(0);
                    Optional<CamundaProperty> nextStepProp = camundaProperties.getCamundaProperties().stream()
                            .filter(prop -> NEXT_STEP_VARIABLE_PROPERTY_NAME.equals(prop.getCamundaName())).findFirst();
                    if (nextStepProp.isPresent() && StringUtils.hasText(nextStepProp.get().getCamundaValue())) {
                        variableNameToSet = nextStepProp.get().getCamundaValue();
                        log.info("BPMN'den sonraki adım değişkeni bulundu: '{}'", variableNameToSet);
                    } else {
                        log.info("Task Key '{}' için '{}' property değeri boş veya yok.", taskDefinitionKey, NEXT_STEP_VARIABLE_PROPERTY_NAME);
                    }
                }
            }
        } catch (Exception e) {
            log.error("BPMN modelinden '{}' property okunurken hata (Task Key: {}): {}", NEXT_STEP_VARIABLE_PROPERTY_NAME, taskDefinitionKey, e.getMessage(), e);
            throw new IllegalStateException("Süreç ilerleme değişkeni BPMN'den okunamadı: " + e.getMessage(), e);
        }

        if (variableNameToSet != null) {
            try {
                startTime = System.currentTimeMillis();
                runtimeService.setVariable(processInstanceId, variableNameToSet, true);
                log.info("Süreç (ID: {}) için '{}' değişkeni 'true' yapıldı. Süre: {}ms", processInstanceId, variableNameToSet, (System.currentTimeMillis() - startTime));
            } catch (ProcessEngineException e) {
                log.error("Süreç (ID: {}) ilerleme değişkeni ('{}') ayarlanırken hata: {}", processInstanceId, variableNameToSet, e.getMessage(), e);
                throw new IllegalStateException("Camunda değişken ayarı hatası: " + e.getMessage(), e);
            }
        } else {
            log.info("Görev ('{}') için ayarlanacak ilerleme değişkeni yok.", taskDefinitionKey);
        }

        try {
            startTime = System.currentTimeMillis();
            taskService.complete(taskId);
            log.info("Aktif görev (Task ID: {}) başarıyla tamamlandı. Süre: {}ms", taskId, (System.currentTimeMillis() - startTime));
        } catch (ProcessEngineException e){
            log.error("Görev (Task ID: {}) tamamlanırken Camunda hatası: {}", taskId, e.getMessage(), e);
            throw new IllegalStateException("Görev (Task ID: " + taskId +") tamamlanamadı: " + e.getMessage(), e);
        }
        log.info("completeUserTaskAndPrepareNextStep metodu tamamlandı. Toplam Süre: {}ms", (System.currentTimeMillis() - methodStartTime));
    }

    @Override
    @Transactional(readOnly = true)
    public TrackingInfoResponse getTrackingInfo(String trackingNumber) {
        log.info("Takip numarası '{}' için kargo bilgisi (detaylı) sorgulanıyor.", trackingNumber);
        Cargo cargo = cargoRepository.findByTrackingNumber(trackingNumber)
                .orElseThrow(() -> new EntityNotFoundException("Takip numarası ile kargo bulunamadı: " + trackingNumber));
        List<TrackingHistoryEvent> historyEvents = getCargoHistory(cargo);
        boolean isCompletable = false;
        boolean isCancellable = cargo.getCurrentStatus() != CargoStatus.DELIVERED &&
                cargo.getCurrentStatus() != CargoStatus.CANCELLED;
        String activeTaskKey = null;
        String activeTaskUserFriendlyName = null;
        if (isCancellable && cargo.getProcessInstanceId() != null) {
            List<Task> activeTasks = taskService.createTaskQuery()
                    .processInstanceId(cargo.getProcessInstanceId())
                    .active()
                    .list();
            if (!CollectionUtils.isEmpty(activeTasks)) {
                Task taskToComplete = null;
                if (activeTasks.stream().anyMatch(t -> "userTask_PhysicalReception".equals(t.getTaskDefinitionKey()))) {
                    taskToComplete = activeTasks.stream().filter(t -> "userTask_PhysicalReception".equals(t.getTaskDefinitionKey())).findFirst().orElse(null);
                } else if (activeTasks.stream().anyMatch(t -> "userTask_InvoiceCreation".equals(t.getTaskDefinitionKey()))) {
                    taskToComplete = activeTasks.stream().filter(t -> "userTask_InvoiceCreation".equals(t.getTaskDefinitionKey())).findFirst().orElse(null);
                } else if (activeTasks.stream().anyMatch(t -> "userTask_ConfirmReceived".equals(t.getTaskDefinitionKey()))) { // ID BPMN'deki gibi userTask_ConfirmReceived ise
                    taskToComplete = activeTasks.stream().filter(t -> "userTask_ConfirmReceived".equals(t.getTaskDefinitionKey())).findFirst().orElse(null);
                } else if (!activeTasks.isEmpty()){
                    taskToComplete = activeTasks.get(0);
                }
                if (taskToComplete != null) {
                    isCompletable = true;
                    activeTaskKey = taskToComplete.getTaskDefinitionKey();
                    activeTaskUserFriendlyName = taskToComplete.getName();
                }
            }
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
                .completable(isCompletable).cancellable(isCancellable)
                .activeTaskDefinitionKey(activeTaskKey).activeTaskName(activeTaskUserFriendlyName)
                .build();
    }

    private List<TrackingHistoryEvent> getCargoHistory(Cargo cargo) {
        if (cargo.getProcessInstanceId() == null) {
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
                        String activityName = activity.getActivityName() != null ? activity.getActivityName() : "Bilinmeyen Aktivite";
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
        List<CargoStatus> tasinmaTransferDurumlari = List.of(CargoStatus.LOADED_ON_VEHICLE_1, CargoStatus.AT_TRANSFER_CENTER, CargoStatus.LOADED_ON_VEHICLE_2, CargoStatus.AT_DISTRIBUTION_HUB);
        long tasiniyorTransferde = cargoRepository.countByCurrentStatusIn(tasinmaTransferDurumlari);
        long dagitimda = cargoRepository.countByCurrentStatus(CargoStatus.OUT_FOR_DELIVERY);
        long teslimEdilen = cargoRepository.countByCurrentStatus(CargoStatus.DELIVERED);
        long iptalEdilen = cargoRepository.countByCurrentStatus(CargoStatus.CANCELLED);
        List<RecentActivityDto> recentActivities = List.of();
        try {
            List<HistoricActivityInstance> lastActivities = historyService.createHistoricActivityInstanceQuery().activityType("serviceTask").orderByHistoricActivityInstanceEndTime().desc().finished().listPage(0, 10);
            List<HistoricActivityInstance> filteredActivities = lastActivities.stream().filter(activity -> TRACKING_ACTIVITY_IDS.contains(activity.getActivityId())).collect(Collectors.toList());
            Set<String> processInstanceIds = filteredActivities.stream().map(HistoricActivityInstance::getProcessInstanceId).collect(Collectors.toSet());
            final Map<String, String> processIdToBusinessKeyMap;
            if (!processInstanceIds.isEmpty()) {
                List<HistoricProcessInstance> processInstances = historyService.createHistoricProcessInstanceQuery().processInstanceIds(processInstanceIds).list();
                processIdToBusinessKeyMap = processInstances.stream().filter(pi -> pi.getBusinessKey() != null).collect(Collectors.toMap(HistoricProcessInstance::getId, HistoricProcessInstance::getBusinessKey, (key1, key2) -> key1));
            } else {
                processIdToBusinessKeyMap = Collections.emptyMap();
            }
            recentActivities = filteredActivities.stream().map(activity -> {
                String activityName = activity.getActivityName() != null ? activity.getActivityName() : "Bilinmeyen İşlem";
                String statusDesc = extractStatusFromActivityName(activityName);
                LocalDateTime timestamp = convertDateToLocalDateTime(activity.getEndTime());
                String trackingNo = processIdToBusinessKeyMap.getOrDefault(activity.getProcessInstanceId(), "-");
                CargoStatus statusEnum = findStatusByActivityName(activityName);
                String badgeClass = getStatusBadgeClass(statusEnum);
                return new RecentActivityDto(trackingNo, statusDesc, badgeClass, timestamp);
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Son işlemler alınırken Camunda geçmiş sorgusunda hata oluştu: {}", e.getMessage(), e);
        }
        return PanelDataDto.builder().beklemedeAlinanCount(beklemedeAlinan).tasiniyorTransferdeCount(tasiniyorTransferde).dagitimdaCount(dagitimda).teslimEdilenCount(teslimEdilen).iptalEdilenCount(iptalEdilen).recentActivities(recentActivities).build();
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

    private CargoSearchResultDto mapCargoToSearchResultDto(Cargo cargo) {
        boolean isCompletable = false;
        boolean isCancellable = cargo.getCurrentStatus() != CargoStatus.DELIVERED && cargo.getCurrentStatus() != CargoStatus.CANCELLED;
        if (isCancellable && cargo.getProcessInstanceId() != null) {
            long activeTaskCount = taskService.createTaskQuery().processInstanceId(cargo.getProcessInstanceId()).active().count();
            isCompletable = activeTaskCount > 0;
        }
        return CargoSearchResultDto.builder()
                .trackingNumber(cargo.getTrackingNumber()).senderName(cargo.getSenderName())
                .receiverName(cargo.getReceiverName()).receiverCity(cargo.getReceiverCity())
                .currentStatus(getStatusDisplayName(cargo.getCurrentStatus()))
                .currentStatusBadgeClass(getStatusBadgeClass(cargo.getCurrentStatus()))
                .lastUpdatedAt(cargo.getLastUpdatedAt())
                .cancellable(isCancellable).completable(isCompletable)
                .build();
    }

    private CargoStatus findStatusByActivityName(String activityName) {
        if (activityName == null) return null;
        if (activityName.contains("Kargo Alındı")) return CargoStatus.RECEIVED;
        if (activityName.contains("İlk Araca Yüklendi")) return CargoStatus.LOADED_ON_VEHICLE_1;
        if (activityName.contains("Transfer Merkezinde")) return CargoStatus.AT_TRANSFER_CENTER;
        if (activityName.contains("Son Araca Yüklendi")) return CargoStatus.LOADED_ON_VEHICLE_2;
        if (activityName.contains("Dağıtım Bölgesinde")) return CargoStatus.AT_DISTRIBUTION_HUB;
        if (activityName.contains("Dağıtımda")) return CargoStatus.OUT_FOR_DELIVERY;
        if (activityName.contains("Teslim Edildi")) return CargoStatus.DELIVERED;
        if (activityName.contains("İptal Edildi")) return CargoStatus.CANCELLED;
        return null;
    }
    private String extractStatusFromActivityName(String activityName) {
        if (activityName == null) return "Bilinmeyen Durum";
        if (activityName.contains(":")) { return activityName.substring(activityName.indexOf(":") + 1).trim(); }
        if (activityName.startsWith("Tamamla:")) { return activityName.substring(activityName.indexOf(":") + 1).trim() + " Tamamlandı"; }
        return activityName;
    }
    private String extractLocationFromActivityName(String activityName) {
        if (activityName != null) {
            if (activityName.contains("Transfer Merkezi")) return "Transfer Merkezi";
            if (activityName.contains("Dağıtım Bölgesi")) return "Dağıtım Bölgesi";
            if (activityName.contains("İlk Araca Yüklendi")) return "Kalkış Noktası";
            if (activityName.contains("Son Araca Yüklendi")) return "Transfer Noktası";
            if (activityName.contains("Dağıtımda")) return "Varış Bölgesi";
            if (activityName.contains("Teslim Edildi")) return "Teslimat Adresi";
            if (activityName.contains("Kargo Alındı")) return "Gönderici Şube";
            if (activityName.contains("İptal Edildi")) return "İşlem Merkezi";
        }
        return "-";
    }
    private LocalDateTime convertDateToLocalDateTime(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
    private String getStatusDisplayName(CargoStatus status) {
        if (status == null) return "Bilinmiyor";
        return switch (status) {
            case PENDING -> "Beklemede"; case RECEIVED -> "Kargo Alındı";
            case LOADED_ON_VEHICLE_1 -> "İlk Araca Yüklendi"; case AT_TRANSFER_CENTER -> "Transfer Merkezinde";
            case LOADED_ON_VEHICLE_2 -> "Son Araca Yüklendi"; case AT_DISTRIBUTION_HUB -> "Dağıtım Bölgesinde";
            case OUT_FOR_DELIVERY -> "Dağıtımda"; case DELIVERED -> "Teslim Edildi";
            case CANCELLED -> "İptal Edildi"; default -> status.name();
        };
    }
    private String getStatusBadgeClass(CargoStatus status) {
        if (status == null) return "bg-secondary";
        return switch (status) {
            case PENDING, RECEIVED -> "bg-secondary";
            case LOADED_ON_VEHICLE_1, AT_TRANSFER_CENTER, LOADED_ON_VEHICLE_2, AT_DISTRIBUTION_HUB -> "bg-primary";
            case OUT_FOR_DELIVERY -> "bg-info text-dark";
            case DELIVERED -> "bg-success"; case CANCELLED -> "bg-danger";
            default -> "bg-dark";
        };
    }
    private String generateUniqueTrackingNumber() {
        for (int i = 0; i < MAX_TRACKING_NUMBER_ATTEMPTS; i++) {
            String prefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHH"));
            long randomSuffix = ThreadLocalRandom.current().nextLong(10000, 100000);
            String trackingNumber = prefix + randomSuffix;
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