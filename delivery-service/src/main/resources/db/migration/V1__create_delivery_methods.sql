CREATE TABLE delivery_methods (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL,
    base_price NUMERIC(19, 2) NOT NULL,
    free_delivery_from NUMERIC(19, 2),
    eta_min_days INTEGER NOT NULL,
    eta_max_days INTEGER NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
