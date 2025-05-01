package com.ozansoyak.cargo_process_tracking.dto;

import com.ozansoyak.cargo_process_tracking.model.enums.CargoStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApproveNextStepRequest {

    @NotNull(message = "İlerleme onayı verilen hedef durum belirtilmelidir.")
    private CargoStatus targetStatus; // Hangi duruma geçiş onaylanıyor?
}