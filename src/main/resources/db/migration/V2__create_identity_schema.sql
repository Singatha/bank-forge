create table roles (
    id smallint primary key,
    name varchar(30) not null unique,
    constraint ck_roles_name check (name in ('CUSTOMER', 'SUPPORT', 'ADMIN'))
);

insert into roles (id, name) values
    (1, 'CUSTOMER'),
    (2, 'SUPPORT'),
    (3, 'ADMIN');

create table users (
    id uuid primary key,
    email varchar(254) not null unique,
    password_hash varchar(100) not null,
    status varchar(20) not null,
    token_version integer not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint ck_users_email_normalized check (email = lower(email)),
    constraint ck_users_status check (status in ('ACTIVE', 'DISABLED')),
    constraint ck_users_token_version check (token_version >= 0)
);

create table user_roles (
    user_id uuid not null references users (id) on delete cascade,
    role_id smallint not null references roles (id),
    primary key (user_id, role_id)
);

create index ix_user_roles_role_id on user_roles (role_id);

create table refresh_tokens (
    id uuid primary key,
    user_id uuid not null references users (id) on delete cascade,
    family_id uuid not null,
    token_hash varchar(64) not null unique,
    expires_at timestamptz not null,
    created_at timestamptz not null,
    revoked_at timestamptz,
    replaced_by_token_id uuid,
    constraint ck_refresh_tokens_expiry check (expires_at > created_at),
    constraint fk_refresh_tokens_replacement
        foreign key (replaced_by_token_id) references refresh_tokens (id)
);

create index ix_refresh_tokens_user_id on refresh_tokens (user_id);
create index ix_refresh_tokens_family_id on refresh_tokens (family_id);
create index ix_refresh_tokens_expires_at on refresh_tokens (expires_at);
create index ix_refresh_tokens_active_user
    on refresh_tokens (user_id, expires_at)
    where revoked_at is null;

