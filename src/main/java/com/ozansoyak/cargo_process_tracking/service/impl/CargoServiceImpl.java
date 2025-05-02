package com.ozansoyak.cargo_process_tracking.service.impl;

import com.ozansoyak.cargo_process_tracking.dto.CreateCargoRequest;
import com.ozansoyak.cargo_process_tracking.dto.CargoResponse;
import com.ozansoyak.cargo_process_tracking.exception.TrackingNumberGenerationException;
import com.ozansoyak.cargo_process_tracking.model.Cargo;
import com.ozansoyak.cargo_process_tracking.model.enums.CargoStatus;
import com.ozansoyak.cargo_process_tracking.repository.CargoRepository;
import com.ozansoyak.cargo_process_tracking.service.CargoService;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RepositoryService; // Model API'ye erişim için eklendi
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
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

import java.util.Collection; // CamundaProperties.getCamundaProperties() için
import java.util.HashMap;
import java.util.List;      // Query.list() için
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class CargoServiceImpl implements CargoService {

    private final CargoRepository cargoRepository;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final RepositoryService repositoryService; // Model API için eklendi

    private static final String CAMUNDA_PROCESS_DEFINITION_KEY = "cargoTrackingProcessConditionalAndUserTaskAfterV2";
    private static final int MAX_TRACKING_NUMBER_ATTEMPTS = 10;
    private static final String NEXT_STEP_VARIABLE_PROPERTY_NAME = "nextStepVariable"; // User Task Extension Property adı

    @Override
    @Transactional
    public CargoResponse createCargoAndStartProcess(CreateCargoRequest request) {
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
                .currentStatus(CargoStatus.PENDING) // Başlangıç durumu PENDING
                .build();

        Cargo savedCargo = cargoRepository.save(cargo);
        log.info("Kargo veritabanına kaydedildi. ID: {}", savedCargo.getId());

        Map<String, Object> processVariables = new HashMap<>();
        String businessKey = savedCargo.getTrackingNumber();
        processVariables.put("cargoId", savedCargo.getId());
        processVariables.put("trackingNumber", savedCargo.getTrackingNumber());
        processVariables.put("isCancelled", false); // İptal bayrağı başlangıçta false

        // İlerleme değişkenleri (canProceedToX) başlangıçta set edilmiyor.
        // Gateway'lerdeki koşul ${canProceedToX == true} şeklinde olduğu için,
        // null olan değişkenler false olarak değerlendirilecektir.

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
            // Kritik hata, işlemi geri almalıyız (Transactional sayesinde otomatik)
            throw new RuntimeException("Kargo süreci başlatılamadı: " + e.getMessage(), e);
        }

        // Süreç ID'sini kargoya kaydet
        savedCargo.setProcessInstanceId(processInstance.getProcessInstanceId());
        cargoRepository.save(savedCargo); // Process ID'si ile tekrar kaydet

        // Başlangıç durumu PENDING dönüyoruz, süreç ilerledikçe güncellenecek.
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
        log.info("{} takip numaralı kargo için iptal işlemi başlatıldı.", trackingNumber);

        // 1. Kargoyu Bul
        Cargo cargo = cargoRepository.findByTrackingNumber(trackingNumber)
                .orElseThrow(() -> {
                    log.warn("İptal işlemi: Takip numarası ({}) ile kargo bulunamadı.", trackingNumber);
                    return new EntityNotFoundException("Takip numarası ile kargo bulunamadı: " + trackingNumber);
                });

        // 2. İptal Edilemez Durumları Kontrol Et
        if (cargo.getCurrentStatus() == CargoStatus.CANCELLED || cargo.getCurrentStatus() == CargoStatus.DELIVERED) {
            log.info("Kargo (ID: {}) zaten '{}' durumunda. İptal işlemi atlanıyor.", cargo.getId(), cargo.getCurrentStatus());
            return; // Idempotency: Zaten bitmişse tekrar işlem yapma.
        }

        // 3. Camunda Sürecini Yönet
        String processInstanceId = cargo.getProcessInstanceId();
        if (processInstanceId == null) {
            // Süreç ID'si yoksa (başlamamış/hata), manuel iptal et.
            log.warn("İptal işlemi: Kargo (ID: {}) için Camunda Process Instance ID bulunamadı. Durum manuel CANCELLED yapılıyor.", cargo.getId());
            cargo.setCurrentStatus(CargoStatus.CANCELLED);
            cargoRepository.save(cargo);
            return;
        }

        ProcessInstance processInstance = null;
        try {
            // Aktif süreci bulmaya çalış
            processInstance = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .active()
                    .singleResult(); // Aktif süreç yoksa hata fırlatır
        } catch (org.camunda.bpm.engine.exception.NullValueException | org.camunda.bpm.engine.exception.NotFoundException e) {
            // Süreç bulunamadı veya aktif değil (bitmiş olabilir).
            log.warn("İptal işlemi: Aktif Camunda süreci bulunamadı veya süreç zaten bitmiş (ID: {}). Kargo durumu: {}", processInstanceId, cargo.getCurrentStatus());
            // Kargonun durumu hala bitmemişse (CANCELLED/DELIVERED değilse), manuel iptal et.
            if (cargo.getCurrentStatus() != CargoStatus.CANCELLED && cargo.getCurrentStatus() != CargoStatus.DELIVERED) {
                log.warn("Süreç bitmiş/bulunamadı ama kargo durumu (ID: {}) '{}'. Manuel olarak CANCELLED yapılıyor.", cargo.getId(), cargo.getCurrentStatus());
                cargo.setCurrentStatus(CargoStatus.CANCELLED);
                cargoRepository.save(cargo);
            }
            return; // Süreç yoksa veya bitmişse daha fazla işlem yapma.
        } catch(ProcessEngineException pee) {
            // Diğer Camunda sorgu hataları
            log.error("Camunda process instance sorgulanırken hata (ID: {}): {}", processInstanceId, pee.getMessage(), pee);
            throw new RuntimeException("Süreç durumu sorgulanırken Camunda hatası: " + pee.getMessage(), pee);
        }

        // 4. Camunda 'isCancelled' Değişkenini Ayarla (Eğer henüz false ise)
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
            // Değişken ayarlanamazsa iptal işlemi başarısız olur.
            throw new RuntimeException("Süreç iptal edilirken Camunda değişkeni ayarlanamadı: " + e.getMessage(), e);
        }

        // 5. Bekleyen Aktif User Task Varsa Tamamla (Sürecin Gateway'e gitmesini hızlandırmak için)
        Task activeTask = null;
        try {
            // Sürece ait aktif user task'ı bulmaya çalış (varsa)
            activeTask = taskService.createTaskQuery()
                    .processInstanceId(processInstanceId)
                    .active()
                    .singleResult(); // Aktif task yoksa hata fırlatır
        } catch (org.camunda.bpm.engine.exception.NullValueException | org.camunda.bpm.engine.exception.NotFoundException e) {
            // Aktif user task yoksa (gateway'de vs. bekliyorsa), sorun değil.
            log.info("İptal işlemi sırasında tamamlanacak aktif kullanıcı görevi bulunamadı. Süreç ilerleyince iptal yoluna girecek.");
        } catch(ProcessEngineException e) {
            // Görev sorgularken başka bir hata olursa logla, ama devam etmeye çalışabiliriz.
            log.error("İptal işlemi sırasında aktif görev sorgulanırken hata oluştu (Process Instance ID: {}): {}. Değişken ayarlandı, ancak görev tamamlanamayabilir.", processInstanceId, e.getMessage(), e);
        }

        if (activeTask != null) {
            log.info("Süreç iptal edildiği için aktif görev (Task ID: {}, Task Key: {}) programatik olarak tamamlanıyor.", activeTask.getId(), activeTask.getTaskDefinitionKey());
            try {
                taskService.complete(activeTask.getId());
                log.info("Aktif görev (Task ID: {}) iptal nedeniyle başarıyla tamamlandı.", activeTask.getId());
            } catch (ProcessEngineException e){
                // Görev tamamlama başarısız olursa (örn. başka biri tamamladıysa, kilitliyse vs.) logla.
                log.error("İptal işlemi sırasında aktif görev (Task ID: {}) tamamlanırken hata: {}. Süreç yine de iptal yoluna girmeli (isCancelled=true).", activeTask.getId(), e.getMessage(), e);
                // Bu durumda hata fırlatmak yerine devam edebiliriz, isCancelled=true ayarlandı.
            }
        }
        // İptal işlemi tetiklendi. Kargonun CANCELLED olması asenkron olarak service task tarafından yapılacak.
    }


    @Override
    @Transactional
    public void completeUserTaskAndPrepareNextStep(String trackingNumber) {
        log.info("{} takip numaralı kargo için aktif görevi tamamlama ve sonraki adımı hazırlama isteği.", trackingNumber);

        // 1. Kargoyu Bul
        Cargo cargo = cargoRepository.findByTrackingNumber(trackingNumber)
                .orElseThrow(() -> new EntityNotFoundException("Takip numarası ile kargo bulunamadı: " + trackingNumber));

        // 2. Son Durumları Kontrol Et
        if (cargo.getCurrentStatus() == CargoStatus.CANCELLED || cargo.getCurrentStatus() == CargoStatus.DELIVERED) {
            log.warn("complete-step: Kargo (Takip No: {}) zaten '{}' durumunda. Görev tamamlanamaz.", trackingNumber, cargo.getCurrentStatus());
            throw new IllegalStateException("Kargo zaten " + cargo.getCurrentStatus() + " durumunda olduğu için işlem yapılamaz.");
        }

        // 3. Aktif Süreci ve Görevi Bul
        String processInstanceId = cargo.getProcessInstanceId();
        if (processInstanceId == null) {
            // Bu durum normalde olmamalı (eğer kargo PENDING değilse)
            log.error("Tutarsız durum: Kargo (ID: {}) PENDING değil ama processInstanceId null.", cargo.getId());
            throw new IllegalStateException("Kargo (Takip No: " + trackingNumber + ") için süreç ID'si bulunamadı, veri tutarsızlığı olabilir.");
        }

        ProcessInstance processInstance = null;
        Task activeTask = null;
        try {
            processInstance = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .active()
                    .singleResult(); // Aktif süreç yoksa hata fırlatır

            activeTask = taskService.createTaskQuery()
                    .processInstanceId(processInstanceId)
                    .active()
                    .singleResult(); // Aktif task yoksa hata fırlatır

        } catch (org.camunda.bpm.engine.exception.NullValueException | org.camunda.bpm.engine.exception.NotFoundException e) {
            // Ya aktif süreç ya da aktif görev bulunamadı.
            log.warn("complete-step: Aktif süreç veya görev bulunamadı (PI ID: {}). Süreç bitmiş veya beklenmedik bir durumda olabilir.", processInstanceId, e);
            // Kullanıcıya daha net bir mesaj verelim.
            throw new EntityNotFoundException("Bu kargo (Takip No: " + trackingNumber + ") için şu anda tamamlanacak aktif bir görev bulunmuyor veya süreç aktif değil.");
        } catch (ProcessEngineException pee) {
            log.error("complete-step: Aktif süreç veya görev sorgulanırken Camunda hatası (PI ID: {}): {}", processInstanceId, pee.getMessage(), pee);
            throw new RuntimeException("Aktif süreç/görev durumu sorgulanamadı: " + pee.getMessage(), pee);
        }

        String taskId = activeTask.getId();
        String taskDefinitionKey = activeTask.getTaskDefinitionKey();
        String processDefinitionId = activeTask.getProcessDefinitionId();
        log.info("Aktif görev bulundu: Task ID: {}, Task Key: {}, Process Definition ID: {}", taskId, taskDefinitionKey, processDefinitionId);


        // 4. BPMN'den Ayarlanacak İlerleme Değişkenini Bul
        String variableNameToSet = null;
        try {
            BpmnModelInstance modelInstance = repositoryService.getBpmnModelInstance(processDefinitionId);
            if (modelInstance == null) {
                // Bu durum Camunda'nın çalışmasında ciddi bir sorun olduğunu gösterir.
                log.error("Kritik Hata: Process definition ID '{}' için BPMN modeli RepositoryService tarafından bulunamadı.", processDefinitionId);
                throw new IllegalStateException("BPMN modeli bulunamadı: " + processDefinitionId);
            }

            UserTask userTaskElement = modelInstance.getModelElementById(taskDefinitionKey);
            if (userTaskElement == null) {
                // Bu durum, deploy edilen BPMN ile çalışan instance arasında uyumsuzluk olduğunu gösterebilir.
                log.error("Konfigürasyon Hatası: BPMN modelinde task definition key '{}' (ID: {}) bulunamadı.", taskDefinitionKey, processDefinitionId);
                throw new IllegalStateException("BPMN modelinde görev tanımı bulunamadı: " + taskDefinitionKey);
            }

            ExtensionElements extensionElements = userTaskElement.getExtensionElements();
            if (extensionElements != null) {
                // CamundaProperties listesini al
                List<CamundaProperties> propertiesList = extensionElements.getElementsQuery()
                        .filterByType(CamundaProperties.class)
                        .list();

                if (!propertiesList.isEmpty()) {
                    if (propertiesList.size() > 1) {
                        log.warn("Task Key '{}' için birden fazla <camunda:properties> bulundu. İlki kullanılacak.", taskDefinitionKey);
                    }
                    CamundaProperties camundaProperties = propertiesList.get(0);

                    // İlgili property'yi collection içinde ara
                    Collection<CamundaProperty> propsCollection = camundaProperties.getCamundaProperties();
                    Optional<CamundaProperty> nextStepVariableProp = propsCollection.stream()
                            .filter(prop -> NEXT_STEP_VARIABLE_PROPERTY_NAME.equals(prop.getCamundaName()))
                            .findFirst();

                    if (nextStepVariableProp.isPresent()) {
                        variableNameToSet = nextStepVariableProp.get().getCamundaValue();
                        if (variableNameToSet == null || variableNameToSet.isBlank()) {
                            log.warn("Task Key '{}' için '{}' property değeri BPMN'de boş tanımlanmış.", taskDefinitionKey, NEXT_STEP_VARIABLE_PROPERTY_NAME);
                            variableNameToSet = null; // Boş değeri kullanma
                        } else {
                            log.info("BPMN'den bir sonraki adım için ayarlanacak değişken bulundu: '{}'", variableNameToSet);
                        }
                    } else {
                        log.info("Task Key '{}' için <camunda:properties> içinde '{}' property bulunamadı (Muhtemelen son görev veya BPMN eksik).", taskDefinitionKey, NEXT_STEP_VARIABLE_PROPERTY_NAME);
                    }
                } else {
                    log.info("Task Key '{}' için <camunda:properties> elementi bulunamadı.", taskDefinitionKey);
                }
            } else {
                log.info("Task Key '{}' için ExtensionElements bulunamadı.", taskDefinitionKey);
            }

        } catch (Exception e) {
            // Model API kullanımı sırasında beklenmedik bir hata olursa
            log.error("BPMN modelinden '{}' property okunurken hata oluştu (Task Key: {}): {}",
                    NEXT_STEP_VARIABLE_PROPERTY_NAME, taskDefinitionKey, e.getMessage(), e);
            // Bu, yapılandırma veya Camunda hatası olabilir, işlemi durdurmak mantıklı.
            throw new IllegalStateException("Süreç ilerleme değişkeni BPMN'den okunamadı: " + e.getMessage(), e);
        }

        // 5. İlerleme Değişkenini Ayarla (Eğer bulunduysa)
        if (variableNameToSet != null) {
            try {
                // setVariable idempotenttir, mevcut değeri kontrol etmeye gerek yok.
                runtimeService.setVariable(processInstanceId, variableNameToSet, true);
                log.info("Camunda süreci (ID: {}) için '{}' değişkeni 'true' olarak ayarlandı.", processInstanceId, variableNameToSet);
            } catch (ProcessEngineException e) {
                log.error("Camunda süreci (ID: {}) ilerleme değişkeni ('{}') ayarlanırken hata: {}",
                        processInstanceId, variableNameToSet, e.getMessage(), e);
                // Değişken ayarlanamazsa görev tamamlanmamalı.
                throw new IllegalStateException("Süreç ilerleme onayı verilirken Camunda hatası: " + e.getMessage(), e);
            }
        } else {
            log.info("Bu görev ('{}') için ayarlanacak bir sonraki adım değişkeni bulunmadığından değişken ayarlanmadı.", taskDefinitionKey);
        }

        // 6. User Task'ı Tamamla
        try {
            taskService.complete(taskId);
            log.info("Aktif görev (Task ID: {}) başarıyla tamamlandı. Sürecin ilerlemesi bekleniyor.", taskId);
        } catch (ProcessEngineException e){ // Görev bulunamazsa, kilitliyse vb. hatalar
            log.error("Görev (Task ID: {}) tamamlanırken Camunda hatası: {}", taskId, e.getMessage(), e);
            // Bu durumda işlem başarısız olmuştur.
            throw new IllegalStateException("Görev (Task ID: " + taskId +") tamamlanamadı: " + e.getMessage(), e);
        } catch (Exception e) { // Beklenmedik diğer hatalar
            log.error("Görev (Task ID: {}) tamamlanırken beklenmedik hata: {}", taskId, e.getMessage(), e);
            throw new RuntimeException("Görev tamamlanırken beklenmedik hata: " + e.getMessage(), e); // Genel RuntimeException
        }
        // Durum güncellemesi, bu adımdan sonra tetiklenecek olan Service Task tarafından yapılacak.
    }


    private String generateUniqueTrackingNumber() {
        // Basit ve kısa bir takip numarası üretimi
        for (int i = 0; i < MAX_TRACKING_NUMBER_ATTEMPTS; i++) {
            String prefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHH")); // Saat dahil
            long randomSuffix = ThreadLocalRandom.current().nextLong(10000, 100000); // 5 haneli
            String trackingNumber = prefix + randomSuffix;

            if (!cargoRepository.existsByTrackingNumber(trackingNumber)) {
                log.debug("Üretilen Takip Numarası: {}", trackingNumber);
                return trackingNumber;
            }
            log.warn("Takip numarası çakışması: {}. Deneme: {}/{}", trackingNumber, i + 1, MAX_TRACKING_NUMBER_ATTEMPTS);
            // Kısa bekleme eklenebilir ama genellikle gereksiz
            // try { Thread.sleep(1); } catch (InterruptedException ignored) {}
        }
        log.error("{} denemede benzersiz takip numarası üretilemedi.", MAX_TRACKING_NUMBER_ATTEMPTS);
        throw new TrackingNumberGenerationException("Benzersiz takip numarası " + MAX_TRACKING_NUMBER_ATTEMPTS + " denemede üretilemedi.");
    }
}