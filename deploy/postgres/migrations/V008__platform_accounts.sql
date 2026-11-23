-- Platform accounts, personal/enterprise spaces, sessions, verification codes,
-- credits and managed-request bookkeeping. Additive only: existing enterprise
-- tables and rows are untouched, and backfill is done by the application
-- AccountMigration runner after this migration is applied.
create table if not exists accounts (
 id varchar(36) primary key,
 email varchar(254) not null unique,
 display_name varchar(120) not null,
 password_hash varchar(100) not null,
 platform_role varchar(16) not null default 'NONE',
 email_verified_at timestamp,
 active boolean not null default true,
 revision integer not null default 1,
 created_at timestamp not null,
 updated_at timestamp not null
);
create table if not exists account_spaces (
 id varchar(36) primary key,
 account_id varchar(36) not null,
 kind varchar(16) not null,
 name varchar(160) not null,
 organization_id varchar(36),
 member_id varchar(36),
 role varchar(16) not null,
 active boolean not null default true,
 created_at timestamp not null,
 unique (account_id,kind),
 foreign key (account_id) references accounts(id)
);
create table if not exists account_sessions (
 token_hash varchar(64) primary key,
 account_id varchar(36) not null,
 space_id varchar(36) not null,
 created_at timestamp not null,
 expires_at timestamp not null,
 foreign key (account_id) references accounts(id),
 foreign key (space_id) references account_spaces(id)
);
create index if not exists account_sessions_account on account_sessions(account_id);
create table if not exists verification_codes (
 id varchar(36) primary key,
 email varchar(254) not null,
 purpose varchar(16) not null,
 code_hash varchar(64) not null,
 attempts integer not null default 0,
 max_attempts integer not null,
 created_at timestamp not null,
 expires_at timestamp not null,
 consumed_at timestamp
);
create index if not exists verification_codes_email on verification_codes(email,purpose,created_at);
create table if not exists credit_wallets (
 space_id varchar(36) primary key,
 account_id varchar(36) not null,
 balance_units bigint not null default 0,
 reserved_units bigint not null default 0,
 unit varchar(16) not null default 'credit',
 scale integer not null default 1000,
 revision bigint not null default 0,
 updated_at timestamp not null,
 foreign key (space_id) references account_spaces(id),
 foreign key (account_id) references accounts(id)
);
create table if not exists credit_ledger (
 id varchar(36) primary key,
 account_id varchar(36) not null,
 space_id varchar(36) not null,
 delta_units bigint not null,
 balance_after_units bigint not null,
 reason varchar(32) not null,
 reference varchar(128) not null unique,
 actor_account_id varchar(36),
 created_at timestamp not null,
 foreign key (account_id) references accounts(id),
 foreign key (space_id) references account_spaces(id)
);
create index if not exists credit_ledger_space on credit_ledger(space_id,created_at);
create table if not exists platform_models (
 id varchar(128) primary key,
 display_name varchar(160) not null,
 protocol varchar(32) not null,
 base_url varchar(500) not null,
 vendor_model varchar(160) not null,
 encrypted_vendor_key text not null,
 multiplier_basis_points integer not null,
 capabilities_json text not null,
 context_tokens integer not null,
 max_output_tokens integer not null,
 enabled boolean not null default false,
 price_version integer not null default 1,
 revision bigint not null default 0,
 created_at timestamp not null,
 updated_at timestamp not null
);
create table if not exists managed_requests (
 id varchar(36) primary key,
 request_id varchar(128) not null unique,
 account_id varchar(36) not null,
 space_id varchar(36) not null,
 model_id varchar(128) not null,
 price_version integer not null,
 multiplier_basis_points integer not null,
 status varchar(24) not null,
 reserved_units bigint not null,
 charged_units bigint not null default 0,
 input_tokens bigint,
 output_tokens bigint,
 upstream_request_id varchar(128),
 created_at timestamp not null,
 updated_at timestamp not null,
 settled_at timestamp,
 foreign key (account_id) references accounts(id),
 foreign key (space_id) references account_spaces(id)
);
create index if not exists managed_requests_space on managed_requests(space_id,created_at);
create table if not exists platform_audit_events (
 id varchar(36) primary key,
 actor_account_id varchar(36),
 action varchar(80) not null,
 target_type varchar(32),
 target_id varchar(128),
 detail varchar(1000),
 occurred_at timestamp not null
);
create index if not exists platform_audit_occurred on platform_audit_events(occurred_at);
create table if not exists platform_migrations (
 id varchar(64) primary key,
 applied_at timestamp not null
);
