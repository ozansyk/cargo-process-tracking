package com.ozansoyak.cargo_process_tracking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActiveTaskDto {
    private String taskId;                  // Camunda Task ID
    private String taskName;                // Görev Adı (BPMN'den)
    private String taskDefinitionKey;       // Görev Tanım Anahtarı
    private String processInstanceId;
    private String processDefinitionName;   // Süreç Adı
    private String businessKey;             // Genellikle Takip Numarası
    private LocalDateTime createTime;       // Görevin oluşturulma zamanı
    private String assignee;                // Göreve atanan kişi (varsa)
    private List<String> candidateGroups;   // Göreve atanabilecek gruplar (virgülle ayrılmış)
}