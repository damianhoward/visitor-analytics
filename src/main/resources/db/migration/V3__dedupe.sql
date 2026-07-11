-- Delivery into this table is at-least-once: the tailer resumes from a persisted offset, the
-- write-ahead buffer replays on reconnect, and the cross-box shipper refetches on a partial
-- failure. A replayed line maps to the same key here and its insert is ignored, so storage
-- stays exact.
CREATE UNIQUE INDEX ux_visits_dedupe ON visits (site, ip_hash, visited_at, path);
