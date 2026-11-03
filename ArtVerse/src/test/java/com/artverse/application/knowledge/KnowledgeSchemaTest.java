package com.artverse.application.knowledge;

import com.artverse.common.BusinessException;
import com.artverse.domain.KnowledgeUnitType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class KnowledgeSchemaTest {
    final KnowledgeSchema schema=new KnowledgeSchema(new ObjectMapper());
    @Test void optionalForeshadowFieldsDoNotBlockAndAreNeverInvented() {
        var normalized=schema.normalize(KnowledgeUnitType.FORESHADOWING,Map.of("setup","玉佩微光","extension","保留"));
        assertThat(normalized).containsEntry("status","UNKNOWN").containsEntry("extension","保留");
        assertThat(normalized.get("expected_resolution_chapter_id")).isNull();
        assertThat(schema.issues(KnowledgeUnitType.FORESHADOWING,"玉佩","一枚玉佩出现微光",normalized)).isEmpty();
    }
    @Test void emptyObjectInvalidTypesAndBrokenJsonCannotBypassChecks() {
        assertThat(schema.issues(KnowledgeUnitType.FORESHADOWING,"玉佩","正文",Map.of())).extracting(KnowledgeSchema.Issue::path).contains("setup");
        assertThat(schema.issues(KnowledgeUnitType.FORESHADOWING,"玉佩","正文",Map.of("setup",false))).extracting(KnowledgeSchema.Issue::code).contains("TYPE");
        for(String corrupt:List.of("null","[]","{","0")) assertThatThrownBy(()->schema.read(corrupt)).isInstanceOf(BusinessException.class);
    }
    @Test void legacyStateMappingRequiresExplicitSetupEvidence() {
        assertThat(schema.normalize(KnowledgeUnitType.FORESHADOWING,Map.of("status","PENDING","setup","玉佩"))).containsEntry("status","UNKNOWN");
        assertThat(schema.normalize(KnowledgeUnitType.FORESHADOWING,Map.of("status","PENDING","setup","玉佩","setup_chapter_id",2))).containsEntry("status","SEEDED");
        assertThat(schema.normalize(KnowledgeUnitType.FORESHADOWING,Map.of("status","RECOVERED"))).containsEntry("status","RESOLVED");
    }
    @Test void extractedTextListsBecomeEditableProseAndEmptyListsRemainUnknown() {
        var raw=Map.<String,Object>of("subtype","RULE","limits",List.of("仅限夜间","需接触目标"),"costs",List.of(),"exceptions",List.of(),"extension",Map.of("keep",true));
        var normalized=schema.normalize(KnowledgeUnitType.WORLDVIEW,raw);
        assertThat(normalized).containsEntry("limits","仅限夜间\n需接触目标").containsEntry("costs",null).containsEntry("exceptions",null).containsEntry("extension",raw.get("extension"));
        assertThat(schema.issues(KnowledgeUnitType.WORLDVIEW,"规则","已知规则",raw)).isEmpty();
        assertThat(schema.normalize(KnowledgeUnitType.WORLDVIEW,normalized)).isEqualTo(normalized);
        assertThat(raw.get("costs")).isEqualTo(List.of());
    }
    @Test void jsonProseAndObjectsAreDecodedButLiteralBracketsAndTextArePreserved() {
        assertThat(schema.normalizeField("text","[\"限制一\",\"限制二\"]")).isEqualTo("限制一\n限制二");
        assertThat(schema.normalizeField("text","{\"条件\":\"月圆\",\"说明\":[\"不能远离\",\"持续一刻\"]}")).isEqualTo("条件：月圆\n说明：不能远离\n持续一刻");
        assertThat(schema.normalizeField("text","[]")).isNull();
        assertThat(schema.normalizeField("text","[这一段只是普通正文]")).isEqualTo("[这一段只是普通正文]");
        assertThat(schema.normalizeField("text","  普通文字\n第二行  ")).isEqualTo("  普通文字\n第二行  ");
        assertThat(schema.normalizeField("text","null")).isEqualTo("null");
    }
    @Test void stringListsAcceptMultilineFormsAndJsonWithoutGuessingReferenceIds() {
        assertThat(schema.normalizeField("strings","阿舟\n小舟\n")).isEqualTo(List.of("阿舟","小舟"));
        assertThat(schema.normalizeField("strings","[\"阿舟\",\"小舟\"]")).isEqualTo(List.of("阿舟","小舟"));
        var invalid=Map.<String,Object>of("setup","玉佩","related_character_ids","林舟");
        assertThat(schema.issues(KnowledgeUnitType.FORESHADOWING,"玉佩","正文",invalid)).extracting(KnowledgeSchema.Issue::path).contains("related_character_ids");
        assertThat(schema.issues(KnowledgeUnitType.FORESHADOWING,"玉佩","正文",Map.of("setup",List.of()))).extracting(KnowledgeSchema.Issue::code).contains("REQUIRED");
    }
    @Test void canonicalHashIncludesNestedKeysAndDoesNotDependOnMapInsertionOrder() {
        var a=new LinkedHashMap<String,Object>();a.put("b",1);a.put("a",Map.of("z",2,"x",3));
        var b=new LinkedHashMap<String,Object>();b.put("a",Map.of("x",3,"z",2));b.put("b",1);
        assertThat(schema.hash(a)).isEqualTo(schema.hash(b));b.put("importance",5);assertThat(schema.hash(a)).isNotEqualTo(schema.hash(b));
    }
    @Test void relationDirectionAndSymmetricCanonicalizationUseStableIds() {
        var symmetric=schema.normalize(KnowledgeUnitType.CHARACTER_RELATION,Map.of("source_character_id",9,"target_character_id",2,"direction","SYMMETRIC","relation_type","朋友"));
        assertThat(symmetric).containsEntry("source_character_id",2L).containsEntry("target_character_id",9L);
        assertThat(schema.issues(KnowledgeUnitType.CHARACTER_RELATION,"关系","描述",Map.of("source_character_id",2,"target_character_id",2,"relation_type","信任"))).extracting(KnowledgeSchema.Issue::code).contains("SELF_RELATION");
    }
}
