create table idempotency_records (
    id uuid primary key,
    user_id uuid not null references users (id),
    idempotency_key varchar(128) not null,
    request_hash varchar(64) not null,
    transfer_id uuid references transfers (id),
    outcome varchar(20) not null,
    response_status integer,
    error_code varchar(100),
    error_message varchar(255),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uq_idempotency_user_key unique (user_id, idempotency_key),
    constraint ck_idempotency_key_not_blank check (length(trim(idempotency_key)) > 0),
    constraint ck_idempotency_hash check (request_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_idempotency_outcome check (outcome in ('PROCESSING', 'COMPLETED', 'FAILED')),
    constraint ck_idempotency_result check (
        (outcome = 'PROCESSING' and transfer_id is null and response_status is null
            and error_code is null and error_message is null)
        or (outcome = 'COMPLETED' and transfer_id is not null and response_status = 201
            and error_code is null and error_message is null)
        or (outcome = 'FAILED' and transfer_id is not null and response_status between 400 and 599
            and error_code is not null and error_message is not null)
    )
);

create index ix_idempotency_transfer on idempotency_records (transfer_id)
    where transfer_id is not null;
create index ix_idempotency_created on idempotency_records (created_at);

create table audit_logs (
    id uuid primary key,
    actor_user_id uuid references users (id),
    action varchar(50) not null,
    resource_type varchar(50) not null,
    resource_id uuid,
    request_id uuid not null,
    correlation_id uuid not null,
    occurred_at timestamptz not null,
    metadata jsonb not null default '{}'::jsonb,
    constraint ck_audit_action check (action in (
        'LOGIN_SUCCESS', 'LOGIN_FAILED', 'ACCOUNT_CREATED',
        'TRANSFER_COMPLETED', 'TRANSFER_FAILED'
    )),
    constraint ck_audit_resource_type check (resource_type in ('AUTH_SESSION', 'ACCOUNT', 'TRANSFER')),
    constraint ck_audit_metadata_object check (jsonb_typeof(metadata) = 'object')
);

create index ix_audit_actor_occurred on audit_logs (actor_user_id, occurred_at desc)
    where actor_user_id is not null;
create index ix_audit_action_occurred on audit_logs (action, occurred_at desc);
create index ix_audit_correlation on audit_logs (correlation_id);

create function prevent_audit_log_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'audit logs are append-only'
        using errcode = 'integrity_constraint_violation';
end;
$$;

create trigger trg_audit_logs_append_only
before update or delete on audit_logs
for each row
execute function prevent_audit_log_mutation();
