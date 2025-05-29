package com.ozansoyak.cargo_process_tracking.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Controller
public class LoginController {

    @GetMapping("/login")
    public String loginPage(@RequestParam(value = "error", required = false) String error,
                            @RequestParam(value = "logout", required = false) String logout,
                            Model model) {

        if (error != null) {
            try {
                String decodedError = URLDecoder.decode(error, StandardCharsets.UTF_8);
                model.addAttribute("errorMessage", decodedError);
            } catch (Exception e) {
                model.addAttribute("errorMessage", "Geçersiz kullanıcı adı veya şifre.");
            }
        }

        if (logout != null) {
            model.addAttribute("logoutMessage", "Başarıyla çıkış yaptınız.");
        }

        return "login";
    }
}