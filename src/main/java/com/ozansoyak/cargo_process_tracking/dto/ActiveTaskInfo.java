package com.ozansoyak.cargo_process_tracking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveTaskInfo {
    private String taskDefinitionKey;
    private String taskName;
    private boolean isCompletable; // Bu görev özelinde tamamlanabilirlik (genelde true olacak)
    private boolean requiresInput; // Fatura gibi özel input gerektiren görevler için flag
}