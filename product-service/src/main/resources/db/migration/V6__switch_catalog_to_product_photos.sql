-- Local product photos generated for the MiniTech Store catalog.
-- The images contain products only: no people, faces or hands.
UPDATE products
SET image_url = CASE category
    WHEN 'CPU' THEN '/assets/products/cpu-photo.png'
    WHEN 'GPU' THEN '/assets/products/gpu-photo.png'
    WHEN 'RAM' THEN '/assets/products/ram-photo.png'
    WHEN 'SSD' THEN '/assets/products/ssd-photo.png'
    WHEN 'Płyta główna' THEN '/assets/products/motherboard-photo.png'
    WHEN 'Monitor' THEN '/assets/products/monitor-photo.png'
    WHEN 'Laptop' THEN '/assets/products/laptop-photo.png'
    WHEN 'Klawiatura' THEN '/assets/products/keyboard-photo.png'
    WHEN 'Słuchawki' THEN '/assets/products/audio-photo.png'
    ELSE image_url
END
WHERE category IN (
    'CPU', 'GPU', 'RAM', 'SSD', 'Płyta główna', 'Monitor', 'Laptop',
    'Klawiatura', 'Słuchawki'
);
