create table mes_legacy_user(source_id uuid primary key,user_id bigint not null unique references sys_user(user_id),source_account varchar(128) not null,source_role varchar(128) not null);
create table mes_legacy_record(source varchar(64) not null,id uuid not null,payload jsonb not null,imported_at timestamptz not null default now(),primary key(source,id));
create table mes_history_import(id uuid primary key,source varchar(128) not null,sha256 varchar(64) not null unique,counts jsonb not null,at timestamptz not null default now());
