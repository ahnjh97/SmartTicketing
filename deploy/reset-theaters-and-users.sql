-- Explicit full account + theater reset, preserving movie catalog and schema.
-- Run with the backend stopped and foreign keys enabled.
CREATE TEMPORARY TABLE reset_guard (ok INT NOT NULL CHECK (ok = 1));
INSERT INTO reset_guard SELECT IF(COUNT(*) = 0, 1, 0)
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND engine <> 'InnoDB';
START TRANSACTION;
SET @movies_before = (SELECT COUNT(*) FROM movies);

DELETE FROM notifications;
DELETE FROM booking_operations;
DELETE FROM booking_group_holds;
DELETE FROM tickets;
DELETE FROM payments;
DELETE FROM reservation_seats;
DELETE FROM showtime_seats;
DELETE FROM reservations;
DELETE FROM waiting_queue_seats;
DELETE FROM waiting_queues;
DELETE FROM waiting_zone_sequences;
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
DELETE FROM user_preferred_seats;
DELETE FROM user_social_accounts;
DELETE FROM booking_user_limits;
DELETE FROM users;

INSERT INTO reset_guard SELECT IF(
    @movies_before = (SELECT COUNT(*) FROM movies)
    AND (SELECT COUNT(*) FROM users) = 0
    AND (SELECT COUNT(*) FROM user_social_accounts) = 0, 1, 0);
SELECT 'remaining_users', COUNT(*) FROM users;
SELECT 'remaining_social_accounts', COUNT(*) FROM user_social_accounts;
SELECT 'preserved_movies', COUNT(*) FROM movies;
SELECT 'remaining_theaters', COUNT(*) FROM theaters;
SELECT 'remaining_showtimes', COUNT(*) FROM showtimes;
COMMIT;
