package com.ozansoyak.cargo_process_tracking.config;

import com.ozansoyak.cargo_process_tracking.model.UserEntity;
import com.ozansoyak.cargo_process_tracking.repository.UserRepository;
import com.ozansoyak.cargo_process_tracking.service.impl.UserDetailsServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity; // Bu anotasyon önemli
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Configuration
@EnableWebSecurity // Spring Security'yi etkinleştir
@Slf4j
public class SecurityConfig {

    private final UserDetailsServiceImpl userDetailsService;
    private final UserRepository userRepository; // Failure handler için inject edelim

    // Constructor Injection
    public SecurityConfig(UserDetailsServiceImpl userDetailsService, UserRepository userRepository) {
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
    }


    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF genellikle stateful web uygulamaları için önerilir, ancak
                // Camunda REST API'leri ile sorun çıkarabilir. Şimdilik kapalı bırakalım.
                // Eğer sadece MVC kullanıyorsanız ve REST API'leri dışarıya açmıyorsanız
                // CSRF'ı etkinleştirmeyi düşünebilirsiniz (ama ek yapılandırma gerektirir).
                .csrf(csrf -> csrf.disable()) // Lambda DSL ile CSRF kapatma
                .authorizeHttpRequests(authz -> authz
                        // Herkese açık yollar
                        .requestMatchers(
                                "/",                // Ana sayfa (varsa)
                                "/login",           // Giriş sayfası
                                "/track**",         // Kargo takip sayfası ve sorguları
                                "/error",           // Spring Boot hata sayfası
                                "/images/**",       // Statik kaynaklar
                                "/css/**",
                                "/js/**"
                                // "/register"      // Kayıt sayfası (varsa) herkese açık olmalı
                                // "/api/cargos" // API endpoint'leri için ayrı kural gerekebilir (örn. Basic Auth veya JWT)
                                // Şimdilik API'yi de açık bırakalım test için, sonra kısıtlanabilir.
                                ,"/api/cargos/**"
                                // "/monitor/**", "/flowable-monitor" // Bunlar Camunda değilse kaldırılabilir
                        ).permitAll()
                        // Camunda Webapp ve REST API'leri (genellikle auth gerektirir)
                        .requestMatchers(
                                "/camunda/**",      // Camunda Cockpit/Admin/Tasklist
                                "/engine-rest/**",  // Camunda REST API
                                "/app/**"           // Camunda Webapp path'leri
                        ).authenticated() // Camunda kendi iç güvenliğini de yönetir (admin user)
                        // Personel Paneli
                        .requestMatchers("/panel/**").authenticated() // Sadece giriş yapanlar
                        // Diğer tüm istekler kimlik doğrulaması gerektirsin
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login") // Özel giriş sayfamız
                        .loginProcessingUrl("/login") // Spring Security bu URL'i dinler (POST)
                        .usernameParameter("email") // Formdaki input name="email" olmalı
                        .passwordParameter("password") // Formdaki input name="password" olmalı
                        .defaultSuccessUrl("/panel", true) // Başarılı girişte yönlendir
                        .failureHandler((request, response, exception) -> { // Özel hata yönetimi
                            String email = request.getParameter("email"); // Formdan email'i al
                            Optional<UserEntity> userOpt = userRepository.findByEmail(email);
                            String errorMessage;

                            if (userOpt.isPresent() && !userOpt.get().getIsEnabled()) {
                                errorMessage = "Hesabınız pasif durumda. Lütfen yönetici ile iletişime geçiniz.";
                            } else if (exception.getMessage() != null && exception.getMessage().contains("Bad credentials")) {
                                // Generic "Bad credentials" yerine daha kullanıcı dostu mesaj
                                errorMessage = "Geçersiz kullanıcı adı veya şifre.";
                            } else {
                                // Diğer authentication hataları (örn: kilitli hesap vs.)
                                // exception.getMessage() daha detaylı bilgi verebilir, loglamak iyi olur.
                                log.error("Giriş hatası - Kullanıcı: {}, Hata: {}", email, exception.getMessage());
                                errorMessage = "Giriş sırasında bir sorun oluştu.";
                            }
                            String encodedErrorMessage = URLEncoder.encode(errorMessage, StandardCharsets.UTF_8);
                            response.sendRedirect("/login?error=" + encodedErrorMessage);
                        })
                        .permitAll() // Giriş sayfasının kendisine herkes erişebilmeli
                )
                .logout(logout -> logout
                        .logoutUrl("/logout") // Çıkış URL'i
                        .logoutSuccessUrl("/login?logout") // Başarılı çıkışta yönlendir
                        .invalidateHttpSession(true) // Oturumu geçersiz kıl
                        .deleteCookies("JSESSIONID") // Oturum çerezini sil
                        .permitAll() // Çıkış işleminin kendisine herkes erişebilmeli
                );

        return http.build();
    }


    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // AuthenticationManager Bean'i (Spring Boot 3'te bu şekilde tanımlamak daha yaygın)
    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity http) throws Exception {
        AuthenticationManagerBuilder authenticationManagerBuilder =
                http.getSharedObject(AuthenticationManagerBuilder.class);
        authenticationManagerBuilder
                .userDetailsService(userDetailsService) // Kendi UserDetailsService'imiz
                .passwordEncoder(passwordEncoder());    // Kendi PasswordEncoder'ımız
        return authenticationManagerBuilder.build();
    }

}