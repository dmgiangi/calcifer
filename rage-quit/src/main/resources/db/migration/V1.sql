CREATE TABLE settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
CREATE TABLE events (
  id TEXT PRIMARY KEY,
  owner TEXT NOT NULL CHECK(owner IN ('user:admin', 'user:moody', 'user:frevadiscor')),
  type TEXT NOT NULL CHECK(type IN ('SMOKED', 'RESISTED')),
  effective_at TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  version INTEGER NOT NULL CHECK(version >= 1)
);
CREATE INDEX events_owner_time ON events(owner, effective_at, id);
CREATE TABLE zero_declarations (
  owner TEXT NOT NULL CHECK(owner IN ('user:admin', 'user:moody', 'user:frevadiscor')),
  date TEXT NOT NULL,
  confirmed_at TEXT NOT NULL,
  PRIMARY KEY(owner, date)
);
CREATE TABLE submissions (
  owner TEXT NOT NULL,
  submission_key TEXT NOT NULL,
  payload TEXT NOT NULL,
  event_id TEXT REFERENCES events(id) ON DELETE SET NULL,
  PRIMARY KEY(owner, submission_key)
);
