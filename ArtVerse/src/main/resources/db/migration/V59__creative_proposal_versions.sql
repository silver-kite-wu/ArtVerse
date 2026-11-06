-- All JPA authoring paths participate in the same optimistic concurrency contract.
ALTER TABLE stories ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE character_profiles ADD COLUMN version bigint NOT NULL DEFAULT 0;
