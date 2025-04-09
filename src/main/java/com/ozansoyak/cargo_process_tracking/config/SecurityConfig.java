package com.ozansoyak.cargo_process_tracking.config;

import com.ozansoyak.cargo_process_tracking.model.UserEntity;
import com.ozansoyak.cargo_process_tracking.repository.UserRepository;
import com.ozansoyak.cargo_process_tracking.service.impl.UserDetailsServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Configuration
public class SecurityConfig {

    private final UserDetailsServiceImpl userDetailsService;

    public SecurityConfig(
            UserDetailsServiceImpl userDetailsService) {
        this.userDetailsService = userDetailsService;
    }


    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, UserRepository userRepository) throws Exception {
        http
                .csrf().disable()
                .authorizeHttpRequests()
                .requestMatchers("/", "/register", "/login", "/index", "/api/cargos", //TODO /api/cargos kaldırılacak
                        "/images/**", "/css/**", "/js/**").permitAll()
                .anyRequest().authenticated()
                .and()
                .formLogin()
                .loginPage("/login")
                .defaultSuccessUrl("/dashboard", true)
                .failureHandler((request, response, exception) -> {
                    String email = request.getParameter("email");
                    Optional<UserEntity> user = userRepository.findByEmail(email);
                    String encodedErrorMessage = "Hata!";
                    if (user.isPresent() && !user.get().getIsEnabled()) {
                        // Kullanıcı aktif değilse özel hata mesajı
                        String errorMessage = "Hesabınız pasif durumda. Lütfen admin ile iletişime geciniz.";
                        encodedErrorMessage = URLEncoder.encode(errorMessage, StandardCharsets.UTF_8);
                    } else if (exception.getMessage().equalsIgnoreCase("Bad credentials")) {
                        String errorMessage = "Kullanıcı adı veya şifre yanlış.";
                        encodedErrorMessage = URLEncoder.encode(errorMessage, StandardCharsets.UTF_8);
                    } else {
                        String errorMessage = "Giriş sırasında bir hata oluştu.";
                        encodedErrorMessage = URLEncoder.encode(errorMessage, StandardCharsets.UTF_8);
                    }
                    response.sendRedirect("/login?error=" + encodedErrorMessage);
                })
                .permitAll()
                .and()
                .logout()
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll();

        return http.build();
    }


    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity http) throws Exception {
        return http.getSharedObject(AuthenticationManagerBuilder.class)
                .userDetailsService(userDetailsService)
                .passwordEncoder(passwordEncoder())
                .and()
                .build();
    }

}
