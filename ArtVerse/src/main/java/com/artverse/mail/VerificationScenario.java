package com.artverse.mail;

public enum VerificationScenario {
    REGISTER("register", "注册验证码", "注册"),
    LOGIN("login", "登录验证码", "登录"),
    RESET_PASSWORD("reset_password", "找回密码验证码", "找回密码");

    private final String code;
    private final String subjectSuffix;
    private final String actionLabel;

    VerificationScenario(String code, String subjectSuffix, String actionLabel) {
        this.code = code;
        this.subjectSuffix = subjectSuffix;
        this.actionLabel = actionLabel;
    }

    public String code() {
        return code;
    }

    public String subjectSuffix() {
        return subjectSuffix;
    }

    public String actionLabel() {
        return actionLabel;
    }

    public static VerificationScenario fromCode(String code) {
        for (VerificationScenario scenario : values()) {
            if (scenario.code.equals(code)) {
                return scenario;
            }
        }
        throw new IllegalArgumentException("未知的验证码场景: " + code);
    }
}