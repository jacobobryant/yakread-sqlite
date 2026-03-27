-- Auto-generated; do not edit.

CREATE TABLE auth_code (
  email TEXT NOT NULL,
  id BLOB PRIMARY KEY NOT NULL,
  code TEXT NOT NULL,
  created_at INT NOT NULL,
  failed_attempts INT NOT NULL
) STRICT;

CREATE TABLE bulk_send (
  mailersend_id TEXT NOT NULL,
  digests BLOB,
  id BLOB PRIMARY KEY NOT NULL,
  sent_at INT NOT NULL,
  payload_size INT NOT NULL
) STRICT;

CREATE TABLE deleted_user (
  id BLOB PRIMARY KEY NOT NULL,
  email_username_hash TEXT NOT NULL
) STRICT;

CREATE TABLE feed (
  moderation INT CHECK (moderation IN (0, 1)), -- approved (0), blocked (1)
  title TEXT,
  etag TEXT,
  last_modified TEXT,
  url TEXT NOT NULL,
  id BLOB PRIMARY KEY NOT NULL,
  failed_syncs INT,
  synced_at INT,
  image_url TEXT,
  description TEXT,
  UNIQUE(url)
) STRICT;

CREATE TABLE test_rss_post (
  post_content TEXT,
  feed_title TEXT NOT NULL,
  post_url TEXT,
  published_at INT NOT NULL,
  post_title TEXT NOT NULL,
  feed_slug TEXT NOT NULL,
  id BLOB PRIMARY KEY NOT NULL
) STRICT;

CREATE TABLE user (
  timezone TEXT,
  suppressed_at INT,
  plan INT CHECK (plan IN (0, 1)), -- quarter (0), annual (1)
  id BLOB PRIMARY KEY NOT NULL,
  email_username TEXT,
  digest_last_sent INT,
  cancel_at INT,
  send_digest_at TEXT,
  roles BLOB,
  customer_id TEXT,
  email TEXT NOT NULL,
  digest_days BLOB,
  use_original_links INT,
  from_the_sample INT,
  joined_at INT
) STRICT;

CREATE TABLE ad (
  balance INT NOT NULL,
  updated_at INT NOT NULL,
  approve_state INT NOT NULL CHECK (approve_state IN (0, 1, 2)), -- pending (0), approved (1), rejected (2)
  session_id TEXT,
  paused INT,
  title TEXT,
  payment_failed INT,
  card_details BLOB,
  payment_method TEXT,
  id BLOB PRIMARY KEY NOT NULL,
  user_id BLOB NOT NULL,
  description TEXT,
  recent_cost INT NOT NULL,
  url TEXT,
  image_url TEXT,
  budget INT,
  customer_id TEXT,
  bid INT,
  FOREIGN KEY(user_id) REFERENCES user(id),
  UNIQUE(user_id)
) STRICT;

CREATE TABLE reclist (
  id BLOB PRIMARY KEY NOT NULL,
  created_at INT NOT NULL,
  clicked BLOB NOT NULL,
  user_id BLOB NOT NULL,
  FOREIGN KEY(user_id) REFERENCES user(id),
  UNIQUE(user_id, created_at)
) STRICT;

CREATE TABLE sub (
  pinned_at INT,
  email_from TEXT,
  user_id BLOB NOT NULL,
  id BLOB PRIMARY KEY NOT NULL,
  email_unsubscribed_at INT,
  feed_id BLOB,
  created_at INT NOT NULL,
  record_type INT NOT NULL CHECK (record_type IN (0, 1)), -- feed (0), email (1)
  FOREIGN KEY(user_id) REFERENCES user(id),
  FOREIGN KEY(feed_id) REFERENCES feed(id),
  UNIQUE(user_id, feed_id, email_from)
) STRICT;

CREATE TABLE ad_click (
  ad_id BLOB NOT NULL,
  user_id BLOB NOT NULL,
  source INT NOT NULL CHECK (source IN (0, 1)), -- web (0), email (1)
  id BLOB PRIMARY KEY NOT NULL,
  cost INT NOT NULL,
  created_at INT NOT NULL,
  FOREIGN KEY(ad_id) REFERENCES ad(id),
  FOREIGN KEY(user_id) REFERENCES user(id),
  UNIQUE(user_id, ad_id)
) STRICT;

CREATE TABLE ad_credit (
  ad_id BLOB NOT NULL,
  amount INT NOT NULL,
  charge_status INT CHECK (charge_status IN (0, 1, 2)), -- pending (0), confirmed (1), failed (2)
  source INT NOT NULL CHECK (source IN (0, 1)), -- charge (0), manual (1)
  id BLOB PRIMARY KEY NOT NULL,
  created_at INT NOT NULL,
  FOREIGN KEY(ad_id) REFERENCES ad(id)
) STRICT;

CREATE TABLE item (
  excerpt TEXT,
  email_reply_to TEXT,
  id BLOB PRIMARY KEY NOT NULL,
  published_at INT,
  direct_candidate_status INT CHECK (direct_candidate_status IN (0, 1, 2)), -- ingest-failed (0), blocked (1), approved (2)
  paywalled INT,
  url TEXT,
  length INT,
  image_url TEXT,
  content TEXT,
  email_maybe_confirmation INT,
  feed_url TEXT,
  site_name TEXT,
  byline TEXT,
  redirect_urls BLOB,
  content_key BLOB,
  email_raw_content_key BLOB,
  lang TEXT,
  email_list_unsubscribe_post TEXT,
  feed_guid TEXT,
  record_type INT NOT NULL CHECK (record_type IN (0, 1, 2)), -- feed (0), email (1), direct (2)
  feed_id BLOB,
  author_name TEXT,
  email_list_unsubscribe TEXT,
  title TEXT,
  author_url TEXT,
  ingested_at INT NOT NULL,
  email_sub_id BLOB,
  FOREIGN KEY(feed_id) REFERENCES feed(id),
  FOREIGN KEY(email_sub_id) REFERENCES sub(id)
) STRICT;

CREATE TABLE mv_sub (
  unread INT,
  affinity_high REAL,
  n_read INT,
  last_published INT,
  affinity_low REAL,
  sub_id BLOB NOT NULL,
  id BLOB PRIMARY KEY NOT NULL,
  FOREIGN KEY(sub_id) REFERENCES sub(id),
  UNIQUE(sub_id)
) STRICT;

CREATE TABLE digest (
  ad_id BLOB,
  subject_id BLOB,
  id BLOB PRIMARY KEY NOT NULL,
  user_id BLOB NOT NULL,
  sent_at INT NOT NULL,
  bulk_send_id BLOB,
  FOREIGN KEY(ad_id) REFERENCES ad(id),
  FOREIGN KEY(subject_id) REFERENCES item(id),
  FOREIGN KEY(user_id) REFERENCES user(id),
  FOREIGN KEY(bulk_send_id) REFERENCES bulk_send(id)
) STRICT;

CREATE TABLE mv_user (
  current_item_id BLOB,
  id BLOB PRIMARY KEY NOT NULL,
  user_id BLOB NOT NULL,
  FOREIGN KEY(current_item_id) REFERENCES item(id),
  FOREIGN KEY(user_id) REFERENCES user(id),
  UNIQUE(user_id)
) STRICT;

CREATE TABLE redirect (
  item_id BLOB NOT NULL,
  url TEXT NOT NULL,
  id BLOB PRIMARY KEY NOT NULL,
  FOREIGN KEY(item_id) REFERENCES item(id)
) STRICT;

CREATE TABLE skip (
  reclist_id BLOB NOT NULL,
  ad_id BLOB,
  item_id BLOB,
  id BLOB PRIMARY KEY NOT NULL,
  FOREIGN KEY(reclist_id) REFERENCES reclist(id),
  FOREIGN KEY(ad_id) REFERENCES ad(id),
  FOREIGN KEY(item_id) REFERENCES item(id),
  UNIQUE(reclist_id, item_id, ad_id)
) STRICT;

CREATE TABLE user_item (
  user_id BLOB NOT NULL,
  skipped_at INT,
  report_reason TEXT,
  bookmarked_at INT,
  reported_at INT,
  disliked_at INT,
  favorited_at INT,
  item_id BLOB NOT NULL,
  viewed_at INT,
  id BLOB PRIMARY KEY NOT NULL,
  FOREIGN KEY(user_id) REFERENCES user(id),
  FOREIGN KEY(item_id) REFERENCES item(id),
  UNIQUE(user_id, item_id)
) STRICT;

CREATE TABLE digest_item (
  kind INT NOT NULL CHECK (kind IN (0, 1)), -- icymi (0), discover (1)
  item_id BLOB NOT NULL,
  id BLOB PRIMARY KEY NOT NULL,
  digest_id BLOB NOT NULL,
  FOREIGN KEY(item_id) REFERENCES item(id),
  FOREIGN KEY(digest_id) REFERENCES digest(id)
) STRICT;

CREATE INDEX idx_user_email_username ON user(email_username);

CREATE INDEX idx_user_customer_id ON user(customer_id);

CREATE INDEX idx_user_email ON user(email);

CREATE INDEX idx_sub_user_id ON sub(user_id);

CREATE INDEX idx_sub_feed_id ON sub(feed_id);

CREATE INDEX idx_ad_credit_ad_id ON ad_credit(ad_id);

CREATE INDEX idx_item_direct_candidate_status ON item(direct_candidate_status);

CREATE INDEX idx_item_url ON item(url);

CREATE INDEX idx_item_record_type ON item(record_type);

CREATE INDEX idx_item_feed_id ON item(feed_id);

CREATE INDEX idx_item_ingested_at ON item(ingested_at);

CREATE INDEX idx_item_email_sub_id ON item(email_sub_id);

CREATE INDEX idx_digest_user_id ON digest(user_id);

CREATE INDEX idx_redirect_item_id ON redirect(item_id);

CREATE INDEX idx_redirect_url ON redirect(url);

CREATE INDEX idx_user_item_user_id ON user_item(user_id);

CREATE INDEX idx_user_item_item_id ON user_item(item_id);