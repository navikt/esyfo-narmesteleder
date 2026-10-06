-- Engangsopprydding før unik indeks på aktive behov (issue #605).
-- Auditen fant tre aktive duplikatpar. Alle radene har en levende dialog i Dialogporten.
-- Statusfiltrene gjør at migreringen ikke endrer noe hvis radene allerede er avsluttet.

-- Paret med 16cac3ed… har allerede registrert nærmeste leder. Begge radene oppfylles,
-- slik NlBehovLeesahHandler.updateStatusForRequirement gjør. Paret finnes via behov-ID-en,
-- slik at fødselsnummer ikke står i repoet.
UPDATE nl_behov b
SET behov_status = 'BEHOV_FULFILLED'
FROM nl_behov anchor
WHERE anchor.id = '16cac3ed-b483-4a26-b329-a809c310c0bd'
  AND anchor.behov_status IN ('BEHOV_CREATED', 'DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION')
  AND b.sykemeldt_fnr = anchor.sykemeldt_fnr
  AND b.orgnummer = anchor.orgnummer
  AND b.behov_status IN ('BEHOV_CREATED', 'DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION');

-- De to andre parene har ikke nærmeste leder. Den nyeste raden utløpes, og den eldste beholdes
-- fordi arbeidsgiver ble varslet om den først.
UPDATE nl_behov
SET behov_status = 'BEHOV_EXPIRED'
WHERE id IN (
    '0438ed71-7326-4cf8-a414-21c66b0be39e',
    '48d0956a-558b-479e-86a4-87c84b0fda19'
)
  AND behov_status IN ('BEHOV_CREATED', 'DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION');

-- Dialogene ryddes av eksisterende jobber: BEHOV_FULFILLED fullføres og BEHOV_EXPIRED utløpes i Dialogporten.
