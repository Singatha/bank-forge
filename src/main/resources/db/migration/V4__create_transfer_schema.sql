create table transfers (
    id uuid primary key,
    reference varchar(24) not null unique,
    source_account_id uuid not null references accounts (id),
    destination_account_id uuid not null references accounts (id),
    initiated_by_user_id uuid not null references users (id),
    amount numeric(19, 2) not null,
    currency varchar(3) not null,
    status varchar(20) not null,
    description varchar(140),
    created_at timestamptz not null,
    completed_at timestamptz,
    failure_reason varchar(100),
    constraint ck_transfers_different_accounts check (source_account_id <> destination_account_id),
    constraint ck_transfers_positive_amount check (amount > 0),
    constraint ck_transfers_currency check (currency in ('ZAR')),
    constraint ck_transfers_status check (status in ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'REVERSED')),
    constraint ck_transfers_completion check (
        (status in ('COMPLETED', 'REVERSED') and completed_at is not null)
        or (status not in ('COMPLETED', 'REVERSED') and completed_at is null)
    ),
    constraint ck_transfers_failure check (
        (status = 'FAILED' and failure_reason is not null)
        or (status <> 'FAILED' and failure_reason is null)
    )
);

create index ix_transfers_initiator_created on transfers (initiated_by_user_id, created_at desc);
create index ix_transfers_source_created on transfers (source_account_id, created_at desc);
create index ix_transfers_destination_created on transfers (destination_account_id, created_at desc);
create index ix_transfers_status on transfers (status);
