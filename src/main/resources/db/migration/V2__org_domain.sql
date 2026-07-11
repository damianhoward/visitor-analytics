-- Registrable domain of the client IP's PTR record (gs.com, virginm.net) — the "which
-- company's network" signal. Domain only, never the full hostname, which can embed the IP.
ALTER TABLE visits ADD org_domain VARCHAR2(256);
