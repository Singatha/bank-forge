create table ledger_entries (
    id uuid primary key,
    transfer_id uuid not null references transfers (id),
    account_id uuid not null references accounts (id),
    entry_type varchar(10) not null,
    amount numeric(19, 2) not null,
    currency varchar(3) not null,
    created_at timestamptz not null,
    constraint uq_ledger_transfer_entry_type unique (transfer_id, entry_type),
    constraint uq_ledger_transfer_account unique (transfer_id, account_id),
    constraint ck_ledger_entry_type check (entry_type in ('DEBIT', 'CREDIT')),
    constraint ck_ledger_positive_amount check (amount > 0),
    constraint ck_ledger_currency check (currency in ('ZAR'))
);

create index ix_ledger_account_created on ledger_entries (account_id, created_at desc);

create function prevent_ledger_entry_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'ledger entries are append-only'
        using errcode = 'integrity_constraint_violation';
end;
$$;

create trigger trg_ledger_entries_append_only
before update or delete on ledger_entries
for each row
execute function prevent_ledger_entry_mutation();

create function reconcile_transfer_ledger()
returns trigger
language plpgsql
as $$
declare
    affected_transfer_id uuid;
    transfer_status varchar(20);
    source_id uuid;
    destination_id uuid;
    transfer_amount numeric(19, 2);
    transfer_currency varchar(3);
    entry_count integer;
    debit_count integer;
    credit_count integer;
    debit_total numeric(19, 2);
    credit_total numeric(19, 2);
    debit_account uuid;
    credit_account uuid;
    mismatched_entry_count integer;
begin
    if tg_table_name = 'transfers' then
        affected_transfer_id := new.id;
    else
        affected_transfer_id := new.transfer_id;
    end if;

    select status, source_account_id, destination_account_id, amount, currency
      into transfer_status, source_id, destination_id, transfer_amount, transfer_currency
      from transfers
     where id = affected_transfer_id;

    select count(*),
           count(*) filter (where entry_type = 'DEBIT'),
           count(*) filter (where entry_type = 'CREDIT'),
           coalesce(sum(amount) filter (where entry_type = 'DEBIT'), 0),
           coalesce(sum(amount) filter (where entry_type = 'CREDIT'), 0),
           (max(account_id::text) filter (where entry_type = 'DEBIT'))::uuid,
           (max(account_id::text) filter (where entry_type = 'CREDIT'))::uuid,
           count(*) filter (where amount <> transfer_amount or currency <> transfer_currency)
      into entry_count, debit_count, credit_count, debit_total, credit_total,
           debit_account, credit_account, mismatched_entry_count
      from ledger_entries
     where transfer_id = affected_transfer_id;

    if transfer_status in ('COMPLETED', 'REVERSED') then
        if entry_count <> 2
           or debit_count <> 1
           or credit_count <> 1
           or debit_total <> credit_total
           or debit_total <> transfer_amount
           or debit_account <> source_id
           or credit_account <> destination_id
           or mismatched_entry_count <> 0 then
            raise exception 'ledger is not balanced for transfer %', affected_transfer_id
                using errcode = 'integrity_constraint_violation';
        end if;
    elsif entry_count <> 0 then
        raise exception 'non-completed transfer % cannot have ledger entries', affected_transfer_id
            using errcode = 'integrity_constraint_violation';
    end if;

    return null;
end;
$$;

create constraint trigger trg_reconcile_ledger_entry
after insert on ledger_entries
deferrable initially deferred
for each row
execute function reconcile_transfer_ledger();

create constraint trigger trg_reconcile_completed_transfer
after insert or update on transfers
deferrable initially deferred
for each row
execute function reconcile_transfer_ledger();
