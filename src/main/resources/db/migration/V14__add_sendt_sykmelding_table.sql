CREATE TABLE sendt_sykmelding
(
    id                     SERIAL PRIMARY KEY,
    sykmelding_id          VARCHAR(50)                                        NOT NULL,
    orgnummer              VARCHAR(9)                                         NOT NULL,
    syketilfelle_startdato DATE,
    fnr                    VARCHAR                                            NOT NULL,
    fom                    DATE                                               NOT NULL,
    tom                    DATE                                               NOT NULL,
    created                TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated                TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);
