-- V5: every slot holds the same 12 students (DI-64), so the capacity is a constant of the domain
-- (Slot.CAPACITY) and no longer a column. Dropping it also drops its CHECK constraints (V1's capacity > 0
-- and V4's slot_capacity_range). No view or function reads it.
-- No new table, so no GRANT is needed.
ALTER TABLE slot DROP COLUMN capacity;
