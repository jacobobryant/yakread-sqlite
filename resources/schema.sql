CREATE TABLE user (
  id BLOB PRIMARY KEY,
  email TEXT,
  roles BLOB,
  joined_at INT,
  digest_days BLOB,
  send_digest_at TEXT,
  timezone TEXT,
  digest_last_sent INT,
  from_the_sample INT,
  use_original_links INT,
  suppressed_at INT,
  email_username TEXT,
  customer_id TEXT,
  plan INT CHECK (plan IN (0, 1)), -- :quarter (0), :annual (1)
  cancel_at INT
) STRICT;

CREATE TABLE sub_base (
  id BLOB PRIMARY KEY,
  user_id BLOB,
  created_at INT,
  pinned_at INT,
  FOREIGN KEY(user_id) REFERENCES user(id)
) STRICT;

CREATE TABLE sub_feed (
  id BLOB PRIMARY KEY,
  user_id BLOB,
  created_at INT,
  pinned_at INT,
  feed_id BLOB,
  FOREIGN KEY(user_id) REFERENCES user(id),
  FOREIGN KEY(feed_id) REFERENCES feed(id)
) STRICT;

CREATE TABLE sub_email (
  id BLOB PRIMARY KEY,
  user_id BLOB,
  created_at INT,
  pinned_at INT,
  from_email TEXT,
  unsubscribed_at INT,
  FOREIGN KEY(user_id) REFERENCES user(id)
) STRICT;

CREATE TABLE item_base (
  id BLOB PRIMARY KEY,
  ingested_at INT,
  title TEXT,
  url TEXT,
  redirect_urls BLOB,
  content TEXT,
  content_key BLOB,
  published_at INT,
  excerpt TEXT,
  author_name TEXT,
  author_url TEXT,
  feed_url TEXT,
  lang TEXT,
  site_name TEXT,
  byline TEXT,
  length INT,
  image_url TEXT,
  paywalled INT
) STRICT;

CREATE TABLE item_feed (
  id BLOB PRIMARY KEY,
  ingested_at INT,
  title TEXT,
  url TEXT,
  redirect_urls BLOB,
  content TEXT,
  content_key BLOB,
  published_at INT,
  excerpt TEXT,
  author_name TEXT,
  author_url TEXT,
  feed_url TEXT,
  lang TEXT,
  site_name TEXT,
  byline TEXT,
  length INT,
  image_url TEXT,
  paywalled INT,
  feed_id BLOB,
  guid TEXT,
  FOREIGN KEY(feed_id) REFERENCES feed(id)
) STRICT;

CREATE TABLE item_email (
  id BLOB PRIMARY KEY,
  ingested_at INT,
  title TEXT,
  url TEXT,
  redirect_urls BLOB,
  content TEXT,
  content_key BLOB,
  published_at INT,
  excerpt TEXT,
  author_name TEXT,
  author_url TEXT,
  feed_url TEXT,
  lang TEXT,
  site_name TEXT,
  byline TEXT,
  length INT,
  image_url TEXT,
  paywalled INT,
  sub_id BLOB,
  raw_content_key BLOB,
  list_unsubscribe TEXT,
  list_unsubscribe_post TEXT,
  reply_to TEXT,
  maybe_confirmation INT,
  FOREIGN KEY(sub_id) REFERENCES sub_base(id)
) STRICT;

CREATE TABLE item_direct (
  id BLOB PRIMARY KEY,
  ingested_at INT,
  title TEXT,
  url TEXT,
  redirect_urls BLOB,
  content TEXT,
  content_key BLOB,
  published_at INT,
  excerpt TEXT,
  author_name TEXT,
  author_url TEXT,
  feed_url TEXT,
  lang TEXT,
  site_name TEXT,
  byline TEXT,
  length INT,
  image_url TEXT,
  paywalled INT,
  doc_type INT CHECK (doc_type = 0), -- :item/direct
  candidate_status INT CHECK (candidate_status IN (0, 1, 2)) -- :ingest-failed (0), :blocked (1), :approved (2)
) STRICT;

CREATE TABLE redirect (
  id BLOB PRIMARY KEY,
  url TEXT,
  item_id BLOB,
  FOREIGN KEY(item_id) REFERENCES item_base(id)
) STRICT;

CREATE TABLE feed (
  id BLOB PRIMARY KEY,
  url TEXT,
  synced_at INT,
  title TEXT,
  description TEXT,
  image_url TEXT,
  etag TEXT,
  last_modified TEXT,
  failed_syncs INT,
  moderation INT CHECK (moderation IN (0, 1)) -- :approved (0), :blocked (1)
) STRICT;

CREATE TABLE user_item (
  id BLOB PRIMARY KEY,
  user_id BLOB,
  item_id BLOB,
  viewed_at INT,
  skipped_at INT,
  bookmarked_at INT,
  favorited_at INT,
  disliked_at INT,
  reported_at INT,
  report_reason TEXT,
  FOREIGN KEY(user_id) REFERENCES user(id),
  FOREIGN KEY(item_id) REFERENCES item_base(id)
) STRICT;

CREATE TABLE digest (
  id BLOB PRIMARY KEY,
  user_id BLOB,
  sent_at INT,
  subject_item_id BLOB,
  ad_id BLOB,
  bulk_send_id BLOB,
  FOREIGN KEY(user_id) REFERENCES user(id),
  FOREIGN KEY(subject_item_id) REFERENCES item_base(id),
  FOREIGN KEY(ad_id) REFERENCES ad(id),
  FOREIGN KEY(bulk_send_id) REFERENCES bulk_send(id)
) STRICT;

CREATE TABLE digest_item (
  id BLOB PRIMARY KEY,
  digest_id BLOB,
  item_id BLOB,
  kind INT CHECK (kind IN (0, 1)), -- :icymi (0), :discover (1)
  FOREIGN KEY(digest_id) REFERENCES digest(id),
  FOREIGN KEY(item_id) REFERENCES item_base(id)
) STRICT;

CREATE TABLE bulk_send (
  id BLOB PRIMARY KEY,
  sent_at INT,
  payload_size INT,
  mailersend_id TEXT,
  digests BLOB -- Serialized list of digest UUIDs
) STRICT;

CREATE TABLE reclist (
  id BLOB PRIMARY KEY,
  user_id BLOB,
  created_at INT,
  clicked BLOB, -- Serialized set of UUIDs
  FOREIGN KEY(user_id) REFERENCES user(id)
) STRICT;

CREATE TABLE skip (
  id BLOB PRIMARY KEY,
  reclist_id BLOB,
  item_id BLOB,
  FOREIGN KEY(reclist_id) REFERENCES reclist(id),
  FOREIGN KEY(item_id) REFERENCES item_base(id)
) STRICT;

CREATE TABLE ad (
  id BLOB PRIMARY KEY,
  user_id BLOB,
  approve_state INT CHECK (approve_state IN (0, 1, 2)), -- :pending (0), :approved (1), :rejected (2)
  updated_at INT,
  balance INT,
  recent_cost INT,
  bid INT,
  budget INT,
  url TEXT,
  title TEXT,
  description TEXT,
  image_url TEXT,
  paused INT,
  payment_failed INT,
  customer_id TEXT,
  session_id TEXT,
  payment_method TEXT,
  card_details BLOB, -- Serialized map
  FOREIGN KEY(user_id) REFERENCES user(id)
) STRICT;

CREATE TABLE ad_click (
  id BLOB PRIMARY KEY,
  user_id BLOB,
  ad_id BLOB,
  created_at INT,
  cost INT,
  source INT CHECK (source IN (0, 1)), -- :web (0), :email (1)
  FOREIGN KEY(user_id) REFERENCES user(id),
  FOREIGN KEY(ad_id) REFERENCES ad(id)
) STRICT;

CREATE TABLE ad_credit (
  id BLOB PRIMARY KEY,
  ad_id BLOB,
  source INT CHECK (source IN (0, 1)), -- :charge (0), :manual (1)
  amount INT,
  created_at INT,
  charge_status INT CHECK (charge_status IN (0, 1, 2)), -- :pending (0), :confirmed (1), :failed (2)
  FOREIGN KEY(ad_id) REFERENCES ad(id)
) STRICT;

CREATE TABLE mv_sub (
  id BLOB PRIMARY KEY,
  sub_id BLOB,
  affinity_low BLOB, -- Serialized double
  affinity_high BLOB, -- Serialized double
  last_published INT,
  unread INT,
  read INT,
  FOREIGN KEY(sub_id) REFERENCES sub_base(id)
) STRICT;

CREATE TABLE mv_user (
  id BLOB PRIMARY KEY,
  user_id BLOB,
  current_item_id BLOB,
  FOREIGN KEY(user_id) REFERENCES user(id),
  FOREIGN KEY(current_item_id) REFERENCES item_base(id)
) STRICT;

CREATE TABLE deleted_user (
  id BLOB PRIMARY KEY,
  email_username_hash TEXT
) STRICT;
