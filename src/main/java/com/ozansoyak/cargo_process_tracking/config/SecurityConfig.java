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
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Configuration
@EnableWebSecurity
@Slf4j
public class SecurityConfig {

    private final UserDetailsServiceImpl userDetailsService;
    private final UserRepository userRepository;

    public SecurityConfig(UserDetailsServiceImpl userDetailsService, UserRepository userRepository) {
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
    }


    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authz -> authz
                        .requestMatchers(
                                "/", "/login", "/track**", "/error",
                                "/images/**", "/css/**", "/js/**"
                                ,"/api/cargos/**"
                        ).permitAll()
                        .requestMatchers("/camunda/**", "/engine-rest/**", "/app/**").permitAll()
                        .requestMatchers("/panel/kullanici-yonetimi").hasRole("ADMIN")
                        .requestMatchers("/deployments/**").hasRole("ADMIN")
                        .requestMatchers("/panel/aktif-gorevler").authenticated()
                        .requestMatchers("/api/cargos/tasks/**").authenticated()
                        .requestMatchers("/panel/**").authenticated()
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .defaultSuccessUrl("/panel", true)
                        .failureHandler((request, response, exception) -> {
                            String email = request.getParameter("email");
                            Optional<UserEntity> userOpt = userRepository.findByEmail(email);
                            String errorMessage;
                            if (userOpt.isPresent() && !userOpt.get().getIsEnabled()) {
                                errorMessage = "Hesabınız pasif durumda. Lütfen yönetici ile iletişime geçiniz.";
                            } else if (exception.getMessage() != null && exception.getMessage().contains("Bad credentials")) {
                                errorMessage = "Geçersiz kullanıcı adı veya şifre.";
                            } else {
                                log.error("Giriş hatası - Kullanıcı: {}, Hata: {}", email, exception.getMessage());
                                errorMessage = "Giriş sırasında bir sorun oluştu.";
                            }
                            String encodedErrorMessage = URLEncoder.encode(errorMessage, StandardCharsets.UTF_8);
                            response.sendRedirect("/login?error=" + encodedErrorMessage);
                        })
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll()
                );

        return http.build();
    }


    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity http) throws Exception {
        AuthenticationManagerBuilder authenticationManagerBuilder =
                http.getSharedObject(AuthenticationManagerBuilder.class);
        authenticationManagerBuilder
                .userDetailsService(userDetailsService)
                .passwordEncoder(passwordEncoder());
        return authenticationManagerBuilder.build();
    }

}