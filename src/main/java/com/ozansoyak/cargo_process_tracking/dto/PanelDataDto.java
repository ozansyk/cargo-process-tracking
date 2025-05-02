package com.ozansoyak.cargo_process_tracking.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder // Builder ile oluşturmak kolaylık sağlar
public class PanelDataDto {
    // Widget verileri (İsimleri paneldeki widget başlıklarına uygun yapalım)
    private long beklemedeAlinanCount; // Toplam Beklemede + Alınan
    private long dagitimdaCount;       // Toplam Dağıtımda
    private long teslimEdilenCount;    // Toplam Teslim Edilen
    private long iptalEdilenCount;     // Toplam İptal Edilen

    // Son işlemler listesi
    private List<RecentActivityDto> recentActivities;
}