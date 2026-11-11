CREATE TABLE pickup_locations (
    code VARCHAR(40) PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    active BOOLEAN NOT NULL
);

INSERT INTO pickup_locations (code, name, active)
VALUES ('WARSAW_STORE', 'MiniTech Store — Warszawa', TRUE);
