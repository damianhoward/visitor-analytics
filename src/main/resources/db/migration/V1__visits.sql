-- One row per kept, enriched request. ip_hash is a salted SHA-256 of the client address; the
-- raw IP is never stored. Sizes are generous byte-semantics VARCHAR2s so multibyte city/org
-- names fit without CHAR-semantics DDL (which H2's Oracle mode, used by the rollup tests,
-- does not parse).
CREATE TABLE visits (
    id          NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    site        VARCHAR2(128)  NOT NULL,
    path        VARCHAR2(512)  NOT NULL,
    engaged     NUMBER(1)      NOT NULL,
    country     VARCHAR2(128),
    city        VARCHAR2(256),
    asn         NUMBER(19),
    org         VARCHAR2(256),
    browser     VARCHAR2(64)   NOT NULL,
    os          VARCHAR2(64)   NOT NULL,
    device_kind VARCHAR2(16)   NOT NULL,
    ip_hash     VARCHAR2(64)   NOT NULL,
    referrer    VARCHAR2(512),
    visited_at  TIMESTAMP      NOT NULL
);

CREATE INDEX ix_visits_at ON visits (visited_at);
CREATE INDEX ix_visits_country ON visits (country);
CREATE INDEX ix_visits_site ON visits (site);
