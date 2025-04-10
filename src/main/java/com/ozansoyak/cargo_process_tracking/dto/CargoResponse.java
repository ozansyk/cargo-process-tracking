package com.ozansoyak.cargo_process_tracking.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CargoResponse {
    private Long id;
    private String trackingNumber;
    private String initialStatus;
    private String processInstanceId;
}