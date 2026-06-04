-- Keep legacy command-capture columns from blocking the live store insert shape.

ALTER TABLE boundary.api_command_captures
  ALTER COLUMN command_id SET DEFAULT '';

ALTER TABLE boundary.api_command_captures
  ALTER COLUMN created_at SET DEFAULT '';
