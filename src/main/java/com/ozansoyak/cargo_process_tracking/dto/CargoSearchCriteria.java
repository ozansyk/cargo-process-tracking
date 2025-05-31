package com.ozansoyak.cargo_process_tracking.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Data
public class CargoSearchCriteria {
    private String trackingNo;
    private String customerInfo;
    private String statusFilter;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date;
}