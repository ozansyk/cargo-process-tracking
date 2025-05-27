package com.ozansoyak.cargo_process_tracking.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessDefinitionDto {
    private String id;       // Camunda Process Definition ID
    private String key;      // Camunda Process Definition Key
    private String name;     // Süreç Adı
    private int version;     // Versiyon
    private String deploymentId; // Hangi deployment'a ait olduğu
}