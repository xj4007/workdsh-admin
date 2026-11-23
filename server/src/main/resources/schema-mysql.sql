create table if not exists organizations (
  id varchar(36) primary key,
  name varchar(160) not null
);
create table if not exists members (
  id varchar(36) primary key,
  organization_id varchar(36) not null,
  email varchar(254) not null unique,
  display_name varchar(120) not null,
  role varchar(16) not null,
  password_hash varchar(100) not null,
  must_change_password boolean not null,
  active boolean not null,
  revision integer not null,
 foreign key (organization_id) references organizations(id)
);
create table if not exists auth_sessions (
  token_hash varchar(64) primary key,
  member_id varchar(36) not null,
  expires_at timestamp not null,
 foreign key (member_id) references members(id)
);
create table if not exists audit_events (
  id varchar(36) primary key,
  organization_id varchar(36) not null,
  actor_id varchar(36) not null,
  action varchar(80) not null,
  target_id varchar(36),
  occurred_at timestamp not null
);
create table if not exists collaboration_handoffs (
  id varchar(36) primary key,
  organization_id varchar(36) not null,
  sender_id varchar(36) not null,
  recipient_id varchar(36) not null,
  request_key varchar(128) not null,
  summary varchar(2000) not null,
  status varchar(16) not null,
  resolution varchar(2000),
  created_at timestamp not null,
  completed_at timestamp,
  unique(sender_id, request_key),
 foreign key (organization_id) references organizations(id),
 foreign key (sender_id) references members(id),
 foreign key (recipient_id) references members(id)
);
create index collaboration_handoff_inbox on collaboration_handoffs(organization_id,recipient_id,status,created_at);
create table if not exists collaboration_messages (
  id varchar(36) primary key,
  handoff_id varchar(36) not null,
  organization_id varchar(36) not null,
  author_id varchar(36) not null,
  request_key varchar(128) not null,
  content varchar(2000) not null,
  created_at timestamp not null,
  unique(handoff_id,author_id,request_key),
 foreign key (handoff_id) references collaboration_handoffs(id),
 foreign key (organization_id) references organizations(id),
 foreign key (author_id) references members(id)
);
create index collaboration_messages_thread on collaboration_messages(handoff_id,created_at);

create table if not exists collaboration_notifications (
  id varchar(36) primary key,
  organization_id varchar(36) not null,
  recipient_id varchar(36) not null,
  author_id varchar(36) not null,
  handoff_id varchar(36) not null,
  kind varchar(24) not null,
  preview varchar(240) not null,
  created_at timestamp not null,
  read_at timestamp,
 foreign key (organization_id) references organizations(id),
 foreign key (recipient_id) references members(id),
 foreign key (author_id) references members(id),
 foreign key (handoff_id) references collaboration_handoffs(id)
);
create index collaboration_notifications_member on collaboration_notifications(organization_id,recipient_id,read_at,created_at);

create table if not exists collaboration_materials (
  handoff_id varchar(36) primary key,
  payload longtext not null,
 foreign key (handoff_id) references collaboration_handoffs(id)
);

create table if not exists departments (
 id varchar(36) primary key,
 organization_id varchar(36) not null,
 parent_id varchar(36),
 name varchar(64) not null,
 revision integer not null default 1,
 foreign key (organization_id) references organizations(id)
);
create table if not exists member_profiles (
 member_id varchar(36) primary key,
 organization_id varchar(36) not null,
 department_id varchar(36),
 username varchar(64) unique,
 employee_number varchar(64),
 job_title varchar(64),
 foreign key (member_id) references members(id),
 foreign key (organization_id) references organizations(id)
);
create table if not exists organization_policies (
 organization_id varchar(36) primary key,
 directory_scope varchar(24) not null default 'ORGANIZATION',
 sharing_enabled boolean not null default true,
 revision integer not null default 1,
 foreign key (organization_id) references organizations(id)
);

create table if not exists member_secrets (
 organization_id varchar(36) not null,
 member_id varchar(36) not null,
 credential_ref varchar(128) character set ascii collate ascii_bin not null,
 encrypted_value text not null,
 primary key (organization_id,member_id,credential_ref),
 foreign key (organization_id) references organizations(id),
 foreign key (member_id) references members(id)
);

create table if not exists member_secret_records (
 organization_id varchar(36) not null,
 member_id varchar(36) not null,
 record_key varchar(255) character set ascii collate ascii_bin not null,
 revision bigint not null,
 kind varchar(16),
 encrypted_value longtext,
 primary key (organization_id,member_id,record_key),
 foreign key (organization_id) references organizations(id),
 foreign key (member_id) references members(id)
);

create table if not exists server_transport_records (
 record_key varchar(128) primary key,
 revision bigint not null,
 encrypted_value longtext not null
);


create table if not exists organization_model_gateway (organization_id varchar(128) primary key, access_hash varchar(64) not null unique);

create table if not exists organization_model_providers (organization_id varchar(128) primary key, revision bigint not null, providers_json text not null);

create table if not exists organization_model_gateway_keys (organization_id varchar(128) primary key, encrypted_key text not null);

-- Client-submitted Desktop visible text has its own immutable namespace.
create table if not exists member_desktop_body_sessions (
 organization_id varchar(36) not null,
 member_id varchar(36) not null,
 client_session_id varchar(128) character set ascii collate ascii_bin not null,
 device_id varchar(128) character set ascii collate ascii_bin not null,
 server_session_id varchar(128) character set ascii collate ascii_bin not null,
 created_at bigint not null,
 revision bigint not null default 0,
 record_count bigint not null default 0,
 deleted_at bigint not null default 0,
 primary key (organization_id,member_id,client_session_id),
 unique (organization_id,member_id,server_session_id),
 foreign key (organization_id) references organizations(id),
 foreign key (member_id) references members(id)
);
create table if not exists member_desktop_body_records (
 organization_id varchar(36) not null,
 member_id varchar(36) not null,
 client_session_id varchar(128) character set ascii collate ascii_bin not null,
 record_seq bigint not null,
 record_id varchar(128) character set ascii collate ascii_bin not null,
 role varchar(16) not null,
 body_text longtext not null,
 received_at bigint not null,
 primary key (organization_id,member_id,client_session_id,record_seq),
 unique (organization_id,member_id,client_session_id,record_id),
 foreign key (organization_id,member_id,client_session_id) references member_desktop_body_sessions(organization_id,member_id,client_session_id)
);
create table if not exists member_desktop_body_receipts (
 organization_id varchar(36) not null,
 member_id varchar(36) not null,
 client_session_id varchar(128) character set ascii collate ascii_bin not null,
 request_id varchar(128) character set ascii collate ascii_bin not null,
 revision bigint not null,
 payload_hash varchar(64) not null,
 record_count bigint not null,
 primary key (organization_id,member_id,client_session_id,request_id),
 unique (organization_id,member_id,client_session_id,revision),
 foreign key (organization_id,member_id,client_session_id) references member_desktop_body_sessions(organization_id,member_id,client_session_id)
);

-- Platform accounts, spaces, sessions, verification, credits and managed usage.
-- MySQL has no CREATE INDEX IF NOT EXISTS, so the indexes of these tables are declared inline to keep re-runs safe.
create table if not exists accounts (
  id varchar(36) primary key,
  email varchar(254) not null unique,
  display_name varchar(120) not null,
  password_hash varchar(100) not null,
  platform_role varchar(16) not null default 'NONE',
  email_verified_at timestamp null,
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
  organization_id varchar(36) null,
  member_id varchar(36) null,
  role varchar(16) not null,
  active boolean not null default true,
  created_at timestamp not null,
  unique (account_id,kind),
  foreign key (account_id) references accounts(id)
);
create table if not exists account_sessions (
  token_hash varchar(64) character set ascii collate ascii_bin primary key,
  account_id varchar(36) not null,
  space_id varchar(36) not null,
  created_at timestamp not null,
  expires_at timestamp not null,
  foreign key (account_id) references accounts(id),
  foreign key (space_id) references account_spaces(id),
  index account_sessions_account (account_id)
);
create table if not exists verification_codes (
  id varchar(36) primary key,
  email varchar(254) not null,
  purpose varchar(16) not null,
  code_hash varchar(64) character set ascii collate ascii_bin not null,
  attempts integer not null default 0,
  max_attempts integer not null,
  created_at timestamp not null,
  expires_at timestamp not null,
  consumed_at timestamp null,
  index verification_codes_email (email,purpose,created_at)
);
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
  reference varchar(128) character set ascii collate ascii_bin not null unique,
  actor_account_id varchar(36) null,
  created_at timestamp not null,
  foreign key (account_id) references accounts(id),
  foreign key (space_id) references account_spaces(id),
  index credit_ledger_space (space_id,created_at)
);
create table if not exists platform_models (
  id varchar(128) primary key,
  display_name varchar(160) not null,
  protocol varchar(32) not null,
  base_url varchar(500) not null,
  vendor_model varchar(160) not null,
  encrypted_vendor_key longtext not null,
  multiplier_basis_points integer not null,
  capabilities_json longtext not null,
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
  input_tokens bigint null,
  output_tokens bigint null,
  upstream_request_id varchar(128) null,
  created_at timestamp not null,
  updated_at timestamp not null,
  settled_at timestamp null,
  foreign key (account_id) references accounts(id),
  foreign key (space_id) references account_spaces(id),
  index managed_requests_space (space_id,created_at)
);
create table if not exists platform_audit_events (
  id varchar(36) primary key,
  actor_account_id varchar(36) null,
  action varchar(80) not null,
  target_type varchar(32) null,
  target_id varchar(128) null,
  detail varchar(1000) null,
  occurred_at timestamp not null,
  index platform_audit_occurred (occurred_at)
);
create table if not exists platform_migrations (
  id varchar(64) primary key,
  applied_at timestamp not null
);
