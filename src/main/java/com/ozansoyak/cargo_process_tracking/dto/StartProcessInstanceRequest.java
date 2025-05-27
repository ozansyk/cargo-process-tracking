package com.ozansoyak.cargo_process_tracking.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import java.util.Map;

@Data
public class StartProcessInstanceRequest {
    @NotEmpty(message = "Süreç tanımı anahtarı boş olamaz")
    private String processDefinitionKey;

    private String businessKey; // Opsiyonel

    // Başlangıç değişkenleri için basit bir Map
    // Gerçekte bu daha karmaşık bir yapı olabilir (örn: JSON string veya özel DTO)
    private Map<String, Object> variables;
}