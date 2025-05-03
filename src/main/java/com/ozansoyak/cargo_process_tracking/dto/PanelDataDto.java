package com.ozansoyak.cargo_process_tracking.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PanelDataDto {
    private long beklemedeAlinanCount;
    private long tasiniyorTransferdeCount; // YENİ ALAN
    private long dagitimdaCount;
    private long teslimEdilenCount;
    private long iptalEdilenCount;
    private List<RecentActivityDto> recentActivities;
}