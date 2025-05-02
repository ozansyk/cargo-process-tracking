package com.ozansoyak.cargo_process_tracking.config;

import com.ozansoyak.cargo_process_tracking.model.UserEntity;
import com.ozansoyak.cargo_process_tracking.model.enums.UserType;
import com.ozansoyak.cargo_process_tracking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional; // Opsiyonel ama iyi pratik

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional // Eğer işlem sırasında hata olursa geri alınmasını sağlayabilir
    public void run(String... args) throws Exception {
        // Başlangıçta admin kullanıcısı yoksa oluştur
        String adminEmail = "ozan.soyak@hotmail.com"; // Veya admin@example.com gibi tam bir email
        if (userRepository.findByEmail(adminEmail).isEmpty()) {
            UserEntity adminUser = UserEntity.builder()
                    .email(adminEmail)
                    .password(passwordEncoder.encode("123456")) // Şifreyi encode etmeyi unutma!
                    .userType(UserType.ADMIN) // ADMIN rolünü ata
                    .isEnabled(true)
                    .build();
            userRepository.save(adminUser);
            log.info("Başlangıç admin kullanıcısı oluşturuldu: {}", adminEmail);
        } else {
            log.info("Admin kullanıcısı zaten mevcut.");
        }

        // İsterseniz burada başka başlangıç verileri de ekleyebilirsiniz.
        // Örneğin, varsayılan bir personel kullanıcısı:
        /*
        String personnelEmail = "personel@example.com";
        if (!userRepository.findByEmail(personnelEmail).isPresent()) {
            UserEntity personnelUser = UserEntity.builder()
                    .email(personnelEmail)
                    .password(passwordEncoder.encode("personel123"))
                    .userType(UserType.PERSONNEL)
                    .isEnabled(true)
                    .build();
            userRepository.save(personnelUser);
            log.info("Başlangıç personel kullanıcısı oluşturuldu: {}", personnelEmail);
        }
        */
    }
}