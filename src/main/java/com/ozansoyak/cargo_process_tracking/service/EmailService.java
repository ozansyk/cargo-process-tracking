package com.ozansoyak.cargo_process_tracking.service;

import com.ozansoyak.cargo_process_tracking.model.Cargo;
import com.ozansoyak.cargo_process_tracking.model.enums.CargoStatus;

public interface EmailService {
    void sendChangedCargoStatusToReceiver(String toEmail, String trackingNumber, CargoStatus newStatus, Cargo cargoDetails);
}
