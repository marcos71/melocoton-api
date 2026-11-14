-- V1: physical schema of the MVP (plan, iteration 0: "Esquema físico v1").
-- Source: base document section 9 (conceptual model), 02 sections 4.1 and 4.2
-- (dictionary, indexes and constraints) and the ER diagram (diagram 5).
-- Names follow docs/glosario_en.md (DI-20).
--
-- Physical conventions (implementation decisions, not business rules):
-- * ids: bigint identity.
-- * enumerations: text + CHECK with the English values of the glossary.
-- * instants: timestamptz (stored in UTC, DI-14); calendar dates: date; slot times: time
--   (wall-clock time of the workshop, America/Argentina/Buenos_Aires).
-- * "who did it" columns (_by) are foreign keys to app_user.
-- * Constraints are the last barrier for rules that fit in a single row or index;
--   everything else lives in the domain.
-- * ${appRole} is the role the API connects with (DB_APP_USER), filled in by Flyway.

-- ---------------------------------------------------------------------------
-- Users and students
-- ---------------------------------------------------------------------------

CREATE TABLE app_user (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          text    NOT NULL,
    email         text    NOT NULL,
    password_hash text,                                -- argon2id (RNF-03, DI-07); null until the invited
                                                       -- person defines it from the invitation link (iteration 1)
    role          text    NOT NULL CHECK (role IN ('STUDENT', 'LEAD_TEACHER', 'ASSISTANT_TEACHER')),
    phone         text    NOT NULL,                    -- also seen by assistant teachers (D-29)
    active        boolean NOT NULL DEFAULT true        -- only source of Active/Inactive
);

-- Email is unique regardless of case.
CREATE UNIQUE INDEX app_user_email_uq ON app_user (lower(email));

CREATE TABLE slot (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    day_of_week smallint NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),  -- ISO: 1 = Monday
    start_time  time     NOT NULL,
    end_time    time     NOT NULL,
    capacity    integer  NOT NULL CHECK (capacity > 0),
    active      boolean  NOT NULL DEFAULT true,
    CHECK (start_time < end_time)
);

CREATE TABLE student (
    user_id            bigint PRIMARY KEY REFERENCES app_user (id),
    enrollment_type    text   NOT NULL CHECK (enrollment_type IN ('MONTHLY', 'DROP_IN')),  -- RN-03
    fixed_slot_id      bigint REFERENCES slot (id),     -- null when drop-in or left without slot (D-26)
    monthly_start_date date,                            -- RN-12
    -- Monthly students have a start date; drop-in students do not.
    CHECK ((enrollment_type = 'MONTHLY') = (monthly_start_date IS NOT NULL)),
    -- Only monthly students have a fixed slot.
    CHECK (fixed_slot_id IS NULL OR enrollment_type = 'MONTHLY')
);

-- ---------------------------------------------------------------------------
-- Lessons and bookings
-- ---------------------------------------------------------------------------

-- A lesson closed by the workshop (RN-17). A lesson is a slot on a date.
CREATE TABLE lesson_closure (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slot_id     bigint      NOT NULL REFERENCES slot (id),
    lesson_date date        NOT NULL,
    reason      text        NOT NULL,                  -- free text shown to students (D-47)
    created_by  bigint      NOT NULL REFERENCES app_user (id),
    created_at  timestamptz NOT NULL,
    UNIQUE (slot_id, lesson_date)                      -- a lesson is closed once
);

CREATE TABLE booking (
    id                     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    student_id             bigint NOT NULL REFERENCES student (user_id),
    slot_id                bigint NOT NULL REFERENCES slot (id),
    lesson_date            date   NOT NULL,
    type                   text   NOT NULL CHECK (type IN ('FIXED', 'DROP_IN', 'MAKEUP')),
    status                 text   NOT NULL CHECK (status IN (
                               'SCHEDULED', 'PRESENT', 'ABSENT', 'ABSENT_RECOVERABLE',
                               'MADE_UP', 'CANCELLED', 'NOT_RECOVERABLE', 'UNRECORDED')),
    original_booking_id    bigint REFERENCES booking (id),  -- the absence a make-up recovers (RN-10)
    cancellation_reason    text   CHECK (cancellation_reason IN (
                               'STUDENT', 'TEACHER', 'WORKSHOP_CLOSURE', 'WITHDRAWAL',
                               'ENROLLMENT_CHANGE', 'SLOT_DEACTIVATED')),
    attendance_recorded_by bigint REFERENCES app_user (id),
    attendance_recorded_at timestamptz,
    -- Every make-up points to its original absence, and only make-ups do (02, 4.2).
    CHECK ((type = 'MAKEUP') = (original_booking_id IS NOT NULL)),
    -- A cancelled booking says why (02, 4.1).
    CHECK (status <> 'CANCELLED' OR cancellation_reason IS NOT NULL),
    -- Who recorded attendance and when go together.
    CHECK ((attendance_recorded_by IS NULL) = (attendance_recorded_at IS NULL))
);

-- Occupied capacity of a lesson (RN-02; 02, 4.2).
CREATE INDEX booking_slot_date_status_ix ON booking (slot_id, lesson_date, status);
-- A student's calendar.
CREATE INDEX booking_student_date_ix ON booking (student_id, lesson_date);
CREATE INDEX booking_original_ix ON booking (original_booking_id);

-- ---------------------------------------------------------------------------
-- Requests (two tables, DI-15)
-- ---------------------------------------------------------------------------

CREATE TABLE makeup_request (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    student_id          bigint      NOT NULL REFERENCES student (user_id),
    status              text        NOT NULL CHECK (status IN (
                            'PENDING', 'COUNTER_PROPOSED', 'APPROVED', 'REJECTED', 'CANCELLED')),  -- RN-07
    created_by          bigint      NOT NULL REFERENCES app_user (id),  -- student or teacher on their behalf
    created_at          timestamptz NOT NULL,
    reason              text,
    close_reason        text        CHECK (close_reason IN (
                            'EXPIRED', 'WITHDRAWAL', 'WORKSHOP_CLOSURE', 'ENROLLMENT_CHANGE',
                            'SLOT_DEACTIVATED', 'BOOKING_CANCELLED', 'REJECTION', 'CANCELLATION')),
    resolved_by         bigint      REFERENCES app_user (id),
    resolved_at         timestamptz,
    original_booking_id bigint      NOT NULL REFERENCES booking (id),  -- the absence to recover
    target_slot_id      bigint      NOT NULL REFERENCES slot (id),
    target_date         date        NOT NULL,
    proposed_slot_id    bigint      REFERENCES slot (id),
    proposed_date       date,
    expires_at          timestamptz,                    -- counter-proposal deadline (RN-19)
    -- A counter-proposal carries the proposed lesson and its deadline.
    CHECK (status <> 'COUNTER_PROPOSED'
           OR (proposed_slot_id IS NOT NULL AND proposed_date IS NOT NULL AND expires_at IS NOT NULL)),
    CHECK ((proposed_slot_id IS NULL) = (proposed_date IS NULL))
);

-- One open make-up request per student (RN-07; 02, 4.2).
CREATE UNIQUE INDEX makeup_request_one_open_per_student_uq
    ON makeup_request (student_id)
    WHERE status IN ('PENDING', 'COUNTER_PROPOSED');
CREATE INDEX makeup_request_original_ix ON makeup_request (original_booking_id);

CREATE TABLE withdrawal_request (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    student_id   bigint      NOT NULL REFERENCES student (user_id),
    status       text        NOT NULL CHECK (status IN ('PENDING', 'APPLIED', 'CANCELLED')),  -- RN-07
    created_by   bigint      NOT NULL REFERENCES app_user (id),
    created_at   timestamptz NOT NULL,
    reason       text,
    close_reason text        CHECK (close_reason IN (
                     'EXPIRED', 'WITHDRAWAL', 'WORKSHOP_CLOSURE', 'ENROLLMENT_CHANGE',
                     'SLOT_DEACTIVATED', 'BOOKING_CANCELLED', 'REJECTION', 'CANCELLATION')),
    resolved_by  bigint      REFERENCES app_user (id),
    resolved_at  timestamptz
);

CREATE INDEX withdrawal_request_student_ix ON withdrawal_request (student_id);

-- ---------------------------------------------------------------------------
-- Pieces
-- ---------------------------------------------------------------------------

CREATE TABLE piece (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    student_id  bigint      NOT NULL REFERENCES student (user_id),
    name        text        NOT NULL,
    notes       text,
    status      text        NOT NULL CHECK (status IN ('IN_PROGRESS', 'BISQUE_FIRED', 'GLAZED', 'FINISHED')),  -- RN-04
    created_at  timestamptz NOT NULL,
    voided_at   timestamptz,                            -- RN-20
    voided_by   bigint      REFERENCES app_user (id),
    void_reason text,
    -- A voided piece records when, who and why; a piece that is not voided records none of them.
    CHECK ((voided_at IS NULL) = (voided_by IS NULL) AND (voided_at IS NULL) = (void_reason IS NULL))
);

CREATE INDEX piece_student_ix ON piece (student_id);

-- Insert-only (RN-05).
CREATE TABLE piece_status_history (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    piece_id        bigint      NOT NULL REFERENCES piece (id),
    previous_status text        CHECK (previous_status IN ('IN_PROGRESS', 'BISQUE_FIRED', 'GLAZED', 'FINISHED')),
    new_status      text        CHECK (new_status IN ('IN_PROGRESS', 'BISQUE_FIRED', 'GLAZED', 'FINISHED')),
    event_type      text        NOT NULL CHECK (event_type IN ('CREATED', 'ADVANCED', 'REVERTED', 'VOIDED')),
    occurred_at     timestamptz NOT NULL,
    user_id         bigint      NOT NULL REFERENCES app_user (id),
    operation_id    uuid        NOT NULL,              -- groups the pieces of one bulk change
    -- Creation has no previous status; advances and reversals have both.
    CHECK (event_type <> 'CREATED' OR (previous_status IS NULL AND new_status IS NOT NULL)),
    CHECK (event_type NOT IN ('ADVANCED', 'REVERTED') OR (previous_status IS NOT NULL AND new_status IS NOT NULL))
);

CREATE INDEX piece_status_history_piece_ix ON piece_status_history (piece_id);

-- ---------------------------------------------------------------------------
-- Payments and student audit
-- ---------------------------------------------------------------------------

-- Insert-only: a payment is final (RN-06).
CREATE TABLE payment (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    student_id  bigint        NOT NULL REFERENCES student (user_id),
    concept     text          NOT NULL CHECK (concept IN ('MONTHLY_FEE', 'DROP_IN_CLASS')),
    period      date          CHECK (extract(day FROM period) = 1),  -- a month, stored as its first day
    booking_id  bigint        REFERENCES booking (id),  -- drop-in class paid
    amount      integer       NOT NULL CHECK (amount > 0),  -- whole Argentine pesos, informative (D-50)
    paid_on     date          NOT NULL,
    method      text          NOT NULL CHECK (method IN ('CASH', 'TRANSFER')),
    recorded_by bigint        NOT NULL REFERENCES app_user (id),
    recorded_at timestamptz   NOT NULL,
    -- A monthly fee is for a month and not for a booking; a drop-in class is not for a month.
    CHECK ((concept = 'MONTHLY_FEE' AND period IS NOT NULL AND booking_id IS NULL)
        OR (concept = 'DROP_IN_CLASS' AND period IS NULL))
);

CREATE INDEX payment_student_ix ON payment (student_id);

-- Insert-only.
CREATE TABLE student_audit (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    student_id            bigint      NOT NULL REFERENCES student (user_id),
    event                 text        NOT NULL CHECK (event IN ('WITHDRAWAL', 'REACTIVATION', 'ENROLLMENT_CHANGE')),
    occurred_at           timestamptz NOT NULL,
    user_id               bigint      NOT NULL REFERENCES app_user (id),
    withdrawal_request_id bigint      REFERENCES withdrawal_request (id),
    reason                text
);

CREATE INDEX student_audit_student_ix ON student_audit (student_id);

-- ---------------------------------------------------------------------------
-- Settings
-- ---------------------------------------------------------------------------

-- A single row with the editable parameters (CU-20). The time zone is fixed and not stored.
CREATE TABLE settings (
    id                         smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    payment_due_day            smallint NOT NULL CHECK (payment_due_day BETWEEN 1 AND 31),
    absence_max_age_months     smallint NOT NULL CHECK (absence_max_age_months > 0),
    counter_proposal_ttl_hours smallint NOT NULL CHECK (counter_proposal_ttl_hours > 0),
    makeup_horizon_days        smallint NOT NULL CHECK (makeup_horizon_days > 0)
);

-- Initial values (base document, section 9).
INSERT INTO settings (payment_due_day, absence_max_age_months, counter_proposal_ttl_hours, makeup_horizon_days)
VALUES (10, 3, 24, 60);

-- Insert-only: the history of parameter changes cannot be rewritten.
CREATE TABLE setting_change (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    setting        text        NOT NULL CHECK (setting IN (
                       'payment_due_day', 'absence_max_age_months',
                       'counter_proposal_ttl_hours', 'makeup_horizon_days')),
    previous_value text        NOT NULL,
    new_value      text        NOT NULL,
    changed_at     timestamptz NOT NULL,
    user_id        bigint      NOT NULL REFERENCES app_user (id)
);

-- ---------------------------------------------------------------------------
-- AI assistant
-- ---------------------------------------------------------------------------

-- One row per assistant query; never stores the query or the answer (RN-22; 02, 6.3).
CREATE TABLE ai_call (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    student_id    bigint        NOT NULL REFERENCES student (user_id),
    created_at    timestamptz   NOT NULL,
    model         text,                                 -- null when the provider was not called
    input_tokens  integer       NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens integer       NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    cost_usd      numeric(10,6) NOT NULL DEFAULT 0 CHECK (cost_usd >= 0),
    latency_ms    integer       CHECK (latency_ms >= 0),
    result        text          NOT NULL CHECK (result IN (
                      'IN_PROGRESS', 'OK', 'DEGRADED_PROVIDER', 'DEGRADED_BUDGET', 'REJECTED_QUOTA')),
    retries       smallint      NOT NULL DEFAULT 0 CHECK (retries >= 0)
);

-- Monthly quotas per student (02, 4.2).
CREATE INDEX ai_call_student_created_ix ON ai_call (student_id, created_at);

-- ---------------------------------------------------------------------------
-- Privileges of the API role (DI-05; 02, 4.2 and 5)
-- ---------------------------------------------------------------------------
-- Nothing is ever deleted (02, section 5: logical deactivation, logical voiding,
-- final payments), so the API role gets no DELETE at all.
-- The four insert-only tables also get no UPDATE: payment, piece_status_history and
-- student_audit (02, 4.2; DI-05) and setting_change (02, section 5: never modified).
-- Every new table in a later migration must be granted explicitly as well.

GRANT SELECT, INSERT, UPDATE ON
    app_user, student, slot, lesson_closure, booking, makeup_request, withdrawal_request,
    piece, settings, ai_call
TO ${appRole};

GRANT SELECT, INSERT ON
    payment, piece_status_history, student_audit, setting_change
TO ${appRole};
