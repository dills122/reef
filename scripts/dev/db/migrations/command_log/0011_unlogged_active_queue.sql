-- command_work_queue is derived active scheduling state.
-- Durable accepted commands live in command_log.commands and terminal outcomes live
-- in command_log.command_results, so the active queue can be reconstructed after
-- restart while avoiding WAL churn on claim/delete-heavy worker traffic.

ALTER TABLE command_log.command_work_queue SET UNLOGGED;
