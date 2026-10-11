-- Finite P3 shares generation/topic identity registry; SOURCE remains generation row.
-- Preserve existing verified/output identities and registrar-owned startup-only writes.
ALTER TABLE runtime.calcify_topic_identities
    DROP CONSTRAINT calcify_topic_identities_role_check;
ALTER TABLE runtime.calcify_topic_identities
    ADD CONSTRAINT calcify_topic_identities_role_check
    CHECK (role IN ('verified', 'output', 'COMMAND', 'CAPTURE', 'OWNERSHIP', 'CAPTURE_CHANGELOG'));
