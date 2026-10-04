-- Run only while the backend is stopped. Keep FK checks enabled and use InnoDB.
-- Account records, social identities, seat preferences and movies are preserved.
CREATE TEMPORARY TABLE reset_guard (ok INT NOT NULL CHECK (ok = 1));
INSERT INTO reset_guard SELECT IF(COUNT(*) = 0, 1, 0)
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND engine <> 'InnoDB';
START TRANSACTION;
SET @users_before = (SELECT COUNT(*) FROM users);
SET @social_before = (SELECT COUNT(*) FROM user_social_accounts);
SET @movies_before = (SELECT COUNT(*) FROM movies);
SET @preferences_before = (SELECT COUNT(*) FROM user_preferred_seats);

DELETE FROM notifications WHERE reservation_id IS NOT NULL OR booking_group_id IS NOT NULL;
DELETE FROM booking_operations;
DELETE FROM booking_group_holds;
DELETE FROM tickets;
DELETE FROM payments;
DELETE FROM reservation_seats;
DELETE FROM showtime_seats;
DELETE FROM reservations;
DELETE FROM waiting_queues;
DELETE FROM queue_counters;
DELETE FROM booking_group_seat_preferences;
DELETE FROM booking_group_theater_preferences;
DELETE FROM booking_request_groups;
DELETE FROM showtimes;
DELETE FROM seats;
DELETE FROM screens;
DELETE FROM user_preferred_theaters;
DELETE FROM user_nearby_theaters;
DELETE FROM theaters;
DELETE FROM theater_collection_progress;

INSERT INTO reset_guard SELECT IF(
    @users_before = (SELECT COUNT(*) FROM users)
    AND @social_before = (SELECT COUNT(*) FROM user_social_accounts)
    AND @movies_before = (SELECT COUNT(*) FROM movies)
    AND @preferences_before = (SELECT COUNT(*) FROM user_preferred_seats), 1, 0);
SELECT 'preserved_users', COUNT(*) FROM users;
SELECT 'preserved_social_accounts', COUNT(*) FROM user_social_accounts;
SELECT 'preserved_movies', COUNT(*) FROM movies;
SELECT 'remaining_theaters', COUNT(*) FROM theaters;
SELECT 'remaining_showtimes', COUNT(*) FROM showtimes;
COMMIT;
