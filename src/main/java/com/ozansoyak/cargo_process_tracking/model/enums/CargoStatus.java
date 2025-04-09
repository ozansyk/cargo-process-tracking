package com.ozansoyak.cargo_process_tracking.model.enums;

public enum CargoStatus {
    PENDING,        // Beklemede (Henüz akış oluşmadan Db'deki ilk status)
    RECEIVED,       // Teslim Alındı (Akıştaki ilk durum)
    SHIPPED,        // Yola Çıktı
    DELIVERED,      // Teslim Edildi
    RETURNED,       // İade Edildi
    CANCELLED      // İptal Edildi
}
