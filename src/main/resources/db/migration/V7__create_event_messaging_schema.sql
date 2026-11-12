create table outbox_events (
    id uuid primary key,
    event_type varchar(50) not null,
    aggregate_type varchar(50) not null,
    aggregate_id uuid not null,
    occurred_at timestamptz not null,
    correlation_id uuid not null,
    payload jsonb not null,
    status varchar(20) not null,
    attempts integer not null default 0,
    next_attempt_at timestamptz,
    locked_at timestamptz,
    published_at timestamptz,
    last_error varchar(500),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint ck_outbox_event_type check (event_type in ('TRANSFER_COMPLETED', 'TRANSFER_FAILED')),
    constraint ck_outbox_aggregate_type check (aggregate_type = 'TRANSFER'),
    constraint ck_outbox_payload_object check (jsonb_typeof(payload) = 'object'),
    constraint ck_outbox_status check (status in ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')),
    constraint ck_outbox_attempts check (attempts >= 0),
    constraint ck_outbox_state check (
        (status = 'PENDING' and next_attempt_at is not null and locked_at is null and published_at is null)
        or (status = 'PROCESSING' and locked_at is not null and published_at is null)
        or (status = 'PUBLISHED' and locked_at is null and published_at is not null)
        or (status = 'FAILED' and locked_at is null and published_at is null and last_error is not null)
    )
);

create index ix_outbox_publishable on outbox_events (status, next_attempt_at, created_at)
    where status = 'PENDING';
create index ix_outbox_stale_processing on outbox_events (locked_at)
    where status = 'PROCESSING';
create index ix_outbox_aggregate on outbox_events (aggregate_type, aggregate_id, occurred_at);

create table processed_events (
    id uuid primary key,
    consumer_name varchar(100) not null,
    event_id uuid not null,
    processed_at timestamptz not null,
    constraint uq_processed_consumer_event unique (consumer_name, event_id)
);

create index ix_processed_events_processed on processed_events (processed_at);

create table notifications (
    id uuid primary key,
    event_id uuid not null,
    transfer_id uuid not null references transfers (id),
    customer_id uuid not null references customers (id),
    type varchar(30) not null,
    title varchar(120) not null,
    message varchar(500) not null,
    is_read boolean not null default false,
    created_at timestamptz not null,
    read_at timestamptz,
    constraint uq_notifications_event_customer unique (event_id, customer_id),
    constraint ck_notifications_type check (type in ('TRANSFER_SENT', 'TRANSFER_RECEIVED', 'TRANSFER_FAILED')),
    constraint ck_notifications_read_state check (
        (is_read and read_at is not null) or (not is_read and read_at is null)
    )
);

create index ix_notifications_customer_created on notifications (customer_id, created_at desc);
create index ix_notifications_customer_unread on notifications (customer_id, created_at desc)
    where not is_read;
