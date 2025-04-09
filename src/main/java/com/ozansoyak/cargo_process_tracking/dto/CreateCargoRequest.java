package com.ozansoyak.cargo_process_tracking.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class CreateCargoRequest {

    @NotBlank(message = "Gönderici adı boş olamaz")
    @Size(max = 255)
    private String senderName;

    @NotBlank(message = "Gönderici adresi boş olamaz")
    @Size(max = 500)
    private String senderAddress;

    @NotBlank(message = "Gönderici şehir boş olamaz")
    @Size(max = 100)
    private String senderCity;

    @NotBlank(message = "Gönderici telefon boş olamaz")
    @Pattern(regexp = "^0[0-9]{10}$", message = "Geçerli bir telefon numarası giriniz")
    @Size(max = 11)
    private String senderPhone;

    @Email(message = "Geçerli bir gönderici e-posta adresi giriniz")
    private String senderEmail;

    @NotBlank(message = "Alıcı adı boş olamaz")
    @Size(max = 255)
    private String receiverName;

    @NotBlank(message = "Alıcı adresi boş olamaz")
    @Size(max = 500)
    private String receiverAddress;

    @NotBlank(message = "Alıcı şehir boş olamaz")
    @Size(max = 100)
    private String receiverCity;

    @NotBlank(message = "Alıcı telefon boş olamaz")
    @Pattern(regexp = "^0[0-9]{10}$", message = "Geçerli bir telefon numarası giriniz")
    @Size(max = 11)
    private String receiverPhone;

    @Email(message = "Geçerli bir alıcı e-posta adresi giriniz (Bildirim için önemli)")
    private String receiverEmail;

    @NotNull(message = "Ağırlık boş olamaz")
    @Positive(message = "Ağırlık pozitif olmalı")
    private Double weight;

    @Size(max = 50)
    private String dimensions;

    @NotBlank(message = "İçerik açıklaması boş olamaz")
    @Size(max = 255)
    private String contentDescription;
}