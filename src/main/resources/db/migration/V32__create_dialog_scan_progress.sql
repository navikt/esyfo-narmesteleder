-- Progress of the bounded Dialogporten send and expiry scans. A row exists only while a flow's
-- cycle is unfinished: it is written after each full batch and deleted when the cycle ends.
-- ADR-0003: the owning module keeps its background task state, so this lives with nl_behov.
create table nl_dialog_scan_progress
(
    flow_key        text        not null,
    last_created    timestamptz not null,
    last_id         uuid        not null,
    high_water_mark timestamptz not null,
    updated         timestamptz not null default now(),
    constraint nl_dialog_scan_progress_pkey primary key (flow_key),
    constraint nl_dialog_scan_progress_flow_key_check
        check (flow_key in ('SEND', 'EXPIRE')),
    -- The position only ever points at rows the cycle's high-water mark includes.
    constraint nl_dialog_scan_progress_position_before_mark_check
        check (last_created < high_water_mark)
);
