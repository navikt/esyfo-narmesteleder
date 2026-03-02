CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE narmesteleder
(
    id                           SERIAL PRIMARY KEY,
    narmesteleder_id             UUID                       NOT NULL,
    orgnummer                    VARCHAR                    NOT NULL,
    bruker_fnr                   VARCHAR                    NOT NULL,
    bruker_navn                  VARCHAR,
    narmesteleder_navn           VARCHAR,
    narmesteleder_fnr            VARCHAR                    NOT NULL,
    narmesteleder_telefonnummer  VARCHAR                    NOT NULL,
    narmesteleder_epost          VARCHAR                    NOT NULL,
    arbeidsgiver_forskutterer    BOOLEAN,
    aktiv_fom                    TIMESTAMP WITH TIME ZONE   NOT NULL,
    aktiv_tom                    TIMESTAMP WITH TIME ZONE,
    created                      TIMESTAMP WITH TIME ZONE   NOT NULL DEFAULT now(),
    updated                      TIMESTAMP WITH TIME ZONE   NOT NULL DEFAULT now()
);

CREATE INDEX narmesteleder_id_idx ON narmesteleder (narmesteleder_id);
CREATE INDEX narmesteleder_fnr_idx ON narmesteleder (bruker_fnr);
CREATE INDEX narmesteleder_nlfnr_idx ON narmesteleder (narmesteleder_fnr);

-- Create a function to update the updated column
CREATE OR REPLACE FUNCTION update_updated_column()
    RETURNS TRIGGER AS
$$
BEGIN
    NEW.updated = now();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_narmesteleder_updated
    BEFORE UPDATE ON narmesteleder
    FOR EACH ROW
EXECUTE FUNCTION update_updated_column();
