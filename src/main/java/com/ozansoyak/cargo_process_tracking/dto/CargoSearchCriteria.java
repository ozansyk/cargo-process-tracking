package com.ozansoyak.cargo_process_tracking.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat; // Tarih formatı için

import java.time.LocalDate;

@Data // Getter, Setter, ToString, EqualsAndHashCode, RequiredArgsConstructor ekler
public class CargoSearchCriteria {
    private String trackingNo;
    private String customerInfo; // Gönderici/Alıcı Adı veya Telefonu için tek alan
    private String statusFilter; // Durum Enum'ının String hali (veya direkt Enum tipi)
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) // HTML date input'tan gelen YYYY-MM-DD formatı için
    private LocalDate date; // Şimdilik tek tarih, ileride başlangıç/bitiş olabilir
    // Sayfalama bilgisi Controller'da Pageable ile yönetilecek
}