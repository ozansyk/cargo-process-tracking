package com.ozansoyak.cargo_process_tracking.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Slf4j
public class HomeController {

    @GetMapping("/")
    public String home() {
        log.info("HomeController '/' endpoint called. Redirecting to /track.");
        return "redirect:/track";
    }

}