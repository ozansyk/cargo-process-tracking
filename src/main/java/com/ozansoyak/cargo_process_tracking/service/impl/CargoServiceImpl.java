package com.ozansoyak.cargo_process_tracking.service.impl;

import com.ozansoyak.cargo_process_tracking.dto.*;
import com.ozansoyak.cargo_process_tracking.exception.TrackingNumberGenerationException;
import com.ozansoyak.cargo_process_tracking.model.Cargo;
import com.ozansoyak.cargo_process_tracking.model.enums.CargoStatus;
import com.ozansoyak.cargo_process_tracking.repository.CargoRepository;
import com.ozansoyak.cargo_process_tracking.service.CargoService;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.*;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.history.HistoricProcessInstance; // Business Key için eklendi
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
// Camunda Model API importları
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.UserTask;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperties;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperty;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final RepositoryService repositoryService; // Model API için eklendi
    private final HistoryService historyService;

    private static final String CAMUNDA_PROCESS_DEFINITION_KEY = "cargoTrackingProcessV2";
    private static final int MAX_TRACKING_NUMBER_ATTEMPTS = 10;
    private static final String NEXT_STEP_VARIABLE_PROPERTY_NAME = "nextStepVariable"; // User Task Extension Property adı

    // Geçmişte göstermek istediğimiz Service Task ID'leri (Java'da filtrelemek için)
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

    @Override
    @Transactional
    public CargoResponse createCargoAndStartProcess(CreateCargoRequest request) {
        // Sizin sağladığınız kodla aynı
        log.info("Yeni kargo oluşturma ve '{}' süreci başlatma isteği.", CAMUNDA_PROCESS_DEFINITION_KEY);
        String trackingNumber = generateUniqueTrackingNumber();
        log.debug("Takip numarası üretildi: {}", trackingNumber);
        Cargo cargo = Cargo.builder()
                .trackingNumber(trackingNumber)
                .senderName(request.getSenderName())
                .senderAddress(request.getSenderAddress())
                .senderCity(request.getSenderCity())
                .senderPhone(request.getSenderPhone())
                .senderEmail(request.getSenderEmail())
                .receiverName(request.getReceiverName())
                .receiverAddress(request.getReceiverAddress())
                .receiverCity(request.getReceiverCity())
                .receiverPhone(request.getReceiverPhone())
                .receiverEmail(request.getReceiverEmail())
                .weight(request.getWeight())
                .dimensions(request.getDimensions())
                .contentDescription(request.getContentDescription())
                .currentStatus(CargoStatus.PENDING)
                .build();
        Cargo savedCargo = cargoRepository.save(cargo);
        log.info("Kargo veritabanına kaydedildi. ID: {}", savedCargo.getId());
        Map<String, Object> processVariables = new HashMap<>();
        String businessKey = savedCargo.getTrackingNumber();
        processVariables.put("cargoId", savedCargo.getId());
        processVariables.put("trackingNumber", savedCargo.getTrackingNumber());
        processVariables.put("isCancelled", false);
        ProcessInstance processInstance;
        try {
            processInstance = runtimeService.startProcessInstanceByKey(
                    CAMUNDA_PROCESS_DEFINITION_KEY,
                    businessKey,
                    processVariables
            );
            log.info("Camunda süreci başlatıldı. Process Instance ID: {}, Business Key: {}",
                    processInstance.getProcessInstanceId(), businessKey);
        } catch (Exception e) {
            log.error("Camunda süreci başlatılamadı (key={}): {}", CAMUNDA_PROCESS_DEFINITION_KEY, e.getMessage(), e);
            throw new RuntimeException("Kargo süreci başlatılamadı: " + e.getMessage(), e);
        }
        savedCargo.setProcessInstanceId(processInstance.getProcessInstanceId());
        cargoRepository.save(savedCargo);
        return new CargoResponse(
                savedCargo.getId(),
                savedCargo.getTrackingNumber(),
                savedCargo.getCurrentStatus().name(),
                processInstance.getProcessInstanceId()
        );
    }

    @Override
    @Transactional
    public void cancelCargoProcess(String trackingNumber) {
        // Sizin sağladığınız kodla aynı
        log.info("{} takip numaralı kargo için iptal işlemi başlatıldı.", trackingNumber);
        Cargo cargo = cargoRepository.findByTrackingNumber(trackingNumber)
                .orElseThrow(() -> {
                    log.warn("İptal işlemi: Takip numarası ({}) ile kargo bulunamadı.", trackingNumber);
                    return new EntityNotFoundException("Takip numarası ile kargo bulunamadı: " + trackingNumber);
                });
        if (cargo.getCurrentStatus() == CargoStatus.CANCELLED || cargo.getCurrentStatus() == CargoStatus.DELIVERED) {
            log.info("Kargo (ID: {}) zaten '{}' durumunda. İptal işlemi atlanıyor.", cargo.getId(), cargo.getCurrentStatus());
            return;
        }
        String processInstanceId = cargo.getProcessInstanceId();
        if (processInstanceId == null) {
            log.warn("İptal işlemi: Kargo (ID: {}) için Camunda Process Instance ID bulunamadı. Durum manuel CANCELLED yapılıyor.", cargo.getId());
            cargo.setCurrentStatus(CargoStatus.CANCELLED);
            cargoRepository.save(cargo);
            return;
        }
        ProcessInstance processInstance = null;
        try {
            processInstance = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .active()
                    .singleResult();
        } catch (org.camunda.bpm.engine.exception.NullValueException | org.camunda.bpm.engine.exception.NotFoundException e) {
            log.warn("İptal işlemi: Aktif Camunda süreci bulunamadı veya süreç zaten bitmiş (ID: {}). Kargo durumu: {}", processInstanceId, cargo.getCurrentStatus());
            if (cargo.getCurrentStatus() != CargoStatus.CANCELLED && cargo.getCurrentStatus() != CargoStatus.DELIVERED) {
                log.warn("Süreç bitmiş/bulunamadı ama kargo durumu (ID: {}) '{}'. Manuel olarak CANCELLED yapılıyor.", cargo.getId(), cargo.getCurrentStatus());
                cargo.setCurrentStatus(CargoStatus.CANCELLED);
                cargoRepository.save(cargo);
            }
            return;
        } catch(ProcessEngineException pee) {
            log.error("Camunda process instance sorgulanırken hata (ID: {}): {}", processInstanceId, pee.getMessage(), pee);
            throw new RuntimeException("Süreç durumu sorgulanırken Camunda hatası: " + pee.getMessage(), pee);
        }
        try {
            log.info("Aktif Camunda süreci (ID: {}) bulunuyor. İptal değişkeni ayarlanacak.", processInstanceId);
            Object currentCancelVar = runtimeService.getVariable(processInstanceId, "isCancelled");
            if (currentCancelVar == null || !Boolean.TRUE.equals(currentCancelVar)) {
                runtimeService.setVariable(processInstanceId, "isCancelled", true);
                log.info("Camunda süreci (ID: {}) için 'isCancelled' değişkeni 'true' olarak ayarlandı.", processInstanceId);
            } else {
                log.info("Camunda süreci (ID: {}) için 'isCancelled' değişkeni zaten 'true' idi.", processInstanceId);
            }
        } catch (ProcessEngineException e) {
            log.error("Camunda süreci (ID: {}) iptal değişkeni ayarlanırken hata: {}", processInstanceId, e.getMessage(), e);
            throw new RuntimeException("Süreç iptal edilirken Camunda değişkeni ayarlanamadı: " + e.getMessage(), e);
        }
        Task activeTask = null;
        try {
            activeTask = taskService.createTaskQuery()
                    .processInstanceId(processInstanceId)
                    .active()
                    .singleResult();
        } catch (org.camunda.bpm.engine.exception.NullValueException | org.camunda.bpm.engine.exception.NotFoundException e) {
            log.info("İptal işlemi sırasında tamamlanacak aktif kullanıcı görevi bulunamadı. Süreç ilerleyince iptal yoluna girecek.");
        } catch(ProcessEngineException e) {
            log.error("İptal işlemi sırasında aktif görev sorgulanırken hata oluştu (Process Instance ID: {}): {}. Değişken ayarlandı, ancak görev tamamlanamayabilir.", processInstanceId, e.getMessage(), e);
        }
        if (activeTask != null) {
            log.info("Süreç iptal edildiği için aktif görev (Task ID: {}, Task Key: {}) programatik olarak tamamlanıyor.", activeTask.getId(), activeTask.getTaskDefinitionKey());
            try {
                taskService.complete(activeTask.getId());
                log.info("Aktif görev (Task ID: {}) iptal nedeniyle başarıyla tamamlandı.", activeTask.getId());
            } catch (ProcessEngineException e){
                log.error("İptal işlemi sırasında aktif görev (Task ID: {}) tamamlanırken hata: {}. Süreç yine de iptal yoluna girmeli (isCancelled=true).", activeTask.getId(), e.getMessage(), e);
            }
        }
    }


    @Override
    @Transactional
    public void completeUserTaskAndPrepareNextStep(String trackingNumber) {
        // Sizin sağladığınız kodla aynı
        log.info("{} takip numaralı kargo için aktif görevi tamamlama ve sonraki adımı hazırlama isteği.", trackingNumber);
        Cargo cargo = cargoRepository.findByTrackingNumber(trackingNumber)
                .orElseThrow(() -> new EntityNotFoundException("Takip numarası ile kargo bulunamadı: " + trackingNumber));
        if (cargo.getCurrentStatus() == CargoStatus.CANCELLED || cargo.getCurrentStatus() == CargoStatus.DELIVERED) {
            log.warn("complete-step: Kargo (Takip No: {}) zaten '{}' durumunda. Görev tamamlanamaz.", trackingNumber, cargo.getCurrentStatus());
            throw new IllegalStateException("Kargo zaten " + cargo.getCurrentStatus() + " durumunda olduğu için işlem yapılamaz.");
        }
        String processInstanceId = cargo.getProcessInstanceId();
        if (processInstanceId == null) {
            log.error("Tutarsız durum: Kargo (ID: {}) PENDING değil ama processInstanceId null.", cargo.getId());
            throw new IllegalStateException("Kargo (Takip No: " + trackingNumber + ") için süreç ID'si bulunamadı, veri tutarsızlığı olabilir.");
        }
        ProcessInstance processInstance = null;
        Task activeTask = null;
        try {
            processInstance = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .active()
                    .singleResult();
            activeTask = taskService.createTaskQuery()
                    .processInstanceId(processInstanceId)
                    .active()
                    .singleResult();
        } catch (org.camunda.bpm.engine.exception.NullValueException | org.camunda.bpm.engine.exception.NotFoundException e) {
            log.warn("complete-step: Aktif süreç veya görev bulunamadı (PI ID: {}). Süreç bitmiş veya beklenmedik bir durumda olabilir.", processInstanceId, e);
            throw new EntityNotFoundException("Bu kargo (Takip No: " + trackingNumber + ") için şu anda tamamlanacak aktif bir görev bulunmuyor veya süreç aktif değil.");
        } catch (ProcessEngineException pee) {
            log.error("complete-step: Aktif süreç veya görev sorgulanırken Camunda hatası (PI ID: {}): {}", processInstanceId, pee.getMessage(), pee);
            throw new RuntimeException("Aktif süreç/görev durumu sorgulanamadı: " + pee.getMessage(), pee);
        }
        String taskId = activeTask.getId();
        String taskDefinitionKey = activeTask.getTaskDefinitionKey();
        String processDefinitionId = activeTask.getProcessDefinitionId();
        log.info("Aktif görev bulundu: Task ID: {}, Task Key: {}, Process Definition ID: {}", taskId, taskDefinitionKey, processDefinitionId);
        String variableNameToSet = null;
        try {
            BpmnModelInstance modelInstance = repositoryService.getBpmnModelInstance(processDefinitionId);
            if (modelInstance == null) {
                log.error("Kritik Hata: Process definition ID '{}' için BPMN modeli RepositoryService tarafından bulunamadı.", processDefinitionId);
                throw new IllegalStateException("BPMN modeli bulunamadı: " + processDefinitionId);
            }
            UserTask userTaskElement = modelInstance.getModelElementById(taskDefinitionKey);
            if (userTaskElement == null) {
                log.error("Konfigürasyon Hatası: BPMN modelinde task definition key '{}' (ID: {}) bulunamadı.", taskDefinitionKey, processDefinitionId);
                throw new IllegalStateException("BPMN modelinde görev tanımı bulunamadı: " + taskDefinitionKey);
            }
            ExtensionElements extensionElements = userTaskElement.getExtensionElements();
            if (extensionElements != null) {
                List<CamundaProperties> propertiesList = extensionElements.getElementsQuery()
                        .filterByType(CamundaProperties.class)
                        .list();
                if (!propertiesList.isEmpty()) {
                    if (propertiesList.size() > 1) {
                        log.warn("Task Key '{}' için birden fazla <camunda:properties> bulundu. İlki kullanılacak.", taskDefinitionKey);
                    }
                    CamundaProperties camundaProperties = propertiesList.get(0);
                    Collection<CamundaProperty> propsCollection = camundaProperties.getCamundaProperties();
                    Optional<CamundaProperty> nextStepVariableProp = propsCollection.stream()
                            .filter(prop -> NEXT_STEP_VARIABLE_PROPERTY_NAME.equals(prop.getCamundaName()))
                            .findFirst();
                    if (nextStepVariableProp.isPresent()) {
                        variableNameToSet = nextStepVariableProp.get().getCamundaValue();
                        if (variableNameToSet == null || variableNameToSet.isBlank()) {
                            log.warn("Task Key '{}' için '{}' property değeri BPMN'de boş tanımlanmış.", taskDefinitionKey, NEXT_STEP_VARIABLE_PROPERTY_NAME);
                            variableNameToSet = null;
                        } else {
                            log.info("BPMN'den bir sonraki adım için ayarlanacak değişken bulundu: '{}'", variableNameToSet);
                        }
                    } else {
                        log.info("Task Key '{}' için '{}' property bulunmadı. Bu, sürecin sonuna yaklaşıldığını gösterebilir.", taskDefinitionKey, NEXT_STEP_VARIABLE_PROPERTY_NAME);
                    }
                } else {
                    log.info("Task Key '{}' için <camunda:properties> elementi bulunamadı.", taskDefinitionKey);
                }
            } else {
                log.info("Task Key '{}' için ExtensionElements bulunamadı.", taskDefinitionKey);
            }
        } catch (Exception e) {
            log.error("BPMN modelinden '{}' property okunurken hata oluştu (Task Key: {}): {}",
                    NEXT_STEP_VARIABLE_PROPERTY_NAME, taskDefinitionKey, e.getMessage(), e);
            throw new IllegalStateException("Süreç ilerleme değişkeni BPMN'den okunamadı: " + e.getMessage(), e);
        }
        if (variableNameToSet != null) {
            try {
                runtimeService.setVariable(processInstanceId, variableNameToSet, true);
                log.info("Camunda süreci (ID: {}) için '{}' değişkeni 'true' olarak ayarlandı.", processInstanceId, variableNameToSet);
            } catch (ProcessEngineException e) {
                log.error("Camunda süreci (ID: {}) ilerleme değişkeni ('{}') ayarlanırken hata: {}",
                        processInstanceId, variableNameToSet, e.getMessage(), e);
                throw new IllegalStateException("Süreç ilerleme onayı verilirken Camunda hatası: " + e.getMessage(), e);
            }
        } else {
            log.info("Bu görev ('{}') için ayarlanacak bir sonraki adım değişkeni bulunmadığından değişken ayarlanmadı.", taskDefinitionKey);
        }
        try {
            taskService.complete(taskId);
            log.info("Aktif görev (Task ID: {}) başarıyla tamamlandı. Sürecin ilerlemesi bekleniyor.", taskId);
        } catch (ProcessEngineException e){
            log.error("Görev (Task ID: {}) tamamlanırken Camunda hatası: {}", taskId, e.getMessage(), e);
            throw new IllegalStateException("Görev (Task ID: " + taskId +") tamamlanamadı: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Görev (Task ID: {}) tamamlanırken beklenmedik hata: {}", taskId, e.getMessage(), e);
            throw new RuntimeException("Görev tamamlanırken beklenmedik hata: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public TrackingInfoResponse getTrackingInfo(String trackingNumber) {
        // Sizin sağladığınız kodla aynı (Java'da filtreleme yapan)
        log.info("Takip numarası '{}' için kargo bilgisi sorgulanıyor.", trackingNumber);
        Cargo cargo = cargoRepository.findByTrackingNumber(trackingNumber)
                .orElseThrow(() -> {
                    log.warn("Takip numarası '{}' ile kargo bulunamadı.", trackingNumber);
                    return new EntityNotFoundException("Takip numarası ile kargo bulunamadı: " + trackingNumber);
                });
        log.debug("Kargo bulundu: ID={}, Durum={}, PI_ID={}", cargo.getId(), cargo.getCurrentStatus(), cargo.getProcessInstanceId());
        List<TrackingHistoryEvent> historyEvents = List.of();
        if (cargo.getProcessInstanceId() != null) {
            try {
                List<HistoricActivityInstance> activityInstances = historyService.createHistoricActivityInstanceQuery()
                        .processInstanceId(cargo.getProcessInstanceId())
                        .activityType("serviceTask")
                        .orderByHistoricActivityInstanceStartTime().asc()
                        .list();
                log.debug("Takip numarası '{}' için {} adet Service Task geçmiş aktivitesi bulundu (Filtrelemeden önce).", trackingNumber, activityInstances.size());
                historyEvents = activityInstances.stream()
                        .filter(activity -> TRACKING_ACTIVITY_IDS.contains(activity.getActivityId()))
                        .map(activity -> {
                            String activityName = activity.getActivityName() != null ? activity.getActivityName() : "Bilinmeyen Aktivite";
                            String statusDesc = extractStatusFromActivityName(activityName);
                            String location = extractLocationFromActivityName(activityName);
                            LocalDateTime timestamp = convertDateToLocalDateTime(activity.getStartTime());
                            return new TrackingHistoryEvent(timestamp, statusDesc, location);
                        })
                        .collect(Collectors.toList());
                log.debug("Takip numarası '{}' için {} adet ilgili geçmiş aktivite bulundu (Filtrelemeden SONRA).", trackingNumber, historyEvents.size());
            } catch (Exception e) {
                log.error("Takip numarası '{}', PI_ID '{}' için Camunda geçmişi alınırken hata: {}",
                        trackingNumber, cargo.getProcessInstanceId(), e.getMessage(), e);
            }
        } else {
            log.warn("Takip numarası '{}' için süreç ID'si bulunamadığından geçmiş bilgisi alınamadı.", trackingNumber);
        }
        return TrackingInfoResponse.builder()
                .trackingNumber(cargo.getTrackingNumber())
                .currentStatus(getStatusDisplayName(cargo.getCurrentStatus()))
                .currentStatusBadgeClass(getStatusBadgeClass(cargo.getCurrentStatus()))
                .senderCity(cargo.getSenderCity())
                .receiverCity(cargo.getReceiverCity())
                .processInstanceId(cargo.getProcessInstanceId())
                .historyEvents(historyEvents)
                .found(true)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public PanelDataDto getPanelData() {
        log.debug("Panel verileri sorgulanıyor...");

        // 1. Widget Sayılarını Hesapla
        long beklemedeAlinan = cargoRepository.countByCurrentStatusIn(
                List.of(CargoStatus.PENDING, CargoStatus.RECEIVED)
        );
        long dagitimda = cargoRepository.countByCurrentStatus(CargoStatus.OUT_FOR_DELIVERY);
        long teslimEdilen = cargoRepository.countByCurrentStatus(CargoStatus.DELIVERED);
        long iptalEdilen = cargoRepository.countByCurrentStatus(CargoStatus.CANCELLED);

        // 2. Son İşlemleri Camunda Geçmişinden Al
        List<RecentActivityDto> recentActivities = List.of();
        try {
            // Son 10 bitmiş service task aktivitesini al
            List<HistoricActivityInstance> lastActivities = historyService.createHistoricActivityInstanceQuery()
                    .activityType("serviceTask")
                    .orderByHistoricActivityInstanceEndTime().desc()
                    .finished()
                    // --- DÜZELTME 1: listPage kullanımı ---
                    .listPage(0, 10); // İlk 10 sonuç

            log.debug("{} adet geçmiş bitmiş Service Task aktivitesi bulundu (Filtrelemeden önce).", lastActivities.size());

            // Aktivite ID'lerine göre filtrele
            List<HistoricActivityInstance> filteredActivities = lastActivities.stream()
                    .filter(activity -> TRACKING_ACTIVITY_IDS.contains(activity.getActivityId()))
                    .collect(Collectors.toList());

            // --- DÜZELTME 2: Business Key'leri toplu al ---
            Set<String> processInstanceIds = filteredActivities.stream()
                    .map(HistoricActivityInstance::getProcessInstanceId)
                    .collect(Collectors.toSet());

            // --- DÜZELTME 3: Map'i final yap ---
            final Map<String, String> processIdToBusinessKeyMap; // final olarak bildir
            if (!processInstanceIds.isEmpty()) {
                List<HistoricProcessInstance> processInstances = historyService.createHistoricProcessInstanceQuery()
                        .processInstanceIds(processInstanceIds) // Set ile sorgula
                        .list();
                processIdToBusinessKeyMap = processInstances.stream()
                        .filter(pi -> pi.getBusinessKey() != null) // Business Key null olmayanları al
                        .collect(Collectors.toMap(
                                HistoricProcessInstance::getId,
                                HistoricProcessInstance::getBusinessKey,
                                (key1, key2) -> key1 // Aynı ID gelirse ilkini koru (normalde olmaz)
                        ));
                log.debug("{} adet process instance için business key bulundu.", processIdToBusinessKeyMap.size());
            } else {
                // processIdToBusinessKeyMap'e boş map ata
                processIdToBusinessKeyMap = Collections.emptyMap();
            }
            // -----------------------------------

            recentActivities = filteredActivities.stream()
                    .map(activity -> {
                        String activityName = activity.getActivityName() != null ? activity.getActivityName() : "Bilinmeyen İşlem";
                        String statusDesc = extractStatusFromActivityName(activityName);
                        LocalDateTime timestamp = convertDateToLocalDateTime(activity.getEndTime());

                        // Lambda içinde artık final olan map'i kullanabiliriz
                        String trackingNo = processIdToBusinessKeyMap.getOrDefault(activity.getProcessInstanceId(), "-");

                        CargoStatus statusEnum = findStatusByActivityName(activityName);
                        String badgeClass = getStatusBadgeClass(statusEnum);

                        return new RecentActivityDto(trackingNo, statusDesc, badgeClass, timestamp);
                    })
                    .collect(Collectors.toList());

            log.debug("{} adet ilgili son işlem bulundu (Filtrelemeden SONRA).", recentActivities.size());

        } catch (Exception e) {
            log.error("Son işlemler alınırken Camunda geçmiş sorgusunda hata oluştu: {}", e.getMessage(), e);
        }

        // 3. Panel DTO'sunu Oluştur ve Döndür
        return PanelDataDto.builder()
                .beklemedeAlinanCount(beklemedeAlinan)
                .dagitimdaCount(dagitimda)
                .teslimEdilenCount(teslimEdilen)
                .iptalEdilenCount(iptalEdilen)
                .recentActivities(recentActivities)
                .build();
    }
    // --- Yardımcı Metotlar (Sizin Kodunuzla Aynı) ---

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
        if (activityName.contains(":")) {
            return activityName.substring(activityName.indexOf(":") + 1).trim();
        }
        if (activityName.startsWith("Tamamla:")) {
            return activityName.substring(activityName.indexOf(":") + 1).trim() + " Tamamlandı";
        }
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
        return date == null ? null : date.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    private String getStatusDisplayName(CargoStatus status) {
        if (status == null) return "Bilinmiyor";
        return switch (status) {
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
    }

    private String getStatusBadgeClass(CargoStatus status) {
        if (status == null) return "bg-secondary";
        return switch (status) {
            case PENDING, RECEIVED -> "bg-secondary";
            case LOADED_ON_VEHICLE_1, AT_TRANSFER_CENTER, LOADED_ON_VEHICLE_2, AT_DISTRIBUTION_HUB -> "bg-primary";
            case OUT_FOR_DELIVERY -> "bg-info";
            case DELIVERED -> "bg-success";
            case CANCELLED -> "bg-danger";
            default -> "bg-dark";
        };
    }

    private String generateUniqueTrackingNumber() {
        // Sizin sağladığınız kodla aynı
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