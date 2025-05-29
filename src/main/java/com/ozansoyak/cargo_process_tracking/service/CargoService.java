package com.ozansoyak.cargo_process_tracking.service;

import com.ozansoyak.cargo_process_tracking.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Map;

import java.util.List;


public interface CargoService {

    CargoResponse createCargoAndStartProcess(CreateCargoRequest request);

    void cancelCargoProcess(String trackingNumber);

    TaskCompletionResponse completeUserTaskAndPrepareNextStep(String trackingNumber, String taskDefinitionKey, Map<String, Object> taskVariables);

    TrackingInfoResponse getTrackingInfo(String trackingNumber);

    void completeTaskByIdAndPrepareNextStep(String taskId);

    PanelDataDto getPanelData();

    Page<CargoSearchResultDto> searchCargos(CargoSearchCriteria criteria, Pageable pageable);

    List<ActiveTaskDto> getActiveUserTasks(String username, List<String> userGroups);
}