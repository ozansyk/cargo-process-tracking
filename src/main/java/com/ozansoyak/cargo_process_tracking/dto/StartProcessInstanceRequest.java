package com.ozansoyak.cargo_process_tracking.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import java.util.Map;

@Data
public class StartProcessInstanceRequest {
    @NotEmpty(message = "Süreç tanımı anahtarı boş olamaz")
    private String processDefinitionKey;

    private String businessKey; // Opsiyonel

    private Map<String, Object> variables; //Opsiyonel
}