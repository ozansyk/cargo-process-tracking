package com.ozansoyak.cargo_process_tracking.model;

import com.ozansoyak.cargo_process_tracking.model.enums.CargoStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "cargos")
public class Cargo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String trackingNumber;

    // Gönderici Bilgileri
    @Column(nullable = false)
    private String senderName;

    @Column(nullable = false, length = 500)
    private String senderAddress;

    @Column(nullable = false, length = 100)
    private String senderCity;

    @Column(nullable = false, length = 20)
    private String senderPhone;

    private String senderEmail;

    // Alıcı Bilgileri
    @Column(nullable = false)
    private String receiverName;

    @Column(nullable = false, length = 500)
    private String receiverAddress;

    @Column(nullable = false, length = 100)
    private String receiverCity;

    @Column(nullable = false, length = 20)
    private String receiverPhone;

    private String receiverEmail;

    // Kargo Detayları
    @Column(nullable = false)
    private Double weight;

    @Column(length = 50)
    private String dimensions;

    @Column(nullable = false, length = 255)
    private String contentDescription;

    // Durum ve Süreç Bilgileri
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private CargoStatus currentStatus;

    private String processInstanceId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime lastUpdatedAt;

    //@OneToMany(mappedBy = "cargo", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    //private List<ShipmentHistory> history;
}