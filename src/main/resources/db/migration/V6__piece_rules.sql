-- V6: last-barrier checks for pieces (RN-05, RN-20, RF-56; DI-72, DI-74). No new table, so no new GRANT:
-- piece (SELECT, INSERT, UPDATE) and piece_status_history (SELECT, INSERT only) were granted in V1.

-- A voidance records the status the piece had and no new status (DI-74).
ALTER TABLE piece_status_history ADD CONSTRAINT piece_status_history_voided_shape_ck
    CHECK (event_type <> 'VOIDED' OR (previous_status IS NOT NULL AND new_status IS NULL));

-- A piece always has a name, and a voided piece a reason (RF-24, RF-56).
ALTER TABLE piece ADD CONSTRAINT piece_name_not_blank_ck CHECK (btrim(name) <> '');
ALTER TABLE piece ADD CONSTRAINT piece_void_reason_not_blank_ck CHECK (void_reason IS NULL OR btrim(void_reason) <> '');
