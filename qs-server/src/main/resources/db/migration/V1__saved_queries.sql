-- Saved queries: canonical SQL plus layout, one row per saved version (technical design,
-- saved queries and schema drift).
CREATE TABLE saved_query (
    id             uuid PRIMARY KEY,
    owner          varchar(255) NOT NULL,
    name           varchar(255) NOT NULL,
    connection_id  varchar(255) NOT NULL,
    latest_version integer      NOT NULL,
    created_at     timestamp(6) with time zone NOT NULL
);

CREATE INDEX saved_query_owner ON saved_query (owner);

CREATE TABLE query_version (
    id                   uuid PRIMARY KEY,
    query_id             uuid         NOT NULL REFERENCES saved_query (id),
    version              integer      NOT NULL,
    sql                  text         NOT NULL,
    layout               jsonb,
    author               varchar(255) NOT NULL,
    saved_at             timestamp(6) with time zone NOT NULL,
    CONSTRAINT query_version_unique UNIQUE (query_id, version)
);
