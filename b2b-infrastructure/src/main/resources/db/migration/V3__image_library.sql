-- Images become their own thing, shared between products, rather than rows owned by one.
--
-- The old product_image carried the URL itself, so the same photo used by two products was
-- two rows with no way to know they were the same file. A library needs one row per image
-- and a join, or "which products use this" and "is anything still using it" have no answer.

CREATE TABLE image (
    id            BIGSERIAL PRIMARY KEY,
    -- What a browser loads. For an upload this is derived from object_key; rows migrated
    -- from the old table are links to somewhere else entirely, which is why it is stored
    -- rather than always computed.
    url           TEXT NOT NULL,
    -- Where it lives in the bucket. Null for an image we did not store.
    object_key    TEXT UNIQUE,
    filename      TEXT NOT NULL,
    content_type  TEXT,
    bytes         BIGINT CHECK (bytes IS NULL OR bytes > 0),
    width         INT,
    height        INT,
    alt_text      TEXT,
    uploaded_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- One row per distinct URL: the same photo attached to two products was two rows before,
-- and collapsing them is the point of the change.
INSERT INTO image (url, filename, alt_text)
SELECT url,
       -- Last path segment, query string dropped: a name is for recognising the file in a
       -- list, and "400x300?text=LT300-WHT" is not one.
       COALESCE(NULLIF(regexp_replace(regexp_replace(url, '\?.*$', ''), '^.*/', ''), ''), 'image'),
       MIN(alt_text)
FROM product_image
GROUP BY url;

ALTER TABLE product_image ADD COLUMN image_id BIGINT REFERENCES image (id) ON DELETE CASCADE;
UPDATE product_image pi SET image_id = i.id FROM image i WHERE i.url = pi.url;
ALTER TABLE product_image ALTER COLUMN image_id SET NOT NULL;

ALTER TABLE product_image DROP COLUMN url;
ALTER TABLE product_image DROP COLUMN alt_text;

-- A product cannot hold the same image twice; the gallery would show it twice.
ALTER TABLE product_image ADD CONSTRAINT product_image_unique UNIQUE (product_id, image_id);
CREATE INDEX idx_product_image_image ON product_image (image_id);

-- Which of its product's images represents this SKU. SET NULL rather than CASCADE: losing
-- the photo must not take the SKU with it.
--
-- That it belongs to *this SKU's product* is not expressible here — a foreign key cannot
-- reach through product_variant to product_image — so the use case enforces it.
ALTER TABLE product_variant
    ADD COLUMN main_image_id BIGINT REFERENCES image (id) ON DELETE SET NULL;
