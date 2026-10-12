-- Finite P3 binding v2: the three finance topic roles share the topic-identity registry. SOURCE stays a row in the
-- source-generation registry (not a role here); existing rows and the registrar's startup-only writes are unchanged.
ALTER TABLE runtime.calcify_topic_identities
    DROP CONSTRAINT calcify_topic_identities_role_check;
ALTER TABLE runtime.calcify_topic_identities
    ADD CONSTRAINT calcify_topic_identities_role_check
    CHECK (role IN ('verified', 'output', 'COMMAND', 'CAPTURE', 'OWNERSHIP', 'CAPTURE_CHANGELOG',
                    'CONTROL', 'FINANCE_DECISIONS', 'FINANCE_CHANGELOG'));
