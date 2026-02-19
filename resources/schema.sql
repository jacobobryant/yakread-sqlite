-- Auto-generated; do not edit.

CREATE TABLE ad (
  id BLOB PRIMARY KEY NOT NULL,
  user_id BLOB NOT NULL,
  approve_state INT NOT NULL CHECK (approve_state IN (0, 1, 2)), -- pending (0), approved (1), rejected (2)
  updated_at INT NOT NULL,
  balance INT NOT NULL,
  recent_cost INT NOT NULL,
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
  card_details BLOB,
  FOREIGN KEY(user_id) REFERENCES user(id)
) STRICT;

CREATE TABLE ad_click (
  id BLOB PRIMARY KEY NOT NULL,
  user_id BLOB NOT NULL,
  ad_id BLOB NOT NULL,
  created_at INT NOT NULL,
  cost INT NOT NULL,
  source INT NOT NULL CHECK (source IN (0, 1)), -- web (0), email (1)
  FOREIGN KEY(user_id) REFERENCES user(id),
  FOREIGN KEY(ad_id) REFERENCES ad(id)
) STRICT;

CREATE TABLE ad_credit (
  id BLOB PRIMARY KEY NOT NULL,
  ad_id BLOB NOT NULL,
  source INT NOT NULL CHECK (source IN (0, 1)), -- charge (0), manual (1)
  amount INT NOT NULL,
  created_at INT NOT NULL,
  charge_status INT CHECK (charge_status IN (0, 1, 2)), -- pending (0), confirmed (1), failed (2)
  FOREIGN KEY(ad_id) REFERENCES ad(id)
) STRICT;

CREATE TABLE bulk_send (
  id BLOB PRIMARY KEY NOT NULL,
  sent_at INT NOT NULL,
  payload_size INT NOT NULL,
  mailersend_id TEXT NOT NULL,
  digests BLOB NOT NULL
) STRICT;

CREATE TABLE deleted_user (
  id BLOB PRIMARY KEY NOT NULL,
  email_username_hash TEXT NOT NULL
) STRICT;

CREATE TABLE digest (
  id BLOB PRIMARY KEY NOT NULL,
  user_id BLOB NOT NULL,
  sent_at INT NOT NULL,
  subject_id BLOB,
  ad_id BLOB,
  bulk_send_id BLOB,
  FOREIGN KEY(user_id) REFERENCES user(id),
  FOREIGN KEY(subject_id) REFERENCES item(id),
  FOREIGN KEY(ad_id) REFERENCES ad(id),
  FOREIGN KEY(bulk_send_id) REFERENCES bulk_send(id)
) STRICT;

CREATE TABLE digest_item (
  id BLOB PRIMARY KEY NOT NULL,
  digest_id BLOB NOT NULL,
  item_id BLOB NOT NULL,
  kind INT NOT NULL CHECK (kind IN (0, 1)), -- icymi (0), discover (1)
  FOREIGN KEY(digest_id) REFERENCES digest(id),
  FOREIGN KEY(item_id) REFERENCES item(id)
) STRICT;

CREATE TABLE feed (
  id BLOB PRIMARY KEY NOT NULL,
  url TEXT NOT NULL,
  synced_at INT,
  title TEXT,
  description TEXT,
  image_url TEXT,
  etag TEXT,
  last_modified TEXT,
  failed_syncs INT,
  moderation INT CHECK (moderation IN (0, 1)) -- approved (0), blocked (1)
) STRICT;

CREATE TABLE item (
  id BLOB PRIMARY KEY NOT NULL,
  ingested_at INT NOT NULL,
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
  record_type INT NOT NULL CHECK (record_type IN (0, 1, 2)), -- feed (0), email (1), direct (2)
  feed_id BLOB,
  feed_guid TEXT,
  email_sub_id BLOB,
  email_raw_content_key BLOB,
  email_list_unsubscribe TEXT,
  email_list_unsubscribe_post TEXT,
  email_reply_to TEXT,
  email_maybe_confirmation INT,
  direct_candidate_status INT CHECK (direct_candidate_status IN (0, 1, 2)), -- ingest-failed (0), blocked (1), approved (2)
  FOREIGN KEY(feed_id) REFERENCES feed(id),
  FOREIGN KEY(email_sub_id) REFERENCES sub(id)
) STRICT;

CREATE TABLE mv_sub (
  id BLOB PRIMARY KEY NOT NULL,
  sub_id BLOB NOT NULL,
  affinity_low REAL,
  affinity_high REAL,
  last_published INT,
  unread INT,
  n_read INT,
  FOREIGN KEY(sub_id) REFERENCES sub(id)
) STRICT;

CREATE TABLE mv_user (
  id BLOB PRIMARY KEY NOT NULL,
  user_id BLOB NOT NULL,
  current_item_id BLOB,
  FOREIGN KEY(user_id) REFERENCES user(id),
  FOREIGN KEY(current_item_id) REFERENCES item(id)
) STRICT;

CREATE TABLE reclist (
  id BLOB PRIMARY KEY NOT NULL,
  user_id BLOB NOT NULL,
  created_at INT NOT NULL,
  clicked BLOB NOT NULL,
  FOREIGN KEY(user_id) REFERENCES user(id)
) STRICT;

CREATE TABLE redirect (
  id BLOB PRIMARY KEY NOT NULL,
  url TEXT NOT NULL,
  item_id BLOB NOT NULL,
  FOREIGN KEY(item_id) REFERENCES item(id)
) STRICT;

CREATE TABLE skip (
  id BLOB PRIMARY KEY NOT NULL,
  reclist_id BLOB NOT NULL,
  item_id BLOB NOT NULL,
  FOREIGN KEY(reclist_id) REFERENCES reclist(id),
  FOREIGN KEY(item_id) REFERENCES item(id)
) STRICT;

CREATE TABLE sub (
  id BLOB PRIMARY KEY NOT NULL,
  user_id BLOB NOT NULL,
  created_at INT NOT NULL,
  pinned_at INT,
  record_type INT NOT NULL CHECK (record_type IN (0, 1)), -- feed (0), email (1)
  feed_id BLOB,
  email_from TEXT,
  email_unsubscribed_at INT,
  FOREIGN KEY(user_id) REFERENCES user(id),
  FOREIGN KEY(feed_id) REFERENCES feed(id)
) STRICT;

CREATE TABLE user (
  id BLOB PRIMARY KEY NOT NULL,
  email TEXT NOT NULL,
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
  plan INT CHECK (plan IN (0, 1)), -- quarter (0), annual (1)
  cancel_at INT
) STRICT;

CREATE TABLE user_item (
  id BLOB PRIMARY KEY NOT NULL,
  user_id BLOB NOT NULL,
  item_id BLOB NOT NULL,
  viewed_at INT,
  skipped_at INT,
  bookmarked_at INT,
  favorited_at INT,
  disliked_at INT,
  reported_at INT,
  report_reason TEXT,
  FOREIGN KEY(user_id) REFERENCES user(id),
  FOREIGN KEY(item_id) REFERENCES item(id)
) STRICT;

CREATE INDEX idx_user_email ON user(email);
CREATE INDEX idx_user_item_user_id ON user_item(user_id);

-- CREATE INDEX idx_sub_user_id ON sub(user_id);
-- CREATE INDEX idx_sub_feed_id ON sub(feed_id);
-- CREATE INDEX idx_item_feed_id ON item(feed_id);
-- CREATE INDEX idx_item_email_sub_id ON item(email_sub_id);
-- CREATE INDEX idx_item_candidate_status ON item(candidate_status);
-- CREATE INDEX idx_item_kind ON item(kind);
-- CREATE INDEX idx_user_item_item_id ON user_item(item_id);
-- CREATE INDEX idx_user_item_user_id ON user_item(user_id);
-- CREATE INDEX idx_user_item_favorited_at ON user_item(favorited_at);
