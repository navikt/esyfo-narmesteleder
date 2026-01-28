CREATE TABLE sendt_sykmelding
(
    id                     SERIAL PRIMARY KEY,
    sykmelding_id          UUID UNIQUE                                        NOT NULL,
    orgnummer              VARCHAR(9)                                         NOT NULL,
    syketilfelle_startdato DATE,
    fnr                    VARCHAR                                            NOT NULL,
    fom                    DATE                                               NOT NULL,
    tom                    DATE                                               NOT NULL,
    revoked_date           DATE,
    created                TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated                TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);
-- Create a function to update the updated column
CREATE OR REPLACE FUNCTION update_updated_column()
    RETURNS TRIGGER AS
$$
BEGIN
    NEW.updated = now();
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Create the trigger
CREATE TRIGGER update_sendt_sykmelding_updated
    BEFORE UPDATE
    ON sendt_sykmelding
    FOR EACH ROW
EXECUTE FUNCTION update_updated_column();
