ALTER TABLE person
    ADD COLUMN id_uuid UUID;

UPDATE person
SET id_uuid = uuidv7();

ALTER TABLE person
    ALTER COLUMN id_uuid SET NOT NULL;

ALTER TABLE person
    ALTER COLUMN id_uuid SET DEFAULT uuidv7();

ALTER TABLE person
    DROP CONSTRAINT person_pkey;

ALTER TABLE person
    DROP COLUMN id;

ALTER TABLE person
    RENAME COLUMN id_uuid TO id;

ALTER TABLE person
    ADD CONSTRAINT person_pkey PRIMARY KEY (id);
