-- V3: a student has at most one active booking per lesson (DI-41, iteration 2).
-- A lesson is a slot on a date. Cancelled bookings are kept as history, so they do not count: the
-- student can book the same lesson again after cancelling. The code checks first; this index is the
-- last barrier against two requests racing, and lets the fixed-booking generation use ON CONFLICT DO NOTHING.
-- No new table, so no GRANT is needed.
CREATE UNIQUE INDEX booking_student_slot_date_active_ux
    ON booking (student_id, slot_id, lesson_date)
    WHERE status <> 'CANCELLED';
