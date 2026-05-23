# Analytics domain migrations

Forward-only migrations for transformed analytics tables/views.

Guidance:
- analytics objects must not be required by runtime hot write paths
- no cross-domain foreign keys
- prioritize read-optimized structures and explicit refresh contracts
