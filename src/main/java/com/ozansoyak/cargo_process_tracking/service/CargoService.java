package com.ozansoyak.cargo_process_tracking.service;

import com.ozansoyak.cargo_process_tracking.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Map; // Eklendi

public interface CargoService {

    CargoResponse createCargoAndStartProcess(CreateCargoRequest request);

    void cancelCargoProcess(String trackingNumber);

    // taskVariables parametresi eklendi
    void completeUserTaskAndPrepareNextStep(String trackingNumber, String taskDefinitionKey, Map<String, Object> taskVariables);

    TrackingInfoResponse getTrackingInfo(String trackingNumber);

    PanelDataDto getPanelData(); // Bu DTO'nun tanımının sizde olduğunu varsayıyorum

    Page<CargoSearchResultDto> searchCargos(CargoSearchCriteria criteria, Pageable pageable);
}