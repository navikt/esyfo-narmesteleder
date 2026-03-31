CREATE TABLE person
(
    id         SERIAL PRIMARY KEY,
    fnr        VARCHAR(11)              UNIQUE NOT NULL,
    fornavn    VARCHAR(255),
    etternavn  VARCHAR(255),
    status     VARCHAR(255)             NOT NULL,
    created    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TRIGGER update_person_updated
    BEFORE UPDATE
    ON person
    FOR EACH ROW
EXECUTE FUNCTION update_updated_column();
