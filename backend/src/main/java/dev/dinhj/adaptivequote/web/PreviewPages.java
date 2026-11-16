package dev.dinhj.adaptivequote.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class PreviewPages {
    @GetMapping({"/embedded", "/embedded/"})
    String embedded() {
        return "forward:/embedded.html";
    }
}
