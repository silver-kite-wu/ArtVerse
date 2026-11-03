-- Additive migration: old material remains author-visible and explicitly unscoped.
ALTER TABLE knowledge_units ADD COLUMN schema_version integer NOT NULL DEFAULT 1;
ALTER TABLE knowledge_candidates ADD COLUMN schema_version integer NOT NULL DEFAULT 1;
ALTER TABLE knowledge_candidates ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE knowledge_candidates ADD COLUMN target_unit_id bigint REFERENCES knowledge_units(id) ON DELETE SET NULL;
ALTER TABLE knowledge_candidates ADD COLUMN base_version integer;
ALTER TABLE knowledge_candidates ADD COLUMN source_hash varchar(64);
ALTER TABLE knowledge_candidates ADD COLUMN source_snapshot jsonb NOT NULL DEFAULT '{}';

CREATE TABLE knowledge_entities (
 id bigserial PRIMARY KEY, story_id bigint NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
 kind varchar(40) NOT NULL, name text NOT NULL,
 character_profile_id bigint UNIQUE REFERENCES character_profiles(id) ON DELETE RESTRICT,
 aliases jsonb NOT NULL DEFAULT '[]', archived boolean NOT NULL DEFAULT false,
 UNIQUE(id, story_id)
);
CREATE INDEX ix_knowledge_entities_story ON knowledge_entities(story_id, kind);
INSERT INTO knowledge_entities(story_id,kind,name,character_profile_id)
 SELECT story_id,'CHARACTER',name,id FROM character_profiles;

CREATE TABLE knowledge_facts (
 id bigserial PRIMARY KEY, story_id bigint NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
 unit_id bigint NOT NULL REFERENCES knowledge_units(id) ON DELETE CASCADE,
 kind varchar(40) NOT NULL, business_key text NOT NULL,
 current_revision integer NOT NULL DEFAULT 1, archived boolean NOT NULL DEFAULT false,
 UNIQUE(story_id,business_key), UNIQUE(id,story_id)
);
CREATE INDEX ix_knowledge_facts_unit ON knowledge_facts(unit_id,kind);
CREATE TABLE knowledge_fact_revisions (
 id bigserial PRIMARY KEY, fact_id bigint NOT NULL, story_id bigint NOT NULL,
 revision integer NOT NULL, payload jsonb NOT NULL,
 reveal_chapter_id bigint REFERENCES chapters(id) ON DELETE RESTRICT,
 effective_from_chapter_id bigint REFERENCES chapters(id) ON DELETE RESTRICT,
 effective_to_chapter_id bigint REFERENCES chapters(id) ON DELETE RESTRICT,
 certainty varchar(30) NOT NULL DEFAULT 'FACT', scope varchar(30) NOT NULL DEFAULT 'SCOPED',
 approved_by bigint REFERENCES users(id) ON DELETE SET NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(fact_id,revision), UNIQUE(id,story_id),
 FOREIGN KEY(fact_id,story_id) REFERENCES knowledge_facts(id,story_id) ON DELETE CASCADE,
 CHECK(certainty IN ('FACT','PLAN','RUMOR','BELIEF')),
 CHECK(scope IN ('SCOPED','LEGACY_UNSCOPED','UNLOCATED'))
);
CREATE INDEX ix_knowledge_fact_visibility ON knowledge_fact_revisions(story_id,reveal_chapter_id);
CREATE TABLE knowledge_fact_entity_links (
 revision_id bigint NOT NULL, story_id bigint NOT NULL, entity_id bigint NOT NULL,
 role varchar(40) NOT NULL,
 PRIMARY KEY(revision_id,entity_id,role),
 FOREIGN KEY(revision_id,story_id) REFERENCES knowledge_fact_revisions(id,story_id) ON DELETE CASCADE,
 FOREIGN KEY(entity_id,story_id) REFERENCES knowledge_entities(id,story_id) ON DELETE RESTRICT
);
CREATE INDEX ix_knowledge_fact_entity ON knowledge_fact_entity_links(entity_id,role);
CREATE TABLE knowledge_fact_links (
 revision_id bigint NOT NULL, story_id bigint NOT NULL, target_fact_id bigint NOT NULL, role varchar(40) NOT NULL,
 PRIMARY KEY(revision_id,target_fact_id,role),
 FOREIGN KEY(revision_id,story_id) REFERENCES knowledge_fact_revisions(id,story_id) ON DELETE CASCADE,
 FOREIGN KEY(target_fact_id,story_id) REFERENCES knowledge_facts(id,story_id) ON DELETE RESTRICT
);
CREATE TABLE knowledge_sources (
 id bigserial PRIMARY KEY, story_id bigint NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
 source_type varchar(40) NOT NULL, chapter_id bigint REFERENCES chapters(id) ON DELETE RESTRICT,
 source_version text, source_hash varchar(64) NOT NULL, snapshot jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(id,story_id)
);
CREATE TABLE knowledge_fact_sources (
 revision_id bigint NOT NULL, story_id bigint NOT NULL, source_id bigint NOT NULL,
 excerpt text NOT NULL DEFAULT '', location text,
 PRIMARY KEY(revision_id,source_id),
 FOREIGN KEY(revision_id,story_id) REFERENCES knowledge_fact_revisions(id,story_id) ON DELETE CASCADE,
 FOREIGN KEY(source_id,story_id) REFERENCES knowledge_sources(id,story_id) ON DELETE RESTRICT
);
CREATE TABLE character_reference_images (
 id bigserial PRIMARY KEY, story_id bigint NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
 character_profile_id bigint NOT NULL REFERENCES character_profiles(id) ON DELETE CASCADE,
 object_key text NOT NULL UNIQUE, is_cover boolean NOT NULL DEFAULT false, sort_order integer NOT NULL DEFAULT 0,
 caption text NOT NULL DEFAULT '', appearance_version text NOT NULL DEFAULT '',
 reveal_chapter_id bigint REFERENCES chapters(id) ON DELETE RESTRICT,
 from_chapter_id bigint REFERENCES chapters(id) ON DELETE RESTRICT,
 to_chapter_id bigint REFERENCES chapters(id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX uq_character_reference_cover ON character_reference_images(character_profile_id) WHERE is_cover;
ALTER TABLE knowledge_unit_chunks ADD COLUMN fact_revision_id bigint REFERENCES knowledge_fact_revisions(id) ON DELETE CASCADE;
ALTER TABLE knowledge_units ADD CONSTRAINT uq_knowledge_unit_story UNIQUE(id,story_id);
ALTER TABLE chapters ADD CONSTRAINT uq_knowledge_chapter_story UNIQUE(id,story_id);
ALTER TABLE character_profiles ADD CONSTRAINT uq_knowledge_character_story UNIQUE(id,story_id);
ALTER TABLE knowledge_facts ADD CONSTRAINT fk_knowledge_fact_unit_story FOREIGN KEY(unit_id,story_id) REFERENCES knowledge_units(id,story_id) ON DELETE CASCADE;
ALTER TABLE knowledge_entities ADD CONSTRAINT fk_knowledge_entity_character_story FOREIGN KEY(character_profile_id,story_id) REFERENCES character_profiles(id,story_id);
ALTER TABLE knowledge_fact_revisions ADD CONSTRAINT fk_knowledge_reveal_story FOREIGN KEY(reveal_chapter_id,story_id) REFERENCES chapters(id,story_id);
ALTER TABLE knowledge_fact_revisions ADD CONSTRAINT fk_knowledge_from_story FOREIGN KEY(effective_from_chapter_id,story_id) REFERENCES chapters(id,story_id);
ALTER TABLE knowledge_fact_revisions ADD CONSTRAINT fk_knowledge_to_story FOREIGN KEY(effective_to_chapter_id,story_id) REFERENCES chapters(id,story_id);
ALTER TABLE knowledge_sources ADD CONSTRAINT fk_knowledge_source_chapter_story FOREIGN KEY(chapter_id,story_id) REFERENCES chapters(id,story_id);
ALTER TABLE character_reference_images ADD CONSTRAINT fk_knowledge_image_character_story FOREIGN KEY(character_profile_id,story_id) REFERENCES character_profiles(id,story_id) ON DELETE CASCADE;

-- Preserve legacy roots without fabricating a chronology or splitting biographies by guesswork.
INSERT INTO knowledge_facts(story_id,unit_id,kind,business_key)
 SELECT story_id,id,CASE type WHEN 'CHARACTER_CARD' THEN 'CHARACTER_CORE'
 WHEN 'CHARACTER_RELATION' THEN 'RELATION_STATE' WHEN 'TIMELINE' THEN 'EVENT'
 WHEN 'FORESHADOWING' THEN 'FORESHADOWING' ELSE 'WORLD_DEFINITION' END,'root:'||id FROM knowledge_units;
INSERT INTO knowledge_fact_revisions(fact_id,story_id,revision,payload,scope)
 SELECT f.id,f.story_id,1,jsonb_build_object('title',u.title,'body',u.body,'summary',u.summary,
 'structured',u.structured_data,'importance',u.importance),'LEGACY_UNSCOPED'
 FROM knowledge_facts f JOIN knowledge_units u ON u.id=f.unit_id;
INSERT INTO knowledge_fact_entity_links(revision_id,story_id,entity_id,role)
 SELECT r.id,r.story_id,e.id,'CHARACTER' FROM knowledge_fact_revisions r
 JOIN knowledge_facts f ON f.id=r.fact_id JOIN knowledge_units u ON u.id=f.unit_id
 JOIN knowledge_entities e ON e.character_profile_id=u.character_profile_id;
