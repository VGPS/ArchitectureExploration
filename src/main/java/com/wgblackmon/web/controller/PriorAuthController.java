package com.wgblackmon.web.controller;

import com.wgblackmon.web.service.PriorAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class PriorAuthController {

    private static final Logger log = LoggerFactory.getLogger(PriorAuthController.class);

    private final PriorAuthService priorAuthService;

    public PriorAuthController(PriorAuthService priorAuthService) {
        this.priorAuthService = priorAuthService;
    }

    @GetMapping({"/", "/prior-auth"})
    public String index() {
        return "prior-auth/index";
    }

    @PostMapping("/prior-auth/submit")
    public String submit(@RequestParam(name = "patientId",     defaultValue = "") String patientId,
                         @RequestParam(name = "cptCode",       defaultValue = "") String cptCode,
                         @RequestParam(name = "icdCode",       defaultValue = "") String icdCode,
                         @RequestParam(name = "clinicalNotes", defaultValue = "") String clinicalNotes,
                         Model model) {
        log.debug("submit() | patientId={}, cptCode={}, icdCode={}", patientId, cptCode, icdCode);
        try {
            model.addAttribute("result", priorAuthService.analyze(patientId, cptCode, icdCode, clinicalNotes));
            model.addAttribute("error", null);
        } catch (Exception e) {
            log.error("submit() | agent error", e);
            model.addAttribute("result", null);
            model.addAttribute("error", e.getMessage());
        }
        log.debug("submit() | return=prior-auth/result-fragment");
        return "prior-auth/result-fragment";
    }
}
