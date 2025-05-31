package com.ozansoyak.cargo_process_tracking.service.impl;

import com.ozansoyak.cargo_process_tracking.model.Cargo;
import com.ozansoyak.cargo_process_tracking.model.enums.CargoStatus;
import com.ozansoyak.cargo_process_tracking.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String emailFrom;

    @Async
    @Override
    public void sendChangedCargoStatusToReceiver(String toEmail, String trackingNumber, CargoStatus newStatus, Cargo cargoDetails) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(emailFrom);
            message.setTo(toEmail);

            String subject = generateEmailSubject(trackingNumber, newStatus);
            String text = generateEmailText(trackingNumber, newStatus, cargoDetails);

            message.setSubject(subject);
            message.setText(text);

            mailSender.send(message);
            log.info("{} takip numaralı kargonun yeni durumu ({}) hakkında e-posta başarıyla {} adresine gönderildi.", trackingNumber, newStatus, toEmail);
        } catch (Exception e) {
            log.error("{} takip numaralı kargo için durum güncelleme e-postası oluşturulurken/gönderilirken beklenmedik bir hata oluştu (Alıcı: {}): {}", trackingNumber, toEmail, e.getMessage(), e);
        }
    }

    private String generateEmailSubject(String trackingNumber, CargoStatus newStatus) {
        String statusDescription = getStatusDisplayNameForEmail(newStatus);
        return String.format("Kargo Durum Güncellemesi - Takip No: %s, Durum: %s", trackingNumber, statusDescription);
    }

    private String generateEmailText(String trackingNumber, CargoStatus newStatus, Cargo cargoDetails) {
        String statusDescription = getStatusDisplayNameForEmail(newStatus);

        StringBuilder textBuilder = new StringBuilder();
        textBuilder.append(String.format("Sayın %s,\n\n", cargoDetails.getReceiverName()));
        textBuilder.append(String.format("%s takip numaralı kargonuzun durumu güncellenmiştir.\n\n", trackingNumber));
        textBuilder.append(String.format("Yeni Durum: %s\n", statusDescription));

        switch (newStatus) {
            case OUT_FOR_DELIVERY:
                textBuilder.append("Kargonuz bugün teslimat için dağıtıma çıkarılmıştır.\n");
                break;
            case DELIVERED:
                textBuilder.append(String.format("Kargonuz %s tarihinde başarıyla teslim edilmiştir.\n",
                        cargoDetails.getLastUpdatedAt() != null ?
                                cargoDetails.getLastUpdatedAt().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) : "belirtilen tarihte"));
                break;
            case AT_TRANSFER_CENTER:
                textBuilder.append(String.format("Kargonuz %s transfer merkezine ulaşmıştır.\n", cargoDetails.getReceiverCity())); // Örnek lokasyon
                break;
            case CANCELLED:
                textBuilder.append("Kargo gönderiniz iptal edilmiştir.\n");
                break;
            default:
                break;
        }

        textBuilder.append("Kargo Takip Sistemi");

        return textBuilder.toString();
    }

    private String getStatusDisplayNameForEmail(CargoStatus status) {
        if (status == null) return "Bilinmiyor";
        return switch (status) {
            case PENDING -> "Onay Bekliyor";
            case RECEIVED -> "Kargo Alındı";
            case LOADED_ON_VEHICLE_1 -> "İlk Taşıma Aracına Yüklendi";
            case AT_TRANSFER_CENTER -> "Transfer Merkezinde";
            case LOADED_ON_VEHICLE_2 -> "Dağıtım Aracına Yüklendi";
            case AT_DISTRIBUTION_HUB -> "Dağıtım Merkezinde";
            case OUT_FOR_DELIVERY -> "Dağıtıma Çıkarıldı";
            case DELIVERED -> "Teslim Edildi";
            case CANCELLED -> "İptal Edildi";
            default -> status.name().replace("_", " ").toLowerCase();
        };
    }
}