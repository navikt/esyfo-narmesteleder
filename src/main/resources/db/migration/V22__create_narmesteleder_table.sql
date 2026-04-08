CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE narmeste_leder
(
    id                           SERIAL PRIMARY KEY,
    narmeste_leder_id            UUID                     NOT NULL,
    orgnummer                    VARCHAR(9)               NOT NULL,
    bruker_fnr                   VARCHAR(11)              NOT NULL,
    narmeste_leder_fnr           VARCHAR(11)              NOT NULL,
    narmeste_leder_telefonnummer VARCHAR                  NOT NULL,
    narmeste_leder_epost         VARCHAR                  NOT NULL,
    arbeidsgiver_forskutterer    BOOLEAN,
    aktiv_fom                    TIMESTAMP WITH TIME ZONE NOT NULL,
    aktiv_tom                    TIMESTAMP WITH TIME ZONE,
    created                      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated                      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX narmeste_leder_id_idx ON narmeste_leder (narmeste_leder_id);
CREATE INDEX narmeste_leder_fnr_idx ON narmeste_leder (bruker_fnr);
CREATE INDEX narmeste_leder_nlfnr_idx ON narmeste_leder (narmeste_leder_fnr);

-- Create a function to update the updated column
CREATE OR REPLACE FUNCTION update_updated_column()
    RETURNS TRIGGER AS
$$
BEGIN
    NEW.updated = now();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_narmeste_leder_updated
    BEFORE UPDATE
    ON narmeste_leder
    FOR EACH ROW
EXECUTE FUNCTION update_updated_column();
