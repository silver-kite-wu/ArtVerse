package com.artverse.application.imagequeue;

import com.artverse.application.UserProviderConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;

/** Immutable inputs and small broker commands. Credentials exist only inside encrypted private manifests. */
public final class ImageProtocol {
    private ImageProtocol() {}
    public enum Status { QUEUED, LOAD_CONTEXT, GENERATING, UPLOADING, COMMITTING, RETRY_WAIT, SUCCEEDED, FAILED, STALE, NEEDS_REVIEW }
    public record Panel(UUID operationId, Integer imageNumber, String prompt, String sceneOverride,
                        Long expectedImageId, Long expectedImageVersion, String contextHash,
                        List<String> references, String inputHash) {}
    public record Manifest(int schemaVersion, long userId, UUID rootId, String submissionHash,
                           String operation, String executionMode, String executionLane, Long chapterId,
                           Long assetGroupId, UUID conversationId, UserProviderConfig provider,
                           String size, String colorMode, List<String> references, List<Panel> panels, OffsetDateTime createdAt) {
        public boolean serial() { return "SERIAL_MODEL".equals(executionLane); }
        public boolean chapter() { return "CHAPTER_SERIAL".equals(executionMode); }
        public String profileKey() { return hash(List.of(userId, Objects.toString(provider.configId(),"byok"),provider.provider(),provider.baseUrl(),provider.model())); }
    }
    public record Command(int schemaVersion, String kind, UUID eventId, long userId, UUID rootId,
                          UUID operationId, String manifestHash, int cursor, long generation,
                          long notBefore, int deferCount, UUID actionId) {
        public Command defer(long millis) { return new Command(schemaVersion,kind,eventId,userId,rootId,operationId,manifestHash,cursor,generation,System.currentTimeMillis()+millis,deferCount+1,actionId); }
        public Command nextControl() { return new Command(schemaVersion,kind,UUID.randomUUID(),userId,rootId,operationId,manifestHash,cursor,generation+1,0,0,actionId); }
    }
    public static UUID stable(Object... fields) { return UUID.nameUUIDFromBytes(hash(Arrays.asList(fields)).getBytes(StandardCharsets.UTF_8)); }
    public static String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ImageJson.write(value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String bytesHash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public static boolean terminal(String status) { return Set.of("SUCCEEDED","FAILED","STALE").contains(Objects.toString(status,"")); }
}
