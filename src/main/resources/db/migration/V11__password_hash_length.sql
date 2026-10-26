-- Argon2id hashes with their {argon2} prefix are about 106 characters, over the 100 that BCrypt needed.
ALTER TABLE users ALTER COLUMN password_hash TYPE VARCHAR(255);
