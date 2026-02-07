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
