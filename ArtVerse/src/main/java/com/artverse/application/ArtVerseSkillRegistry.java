package com.artverse.application;

import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.BusinessSkillSelection;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ArtVerseSkillRegistry {

    private static final String ROOT = "classpath:business-agent-skills/";
    private static final String CATALOG = ROOT + "catalog.yml";
    private static final String PUBLISHED = "PUBLISHED";
    private static final Set<String> ALLOWED_TOOL_GROUPS = Set.of(
            "story-context", "storyboard-draft", "storyboard-commit", "hitl", "novel-draft",
            "automation-read", "automation-manage", "review-experts", "knowledge-read", "asset-read",
            "artifact-read", "artifact-draft-management", "novel-history-read", "knowledge-candidate-read",
            "settings-draft", "knowledge-draft", "image-proposal"
    );
    private static final Map<AgentTaskType, List<String>> DEFAULT_TASK_SKILLS = Map.of(
            AgentTaskType.MANGA_CONVERSATION, List.of("manga.creative-direction"),
            AgentTaskType.MANGA_CREATIVE, List.of("manga.creative-direction", "manga.visual-prompting"),
            AgentTaskType.MANGA_STORYBOARD, List.of("manga.storyboard-design", "manga.visual-prompting"),
            AgentTaskType.MANGA_REVIEW, List.of("manga.storyboard-review")
    );
    private static final Map<NovelBusinessSkillMode, List<String>> NOVEL_MODE_SKILLS = Map.of(
            NovelBusinessSkillMode.IDEATION, List.of("novel.ideation"),
            NovelBusinessSkillMode.CHAPTER_WRITING, List.of("novel.chapter-writing"),
            NovelBusinessSkillMode.PROSE_POLISH, List.of("novel.prose-polish"),
            NovelBusinessSkillMode.REVIEW, List.of("novel.review"),
            NovelBusinessSkillMode.NONE, List.of()
    );

    private final ResourceLoader resourceLoader;
    private final ArtVerseProperties properties;

    private volatile List<SkillManifest> publishedSkills = List.of();
    private volatile Map<String, SkillManifest> skillsByKey = Map.of();

    public record SkillManifest(
            String skillKey,
            String semanticVersion,
            String checksum,
            String status,
            String domain,
            String title,
            String description,
            List<String> supportedRoutes,
            List<String> capabilities,
            String promptTemplate,
            String promptVersion,
            List<String> allowedToolGroups,
            Map<String, Object> budgetPolicy,
            String evaluatorKey,
            String hitlPolicy,
            boolean userConfigurable,
            Map<String, String> resources
    ) {
        public SkillManifest {
            supportedRoutes = supportedRoutes == null ? List.of() : List.copyOf(supportedRoutes);
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            allowedToolGroups = allowedToolGroups == null ? List.of() : List.copyOf(allowedToolGroups);
            budgetPolicy = budgetPolicy == null ? Map.of() : Map.copyOf(budgetPolicy);
            resources = resources == null ? Map.of() : Map.copyOf(resources);
        }
    }

    public record UserSkillView(SkillManifest manifest, boolean enabled) {
    }

    @PostConstruct
    void initialize() {
        List<SkillManifest> manifests = loadCatalog();
        this.publishedSkills = List.copyOf(manifests);
        LinkedHashMap<String, SkillManifest> byKey = new LinkedHashMap<>();
        manifests.forEach(manifest -> byKey.put(manifest.skillKey(), manifest));
        this.skillsByKey = Map.copyOf(byKey);
    }

    public BusinessSkillSelection resolveSelection(AgentRunRequest request) {
        if (request == null || !properties.getAgent().isBusinessSkillsEnabled()) {
            return BusinessSkillSelection.empty();
        }
        if (Boolean.TRUE.equals(request.variables().get("review_completed"))) {
            return BusinessSkillSelection.empty();
        }
        if (request.businessSkillSelection() != null) {
            return selectionForKeys(request.businessSkillSelection().skillKeys());
        }
        return selectionForTask(request.taskType());
    }

    public BusinessSkillSelection selectionForTask(AgentTaskType taskType) {
        return selectionForKeys(DEFAULT_TASK_SKILLS.getOrDefault(taskType, List.of()));
    }

    public BusinessSkillSelection selectionForNovelMode(NovelBusinessSkillMode mode) {
        NovelBusinessSkillMode effectiveMode = mode == null ? NovelBusinessSkillMode.NONE : mode;
        return selectionForKeys(NOVEL_MODE_SKILLS.getOrDefault(effectiveMode, List.of()));
    }

    public BusinessSkillSelection selectionForKeys(List<String> skillKeys) {
        if (!properties.getAgent().isBusinessSkillsEnabled() || skillKeys == null || skillKeys.isEmpty()) {
            return BusinessSkillSelection.empty();
        }
        return new BusinessSkillSelection(skillKeys.stream().map(this::requirePublished).toList());
    }

    public List<UserSkillView> listForUser(Long userId) {
        boolean enabled = properties.getAgent().isBusinessSkillsEnabled();
        return publishedSkills.stream()
                .map(manifest -> new UserSkillView(manifest, enabled))
                .toList();
    }

    public UserSkillView setEnabled(Long userId, String skillKey, boolean enabled) {
        requirePublished(skillKey);
        throw new BusinessException(400, "Business agent skills are platform-managed and cannot be toggled per user");
    }

    public List<SkillManifest> publishedSkills() {
        return publishedSkills;
    }

    public SkillManifest requirePublished(String skillKey) {
        SkillManifest manifest = skillsByKey.get(skillKey);
        if (manifest == null) {
            throw new BusinessException(503, "Published business Skill is unavailable: " + skillKey);
        }
        return manifest;
    }

    private List<SkillManifest> loadCatalog() {
        Map<String, Object> root = readYaml(CATALOG);
        List<Map<String, Object>> skillEntries = asMapList(root.get("skills"), "skills");
        List<SkillManifest> manifests = new ArrayList<>();
        LinkedHashSet<String> seenKeys = new LinkedHashSet<>();
        for (Map<String, Object> entry : skillEntries) {
            String directory = requireText(entry, "directory", "catalog entry");
            SkillManifest manifest = loadManifest(directory);
            if (!seenKeys.add(manifest.skillKey())) {
                throw new IllegalStateException("Duplicate business skill key: " + manifest.skillKey());
            }
            manifests.add(manifest);
        }
        return manifests;
    }

    private SkillManifest loadManifest(String directory) {
        String normalizedDirectory = normalizeRootRelativePath("", directory, "directory");
        Map<String, Object> yaml = readYaml(ROOT + normalizedDirectory + "/skill.yml");
        String skillKey = requireText(yaml, "key", normalizedDirectory);
        String version = requireText(yaml, "version", skillKey);
        String domain = requireText(yaml, "domain", skillKey);
        if (!"manga".equals(domain) && !"novel".equals(domain)) {
            throw new IllegalStateException("Unsupported business skill domain for " + skillKey + ": " + domain);
        }
        String title = requireText(yaml, "title", skillKey);
        String description = requireText(yaml, "description", skillKey);
        String promptVersion = optionalText(yaml, "promptVersion");
        String contentPath = normalizeRootRelativePath(normalizedDirectory, requireText(yaml, "content", skillKey), "content");
        ensureMarkdownPath(contentPath, skillKey, "content");
        String promptTemplate = readText(ROOT + contentPath);
        if (promptTemplate.isBlank()) {
            throw new IllegalStateException("Business skill content is blank: " + skillKey);
        }
        if (promptTemplate.length() > 50_000) {
            throw new IllegalStateException("Business skill content is too large: " + skillKey);
        }

        LinkedHashMap<String, String> resources = new LinkedHashMap<>();
        for (String reference : asStringList(yaml.get("references"))) {
            String normalizedReference = normalizeRootRelativePath(normalizedDirectory, reference, "reference");
            ensureMarkdownPath(normalizedReference, skillKey, "reference");
            String resourceText = readText(ROOT + normalizedReference);
            if (resourceText.isBlank()) {
                throw new IllegalStateException("Business skill reference is blank: " + normalizedReference);
            }
            resources.put(normalizedReference, resourceText);
        }

        List<String> allowedToolGroups = asStringList(yaml.get("allowedToolGroups"));
        for (String toolGroup : allowedToolGroups) {
            if (!ALLOWED_TOOL_GROUPS.contains(toolGroup)) {
                throw new IllegalStateException("Unsupported tool group " + toolGroup + " for business skill " + skillKey);
            }
        }

        String checksum = checksum(skillKey, version, title, description, promptTemplate, resources,
                asStringList(yaml.get("supportedRoutes")), asStringList(yaml.get("capabilities")),
                allowedToolGroups, asObjectMap(yaml.get("budgetPolicy")));
        return new SkillManifest(
                skillKey,
                version,
                checksum,
                PUBLISHED,
                domain,
                title,
                description,
                asStringList(yaml.get("supportedRoutes")),
                asStringList(yaml.get("capabilities")),
                promptTemplate,
                promptVersion == null || promptVersion.isBlank() ? version : promptVersion,
                allowedToolGroups,
                asObjectMap(yaml.get("budgetPolicy")),
                optionalText(yaml, "evaluatorKey"),
                optionalText(yaml, "hitlPolicy") == null ? "NONE" : optionalText(yaml, "hitlPolicy"),
                Boolean.TRUE.equals(yaml.get("userConfigurable")),
                resources
        );
    }

    private Map<String, Object> readYaml(String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("Missing business skill resource: " + location);
        }
        try (InputStream input = resource.getInputStream()) {
            Object loaded = new Yaml().load(input);
            if (!(loaded instanceof Map<?, ?> raw)) {
                throw new IllegalStateException("Invalid YAML root for " + location);
            }
            LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
            raw.forEach((key, value) -> normalized.put(String.valueOf(key), value));
            return Map.copyOf(normalized);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read business skill resource: " + location, error);
        }
    }

    private String readText(String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("Missing business skill text resource: " + location);
        }
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read business skill text resource: " + location, error);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asObjectMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalStateException("Expected object map but got: " + value);
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        raw.forEach((key, item) -> normalized.put(String.valueOf(key), item));
        return Map.copyOf(normalized);
    }

    private List<String> asStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> raw)) {
            throw new IllegalStateException("Expected list but got: " + value);
        }
        return raw.stream().map(String::valueOf).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private List<Map<String, Object>> asMapList(Object value, String fieldName) {
        if (!(value instanceof List<?> raw)) {
            throw new IllegalStateException("Expected list for " + fieldName);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> rawMap)) {
                throw new IllegalStateException("Expected object entry inside " + fieldName);
            }
            LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
            rawMap.forEach((key, entryValue) -> normalized.put(String.valueOf(key), entryValue));
            results.add(Map.copyOf(normalized));
        }
        return List.copyOf(results);
    }

    private String requireText(Map<String, Object> source, String field, String scope) {
        String value = optionalText(source, field);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required field " + field + " for " + scope);
        }
        return value;
    }

    private String optionalText(Map<String, Object> source, String field) {
        Object value = source.get(field);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private String normalizeRootRelativePath(String baseDirectory, String candidate, String label) {
        String rawCandidate = candidate == null ? "" : candidate.replace('\\', '/').trim();
        if (rawCandidate.isBlank()) {
            throw new IllegalStateException("Business skill " + label + " path is blank");
        }
        Path base = baseDirectory == null || baseDirectory.isBlank() ? Path.of("") : Path.of(baseDirectory);
        Path resolved = base.resolve(rawCandidate).normalize();
        String normalized = resolved.toString().replace('\\', '/');
        if (normalized.startsWith("..") || normalized.contains("/../") || normalized.startsWith("/")) {
            throw new IllegalStateException("Business skill " + label + " path escapes skill root: " + candidate);
        }
        if (normalized.contains("/scripts/") || normalized.endsWith("/scripts")
                || normalized.endsWith(".sh") || normalized.endsWith(".py") || normalized.endsWith(".js")) {
            throw new IllegalStateException("Executable resources are not allowed in runtime business skills: " + candidate);
        }
        return normalized;
    }

    private void ensureMarkdownPath(String normalizedPath, String skillKey, String label) {
        if (!normalizedPath.endsWith(".md")) {
            throw new IllegalStateException("Business skill " + skillKey + " " + label + " must be markdown: " + normalizedPath);
        }
    }

    private String checksum(String skillKey,
                            String version,
                            String title,
                            String description,
                            String promptTemplate,
                            Map<String, String> resources,
                            List<String> supportedRoutes,
                            List<String> capabilities,
                            List<String> allowedToolGroups,
                            Map<String, Object> budgetPolicy) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, skillKey);
            updateDigest(digest, version);
            updateDigest(digest, title);
            updateDigest(digest, description);
            updateDigest(digest, promptTemplate);
            supportedRoutes.forEach(item -> updateDigest(digest, item));
            capabilities.forEach(item -> updateDigest(digest, item));
            allowedToolGroups.forEach(item -> updateDigest(digest, item));
            budgetPolicy.forEach((key, value) -> {
                updateDigest(digest, key);
                updateDigest(digest, String.valueOf(value));
            });
            resources.forEach((key, value) -> {
                updateDigest(digest, key);
                updateDigest(digest, value);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception error) {
            throw new IllegalStateException("Failed to compute business skill checksum for " + skillKey, error);
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }
}
