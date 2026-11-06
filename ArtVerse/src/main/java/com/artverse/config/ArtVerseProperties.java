package com.artverse.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "artverse")
public class ArtVerseProperties {

    private List<String> corsOrigins = List.of("http://localhost:5173", "http://127.0.0.1:5173");
    private Storage storage = new Storage();
    private Upload upload = new Upload();
    private ImportConfig importConfig = new ImportConfig();
    private Character character = new Character();
    private Ref ref = new Ref();
    private Manga manga = new Manga();
    private MangaGeneration mangaGeneration = new MangaGeneration();
    private DeepSeek deepseek = new DeepSeek();
    private Image image = new Image();
    private Minio minio = new Minio();
    private Coze coze = new Coze();
    private Flyway flyway = new Flyway();
    private RateLimit rateLimit = new RateLimit();
    private SingleFlight singleFlight = new SingleFlight();
    private Idempotency idempotency = new Idempotency();
    private Agent agent = new Agent();
    private Secrets secrets = new Secrets();
    private Auth auth = new Auth();

    @Data
    public static class Storage {
        private String root = "./manga_outputs";
    }

    @Data
    public static class Secrets {
        private String encryptionKey = "";
        private int activeKeyVersion = 2;
    }

    @Data
    public static class Auth {
        private Cookie cookie = new Cookie();
        private Challenge challenge = new Challenge();
        private Risk risk = new Risk();
        private Proxy proxy = new Proxy();
        private Csrf csrf = new Csrf();
    }

    @Data
    public static class Cookie {
        private boolean secure = false;
        private String refreshCookieName = "artverse-refresh";
        private long refreshTokenTimeoutSeconds = 43_200;
    }

    @Data
    public static class Challenge {
        private ChallengeMode mode = ChallengeMode.ENFORCE;
        private String provider = "graphic-captcha";
        private int captchaLength = 4;
        private int captchaImageWidth = 160;
        private int captchaImageHeight = 50;
        private int captchaExpireSeconds = 300;
        private int captchaFontSize = 28;
        private int captchaInterferenceLines = 5;
    }

    public enum ChallengeMode {
        DISABLED,
        OBSERVE,
        ENFORCE
    }

    @Data
    public static class Risk {
        private String hmacKey = "";
        private int passwordChallengeThreshold = 3;
        private int passwordFailureLimit = 6;
        private int passwordFailureWindowSeconds = 300;
        private int loginIpHardLimit = 100;
        private int loginIpWindowSeconds = 300;
        private int registerIpLimit = 10;
        private int registerIpWindowSeconds = 3_600;
    }

    @Data
    public static class Proxy {
        private List<String> trustedCidrs = List.of();
    }

    @Data
    public static class Csrf {
        private EnforcementMode mode = EnforcementMode.ENFORCE;
        private String headerName = "X-ArtVerse-Client";
        private String headerValue = "web";
    }

    public enum EnforcementMode {
        REPORT,
        ENFORCE
    }

    @Data
    public static class Upload {
        private long maxImageBytes = 10485760;//10MB
    }

    @Data
    public static class ImportConfig {
        private long maxZipBytes = 524288000;//500MB
        private int maxNovelChars = 50000;//50K
    }

    @Data
    public static class Character {
        private int maxChars = 20000;
    }

    @Data
    public static class Ref {
        private int maxImagesPerLevel = 4;
    }

    @Data
    public static class Manga {
        private int defaultImageCount = 10;
        private List<Integer> allowedImageCounts = List.of(4, 6, 8, 10, 12, 15, 20);
    }

    @Data
    public static class MangaGeneration {
        private int maxConcurrentJobs = 4;
    }

    @Data
    public static class DeepSeek {
        private String model = "deepseek-v4-flash";
    }

    @Data
    public static class Image {
        private String model = "gpt-image-2";
        private String size = "1024x1536";
    }

    @Data
    public static class Minio {
        private String endpoint = "http://localhost:9000";
        private String bucket = "artverse-manga";
        private String region = "us-east-1";
        private String accessKey = "";
        private String secretKey = "";
        private boolean secure = false;
        private String publicBaseUrl = "";
        private int presignedUrlExpireSeconds = 3600;
    }

    @Data
    public static class Coze {
        private String workflowId = "7645642109203103763";
    }

    @Data
    public static class Flyway {
        private boolean autoRepairChecksumMismatch = false;
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        private int defaultWindowSeconds = 60;
        private int defaultMaxRequests = 30;
    }

    @Data
    public static class SingleFlight {
        private boolean enabled = true;
        private int defaultTtlSeconds = 30;
    }

    @Data
    public static class Idempotency {
        private boolean enabled = true;
        private int successTtlSeconds = 180;
        private int failureTtlSeconds = 20;
        private int processingTtlSeconds = 600;
        private int followerWaitSeconds = 300;
        private int maxFollowers = 5;
    }

    @Data
    public static class Agent {
        private int firstEventTimeoutSeconds = 90;
        private int modelIdleTimeoutSeconds = 180;
        private int modelActivityBudgetSeconds = 900;
        private int toolIdleTimeoutSeconds = 600;
        private long runWatchdogIntervalMs = 30_000;
        private int maxConcurrentRuns = 32;
        private int maxConcurrentRunsPerUser = 2;
        private int routerMaxModelCalls = 2;
        private int conversationMaxModelCalls = 4;
        private int conversationMaxToolCalls = 4;
        private int storyboardMaxModelCalls = 8;
        private int storyboardMaxToolCalls = 12;
        private int reviewMaxModelCalls = 20;
        private int reviewMaxToolCalls = 24;
        private int directorMaxModelCalls = 12;
        private int directorMaxToolCalls = 4;
        private int directorRootMaxModelCalls = 48;
        private int directorRootMaxToolCalls = 64;
        private int textMaxModelCalls = 4;
        private int textMaxToolCalls = 2;
        private int storyChatReadMaxModelCalls = 4;
        private int storyChatReadMaxToolCalls = 8;
        private int storyChatWriteMaxModelCalls = 8;
        private int storyChatWriteMaxToolCalls = 12;
        private int maxInputTokens = 224_000;
        private int maxInputBytes = 896_000;
        private int maxOutputTokens = 32_000;
        private int maxOutputBytes = 256_000;
        private int maxSubagents = 4;
        private boolean ragInjectionEnabled = true;
        private boolean businessSkillsEnabled = true;
        private boolean multiInstanceEventBusEnabled = true;
        private boolean outboxWorkerEnabled = true;
        private int outboxBatchSize = 8;
        private int outboxLeaseSeconds = 90;
        private int outboxMaxAttempts = 5;
        private boolean providerAllowHttp = false;
        private List<String> providerAllowedPrivateCidrs = List.of();
        private List<ModelCapability> modelCapabilities = List.of();

        @Data
        public static class ModelCapability {
            private String provider;
            private String baseUrl;
            private String model;
            private Long configId;
            private boolean toolCalling;
            private boolean nativeStructuredOutput;
            private boolean nativeStructuredOutputWithTools;
            private String declarationVersion;
        }

        // Automatic multi-agent routing
        private boolean autoRoutingEnabled = true;
        private boolean routingShadowMode = false;
        private double routingDirectThreshold = 0.80;
        private double routingReadOnlyThreshold = 0.55;
        // Circuit breaker
        private int circuitBreakerFailureThreshold = 5;
        private int circuitBreakerWaitSeconds = 30;
        private int circuitBreakerSlidingWindowSize = 20;
        private int circuitBreakerSlowCallThresholdMs = 120_000;
    }
}
