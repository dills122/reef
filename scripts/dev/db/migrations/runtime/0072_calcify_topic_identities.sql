-- Bind each generation's verified/output Calcify topic to its broker topic
-- ID, mirroring calcify_source_generations.source_topic_id. Resolved/
-- verified topic recreation under the same name must not go undetected.
CREATE TABLE IF NOT EXISTS runtime.calcify_topic_identities (
    source_generation integer NOT NULL REFERENCES runtime.calcify_source_generations(source_generation),
    role text NOT NULL CHECK (role IN ('verified', 'output')),
    topic_name text NOT NULL,
    topic_id text NOT NULL,
    registered_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source_generation, role)
);
