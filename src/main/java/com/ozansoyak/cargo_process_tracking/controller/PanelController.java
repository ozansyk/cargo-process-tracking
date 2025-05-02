package com.ozansoyak.cargo_process_tracking.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/panel") // Bu path altındaki tüm istekler buraya gelir
public class PanelController {

    @GetMapping
    public String showPanel(Model model, Authentication authentication) {
        // Giriş yapmış kullanıcının bilgilerini alıp model'e ekleyebiliriz
        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();
            String username;
            if (principal instanceof UserDetails) {
                username = ((UserDetails)principal).getUsername();
            } else {
                username = principal.toString();
            }
            model.addAttribute("username", username);
        }
        return "panel"; // resources/templates/panel.html
    }

    // Panelle ilgili başka sayfalar veya işlemler buraya eklenebilir
    // Örneğin:
    // @GetMapping("/tasks")
    // public String showTasks(Model model) { ... return "panel-tasks"; }
}