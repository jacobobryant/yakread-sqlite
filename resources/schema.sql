CREATE TABLE users (
  id INT PRIMARY KEY,
  name TEXT,
  age INT,
  created_at INT,
  external_id BLOB,
  likes_cheese INT,
  plan INT check (plan IN (0, 1)),
  days BLOB,
  urls BLOB
) STRICT;
