package com.ozansoyak.cargo_process_tracking.service.impl;

import com.ozansoyak.cargo_process_tracking.model.UserEntity;
import com.ozansoyak.cargo_process_tracking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User; // Spring Security User
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional; // Önemli

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true) // Veritabanından okuma yapıldığı için
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        UserEntity userEntity = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Kullanıcı bulunamadı: " + email));

        if (!userEntity.getIsEnabled()) {
            // Spring Security bu durumu farklı exception tipleriyle yönetir,
            // biz burada DisabledException fırlatabiliriz veya direkt User içinde belirtebiliriz.
            // Şimdilik basitlik için UserDetails oluştururken 'enabled' flag'ini false geçelim.
            throw new UsernameNotFoundException("Kullanıcı aktif değil: " + email); // Veya farklı exception
            // return new User(userEntity.getEmail(), userEntity.getPassword(), false, true, true, true, getAuthorities(userEntity));
        }

        // UserDetails nesnesini oluştururken rolleri de ekleyelim
        return new User(
                userEntity.getEmail(),
                userEntity.getPassword(),
                userEntity.getIsEnabled(), // enabled
                true, // accountNonExpired
                true, // credentialsNonExpired
                true, // accountNonLocked
                getAuthorities(userEntity) // Yetkileri/Rolleri al
        );
    }

    // UserType enum'ını GrantedAuthority listesine çeviren metot
    private Collection<? extends GrantedAuthority> getAuthorities(UserEntity user) {
        Set<GrantedAuthority> authorities = new HashSet<>();
        if (user.getUserType() != null) {
            // Spring Security genellikle "ROLE_" prefix'i bekler
            authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getUserType().name()));
            // İleride daha granüler roller (permissionlar) eklenirse burası genişletilebilir.
            // Örn: authorities.add(new SimpleGrantedAuthority("PERMISSION_CREATE_CARGO"));
        } else {
            // Rol atanmamışsa varsayılan bir rol verilebilir veya boş bırakılabilir
            authorities.add(new SimpleGrantedAuthority("ROLE_USER")); // Varsayılan
        }
        return authorities;
    }
}