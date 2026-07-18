-- Pre-release cutover: Arena owns bot limits and ownership records.
-- These tables were created by admin/0003 before that boundary existed.
DROP TABLE IF EXISTS admin.user_bot_ownerships;
DROP TABLE IF EXISTS admin.user_bot_limits;
