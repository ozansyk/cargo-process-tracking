package com.ozansoyak.cargo_process_tracking.model.enums;

public enum CargoStatus {
    PENDING,              // Kayıt oluşturuldu, süreç henüz başlamadı veya ilk adımda
    RECEIVED,             // Kargo teslim alındı (task_UpdateStatusReceived)
    LOADED_ON_VEHICLE_1,  // İlk araca yüklendi (task_UpdateStatusLoaded1)
    AT_TRANSFER_CENTER,   // Transfer merkezinde (task_UpdateStatusTransferCenter)
    LOADED_ON_VEHICLE_2,  // Son araca yüklendi (task_UpdateStatusLoaded2)
    AT_DISTRIBUTION_HUB,  // Dağıtım bölgesinde/merkezinde (task_UpdateStatusDistributionArea)
    OUT_FOR_DELIVERY,     // Dağıtımda (task_UpdateStatusOutForDelivery)
    DELIVERED,            // Teslim edildi (task_UpdateStatusDelivered)
    CANCELLED             // İptal edildi (task_UpdateStatusCancelled)
}
