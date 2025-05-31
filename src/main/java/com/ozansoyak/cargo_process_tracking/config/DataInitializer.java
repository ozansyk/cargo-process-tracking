package com.ozansoyak.cargo_process_tracking.config;

import com.ozansoyak.cargo_process_tracking.model.UserEntity;
import com.ozansoyak.cargo_process_tracking.model.enums.UserType;
import com.ozansoyak.cargo_process_tracking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        String adminEmail = "ozan.soyak@hotmail.com";
        if (userRepository.findByEmail(adminEmail).isEmpty()) {
            UserEntity adminUser = UserEntity.builder()
                    .email(adminEmail)
                    .password(passwordEncoder.encode("123456"))
                    .userType(UserType.ADMIN)
                    .isEnabled(true)
                    .build();
            userRepository.save(adminUser);
            log.info("Başlangıç admin kullanıcısı oluşturuldu: {}", adminEmail);
        } else {
            log.info("Admin kullanıcısı zaten mevcut.");
        }

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