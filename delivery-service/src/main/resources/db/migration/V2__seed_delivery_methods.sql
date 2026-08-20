INSERT INTO delivery_methods
    (code, name, description, base_price, free_delivery_from, eta_min_days, eta_max_days, active, created_at, updated_at)
VALUES
    ('STANDARD_COURIER', 'Kurier standard', 'Dostawa pod wskazany adres w dni robocze.', 14.99, 500.00, 1, 2, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PARCEL_LOCKER', 'Paczkomat', 'Odbiór w wybranym automacie paczkowym.', 11.99, 300.00, 1, 2, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('STORE_PICKUP', 'Odbiór osobisty', 'Odbiór zamówienia w punkcie MiniTech Store.', 0.00, NULL, 0, 1, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
