package com.ozansoyak.cargo_process_tracking.service;

import com.ozansoyak.cargo_process_tracking.dto.*;
import org.springframework.data.domain.Page; // Page eklendi
import org.springframework.data.domain.Pageable; // Pageable eklendi


public interface CargoService {

    CargoResponse createCargoAndStartProcess(CreateCargoRequest request);

    void cancelCargoProcess(String trackingNumber);

    void completeUserTaskAndPrepareNextStep(String trackingNumber);

    TrackingInfoResponse getTrackingInfo(String trackingNumber);

    PanelDataDto getPanelData();

    Page<CargoSearchResultDto> searchCargos(CargoSearchCriteria criteria, Pageable pageable);
}