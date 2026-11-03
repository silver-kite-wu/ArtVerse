package com.artverse.application.knowledge;

import com.artverse.common.BusinessException;
import com.artverse.domain.KnowledgeUnitType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Single source of field names, validation and legacy normalization. No invented plot facts. */
@Component
public class KnowledgeSchema {
    public static final int VERSION = 2;
    private final ObjectMapper json;
    public KnowledgeSchema(ObjectMapper mapper) {
        this.json = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }
    public record Issue(String path, String code, String message, String severity) {}
    public record Field(String key, String label, String type, boolean required, List<String> options) {}
    public record TypeSchema(String type, int version, List<Field> fields) {}
    private static Field f(String key, String label, String type, boolean required, String... options) {
        return new Field(key,label,type,required,List.of(options));
    }
    public List<TypeSchema> schemas() {
        return List.of(
            new TypeSchema("CHARACTER_CARD",VERSION,List.of(
                f("character_id","绑定人物（空则新建人物）","character",false),f("aliases","别名","strings",false),
                f("first_appearance_chapter_id","首次出场章节","chapter",false),f("exit_chapter_id","退场章节","chapter",false),
                f("exit_reason","退场原因","text",false),f("lifecycle","人物状态","select",false,"UNKNOWN","ACTIVE","ABSENT","EXITED","DEAD"),
                f("appearance","外貌","text",false),f("personality","性格","text",false),f("motivation","动机与目标","text",false),
                f("abilities","能力","text",false),f("limitations","限制与代价","text",false),f("taboos","禁忌","text",false))),
            new TypeSchema("CHARACTER_RELATION",VERSION,List.of(
                f("source_character_id","起点人物","character",true),f("target_character_id","终点人物","character",true),
                f("relation_type","关系类型","text",true),f("label","关系含义（起点对终点）","text",true),
                f("direction","方向","select",true,"DIRECTED","SYMMETRIC"),f("stage","阶段","text",false),
                f("public_information","公开信息","text",false),f("hidden_information","隐藏信息","text",false),f("known_by","知道者","characters",false))),
            new TypeSchema("WORLDVIEW",VERSION,List.of(
                f("subtype","资料分类","select",true,"PLACE","ORGANIZATION","ITEM","CURRENCY","POWER_SYSTEM","RULE","HISTORY"),
                f("definition","定义与规则","text",false),f("limits","限制","text",false),f("costs","代价","text",false),
                f("exceptions","例外","text",false),f("unit","单位","text",false),f("exchange_rule","明确兑换规则","text",false),
                f("place_type","地点类型","text",false),f("parent_entity_id","上级地点 / 组织","entity",false),f("travel_rules","可达地点与已知耗时","text",false),f("hazards","危险与禁区","text",false),
                f("aliases","别名","strings",false),f("goals","组织目标","text",false),f("hierarchy","层级与职务","text",false),f("headquarters_entity_id","据点","entity",false),
                f("category","物品类别","text",false),f("effects","功效","text",false),f("rarity","稀有度","text",false),f("stacking","堆叠 / 唯一性","select",false,"STACKABLE","UNIQUE","UNKNOWN"),
                f("quantity_precision","小数精度","number",false),f("rank_sequence","等级序列（由低到高，每行一阶）","strings",false),f("breakthrough_conditions","突破条件","text",false),f("scope","适用范围","text",false),f("taboos","禁忌","text",false))),
            new TypeSchema("TIMELINE",VERSION,List.of(
                f("event_type","事件类型","text",false),f("story_time","故事发生时间（保留原文）","text",false),
                f("calendar_id","历法","text",false),f("relative_anchor_event_id","相对时间锚点事件","event",false),
                f("story_order","发生顺序（可空）","number",false),f("time_uncertainty","精度与不确定性","text",false),
                f("narrative_mode","叙事方式","select",false,"OCCURRENCE","MENTION","FLASHBACK","DREAM","RUMOR"),
                f("participant_character_ids","参与人物","characters",false),f("location_entity_id","地点","entity",false),
                f("storyline","故事线","text",false),f("result","结果","text",false),
                f("start_time","开始时间（原文）","text",false),f("end_time","结束时间（原文）","text",false),f("scene_order","章内叙事顺序","number",false),
                f("participant_roles","参与者作用","roles",false),f("related_entity_ids","相关组织 / 物品","entities",false),
                f("cause_event_ids","前因事件","events",false),f("follows_event_ids","发生在这些事件之后","events",false),f("depends_on_event_ids","前置依赖事件","events",false),f("causal_mode","时间约束","select",false,"STRICT","TIME_LOOP"))),
            new TypeSchema("FORESHADOWING",VERSION,List.of(
                f("setup","伏笔描述","text",true),f("status","剧情状态","select",false,"UNKNOWN","DRAFT_PLAN","SEEDED","DEVELOPING","PARTIALLY_RESOLVED","RESOLVED","ABANDONED"),
                f("setup_chapter_id","首次埋设章节","chapter",false),f("reader_question","读者疑问与承诺","text",false),
                f("intended_payoff","作者预期回收（计划）","text",false),f("expected_resolution_chapter_id","预计回收章节（计划）","chapter",false),
                f("resolution_condition","回收条件（可空）","text",false),f("resolved_event_id","实际回收事件","event",false),
                f("resolution_evidence","实际回收证据","text",false),f("related_character_ids","相关人物","characters",false),
                f("foreshadowing_type","伏笔类型","text",false),f("storyline","故事线 / 篇章","text",false),f("surface_presentation","表层呈现","text",false),
                f("setup_event_id","埋设事件","event",false),f("related_entity_ids","相关地点 / 物品","entities",false),f("known_by","知道者","characters",false),
                f("expected_resolution_end_chapter_id","预计回收窗口截止（计划）","chapter",false),f("reminder_after_chapters","多少章未推进时提醒（可空）","number",false),f("resolution_explanation","回收如何解释已有线索","text",false)))
        );
    }
    public Map<String,Object> normalize(KnowledgeUnitType type, Map<String,Object> input) {
        Map<String,Object> result = new LinkedHashMap<>(input == null ? Map.of() : input);
        for (Field field : schemas().stream().filter(s -> s.type().equals(type.name())).findFirst().orElseThrow().fields()) {
            if (!result.containsKey(field.key())) continue;
            Object value = normalizeField(field.type(),result.get(field.key()));
            result.put(field.key(),value);
            if (value instanceof String text && text.isBlank()) result.put(field.key(), null);
            if (Set.of("chapter","character","entity","event","number").contains(field.type()) && value instanceof String text && !text.isBlank()) {
                try { result.put(field.key(), new BigDecimal(text)); } catch (NumberFormatException ignored) { /* reported below */ }
            }
        }
        if (type == KnowledgeUnitType.FORESHADOWING) {
            String status = text(result,"status").toUpperCase(Locale.ROOT);
            if (status.isBlank()) status = "UNKNOWN";
            if (status.equals("RECOVERED")) status = "RESOLVED";
            if (status.equals("PENDING")) status = result.get("setup_chapter_id") != null && !text(result,"setup").isBlank() ? "SEEDED" : "UNKNOWN";
            result.put("status",status);
            result.putIfAbsent("expected_resolution_chapter_id",null);
            result.putIfAbsent("resolution_condition",null);
        }
        if (type == KnowledgeUnitType.CHARACTER_RELATION) {
            result.putIfAbsent("direction","DIRECTED");
            if (!result.containsKey("label") && !text(result,"relation_type").isBlank()) result.put("label",result.get("relation_type"));
            if ("SYMMETRIC".equals(result.get("direction"))) {
                Long a = id(result.get("source_character_id")), b = id(result.get("target_character_id"));
                if (a != null && b != null && a > b) { result.put("source_character_id",b); result.put("target_character_id",a); }
            }
        }
        return result;
    }
    /** Text forms and JSON imports share one storage contract; reference IDs stay strict. */
    public Object normalizeField(String type,Object value) {
        if (!Set.of("text","strings").contains(type)) return value;
        if (value instanceof String text) {
            String trimmed=text.trim();
            if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                try { value=json.readValue(trimmed,Object.class); }
                catch (Exception ignored) { /* Brackets can be ordinary prose. Never discard it. */ }
            }
        }
        if (type.equals("text") && (value instanceof String || value instanceof List<?> || value instanceof Map<?,?>)) {
            String text=readableText(value);return text.isBlank()?null:text;
        }
        if (type.equals("strings")) {
            if(value instanceof String text) return text.lines().map(String::trim).filter(s->!s.isEmpty()).toList();
            if(value instanceof List<?> list && list.stream().allMatch(v->v==null || v instanceof String))
                return list.stream().filter(Objects::nonNull).map(Object::toString).map(String::trim).filter(s->!s.isEmpty()).toList();
        }
        return value;
    }
    private String readableText(Object value) {
        if(value==null) return "";
        if(value instanceof String text) return text;
        if(value instanceof List<?> list) return list.stream().map(this::readableText).filter(s->!s.isBlank()).reduce((a,b)->a+"\n"+b).orElse("");
        if(value instanceof Map<?,?> map) return map.entrySet().stream().map(e->{String text=readableText(e.getValue());return e.getKey()+"："+(text.isBlank()?"未填写":text);}).reduce((a,b)->a+"\n"+b).orElse("");
        return value.toString();
    }
    public List<Issue> issues(KnowledgeUnitType type, String title, String body, Map<String,Object> raw) {
        List<Issue> issues = new ArrayList<>();
        if (title == null || title.isBlank()) issues.add(issue("title","REQUIRED","请填写标题"));
        if (body == null || body.isBlank()) issues.add(issue("body","REQUIRED","请填写事实描述"));
        Map<String,Object> data = normalize(type, raw);
        for (Field field : schemas().stream().filter(s -> s.type().equals(type.name())).findFirst().orElseThrow().fields()) {
            Object value = data.get(field.key());
            if (field.required() && (value == null || value.toString().isBlank())) {
                issues.add(issue(field.key(),"REQUIRED","请补充" + field.label())); continue;
            }
            if (value == null) continue;
            boolean valid = switch (field.type()) {
                case "text" -> value instanceof String;
                case "select" -> value instanceof String && field.options().contains(value);
                case "strings" -> value instanceof List<?> list && list.stream().allMatch(v -> v instanceof String);
                case "characters","entities","events" -> value instanceof List<?> list && list.stream().allMatch(v -> id(v) != null);
                case "roles" -> value instanceof Map<?,?> roles && roles.entrySet().stream().allMatch(e->id(e.getKey())!=null && e.getValue() instanceof String);
                case "chapter","character","entity","event" -> id(value) != null;
                case "number" -> value instanceof Number;
                default -> true;
            };
            if (!valid) issues.add(issue(field.key(),"TYPE",switch(field.type()) {
                case "text","strings" -> "“"+field.label()+"”的内容暂时无法识别，请在对应输入框填写文字";
                case "select","chapter","character","entity","event","characters","entities","events" -> "请在“"+field.label()+"”中重新选择有效选项";
                case "number" -> "请在“"+field.label()+"”中填写数字";
                default -> "请检查“"+field.label()+"”的填写内容";
            }));
        }
        Long source = id(data.get("source_character_id")), target = id(data.get("target_character_id"));
        if (source != null && source.equals(target)) issues.add(issue("target_character_id","SELF_RELATION","关系起点和终点不能是同一人物"));
        if (type == KnowledgeUnitType.FORESHADOWING && Set.of("RESOLVED","PARTIALLY_RESOLVED").contains(text(data,"status"))) {
            if (id(data.get("resolved_event_id")) == null) issues.add(issue("resolved_event_id","REQUIRED","回收必须关联实际事件"));
            if (text(data,"resolution_evidence").isBlank()) issues.add(issue("resolution_evidence","REQUIRED","请填写实际回收证据"));
        }
        return issues;
    }
    public void requireValid(KnowledgeUnitType type,String title,String body,Map<String,Object> data) {
        List<Issue> issues = issues(type,title,body,data);
        if (!issues.isEmpty()) throw new BusinessException(400,issues.stream().map(i -> i.path()+"："+i.message()).reduce((a,b)->a+"；"+b).orElse("知识字段不完整"));
    }
    public Map<String,Object> read(String value) {
        try {
            Map<String,Object> result=json.readValue(value,new TypeReference<>() {});
            if (result == null) throw new IllegalArgumentException();
            return result;
        } catch (Exception e) { throw new BusinessException(400,"知识内容暂时无法读取，请重新打开；如仍失败，请重新生成候选"); }
    }
    public String write(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new BusinessException(400,"知识结构无法序列化"); }
    }
    public String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(value).getBytes(StandardCharsets.UTF_8))); }
        catch (BusinessException e) { throw e; } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static Long id(Object value) {
        if (value == null || value instanceof Boolean) return null;
        try { long n = new BigDecimal(value.toString()).longValueExact(); return n > 0 ? n : null; }
        catch (Exception e) { return null; }
    }
    public static String text(Map<String,Object> data,String key) { return Objects.toString(data.get(key),"").trim(); }
    public static Issue issue(String path,String code,String message) { return new Issue(path,code,message,"ERROR"); }
}
