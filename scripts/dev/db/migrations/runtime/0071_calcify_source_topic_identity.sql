-- Bind each logical generation to its broker topic ID on first extractor start.
ALTER TABLE runtime.calcify_source_generations
    ADD COLUMN source_topic_id text;
