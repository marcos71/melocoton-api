-- V4: a slot holds 1 to 12 students (DI-64). The domain validates first; this is the last barrier.
-- The unnamed capacity > 0 check from V1 stays; this one adds the upper bound.
-- No new table, so no GRANT is needed.
ALTER TABLE slot
    ADD CONSTRAINT slot_capacity_range CHECK (capacity BETWEEN 1 AND 12);
