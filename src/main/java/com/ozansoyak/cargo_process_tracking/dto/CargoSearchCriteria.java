package com.ozansoyak.cargo_process_tracking.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat; // Tarih formatı için

import java.time.LocalDate;

@Data
public class CargoSearchCriteria {
    private String trackingNo;
    private String customerInfo;
    private String statusFilter;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date;
}