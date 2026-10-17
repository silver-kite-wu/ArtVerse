package com.artverse.mail;

import com.artverse.api.dto.AuthDtos.SendCodeRequest;
import com.artverse.common.BusinessException;
import com.artverse.common.Result;
import com.artverse.common.aspect.RateLimit;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mail")
@RequiredArgsConstructor
public class MailController {

    private final EmailVerificationService verificationService;

    @GetMapping("/config")
    public Result<Boolean> config() {
        return Result.success("操作成功", verificationService.isEnabled());
    }

    @PostMapping("/send-code")
    @RateLimit(windowSeconds = 60, maxRequests = 5, key = "mail-send")
    public Result<Void> sendCode(@Valid @RequestBody SendCodeRequest req) {
        VerificationScenario scenario;
        try {
            scenario = VerificationScenario.fromCode(req.scenario());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "无效的验证码场景");
        }
        verificationService.sendCode(req.email(), scenario);
        return Result.success("验证码已发送", null);
    }
}
