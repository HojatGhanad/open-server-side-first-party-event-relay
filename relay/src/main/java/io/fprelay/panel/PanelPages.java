package io.fprelay.panel;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Routes for the panel's static pages.
 */
@Profile("panel")
@Controller
public class PanelPages {
    @GetMapping("/login")
    public String login() {
        return "forward:/login.html";
    }

    @GetMapping("/tenants")
    public String tenants() {
        return "forward:/index.html";
    }
}
