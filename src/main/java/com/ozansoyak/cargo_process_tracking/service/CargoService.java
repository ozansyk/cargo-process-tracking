package com.ozansoyak.cargo_process_tracking.service;

import com.ozansoyak.cargo_process_tracking.dto.CreateCargoRequest;
import com.ozansoyak.cargo_process_tracking.dto.CargoResponse;

public interface CargoService {
    CargoResponse createCargoAndStartProcess(CreateCargoRequest request);
}