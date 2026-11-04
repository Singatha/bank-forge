create table customers (
    id uuid primary key,
    user_id uuid not null unique references users (id) on delete cascade,
    first_name varchar(100) not null,
    last_name varchar(100) not null,
    email varchar(254) not null unique,
    phone_number varchar(20) not null,
    status varchar(20) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint ck_customers_email_normalized check (email = lower(email)),
    constraint ck_customers_status check (status in ('PENDING', 'ACTIVE', 'SUSPENDED', 'CLOSED'))
);

create index ix_customers_status on customers (status);

create table accounts (
    id uuid primary key,
    account_number varchar(20) not null unique,
    customer_id uuid not null references customers (id),
    account_type varchar(20) not null,
    currency varchar(3) not null,
    available_balance numeric(19, 2) not null default 0.00,
    status varchar(20) not null,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint ck_accounts_simulated_number check (account_number like 'BC%'),
    constraint ck_accounts_type check (account_type in ('CHECKING', 'SAVINGS')),
    constraint ck_accounts_currency check (currency in ('ZAR')),
    constraint ck_accounts_balance_non_negative check (available_balance >= 0),
    constraint ck_accounts_status check (status in ('ACTIVE', 'FROZEN', 'CLOSED')),
    constraint ck_accounts_version check (version >= 0)
);

create index ix_accounts_customer_created on accounts (customer_id, created_at);
create index ix_accounts_status on accounts (status);
