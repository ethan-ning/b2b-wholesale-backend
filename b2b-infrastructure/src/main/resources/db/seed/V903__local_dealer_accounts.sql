-- Dealer logins for local development ONLY, same as the other db/seed files.
--
-- Without these, every fresh database needs the admin-creates-a-customer dance before
-- anyone can open the dealer portal at all. Both are seeded with the password change
-- already done, so `must_change_password` is false and login lands straight on the
-- catalog; to exercise the forced-change screen, create a customer from the admin
-- portal and use the temporary password it hands back.
--
-- Password for both: dealer123 (BCrypt cost 12, matching BCryptPasswordHasher).

INSERT INTO customer (email, password_hash, name, company_name, tier_id, phone, must_change_password, status) VALUES
    ('dealer1@example.com', '$2a$12$eG7IytiuhsJ8iKn/083kI.gezVdnfcfj0k7y4g/44w1.ph/ccR8s6',
     'Gold Dealer', 'Northgate Truck Supply', 1, '555-0101', false, 'ACTIVE'),
    ('dealer2@example.com', '$2a$12$eG7IytiuhsJ8iKn/083kI.gezVdnfcfj0k7y4g/44w1.ph/ccR8s6',
     'Silver Dealer', 'Cross Creek Auto', 2, '555-0102', false, 'ACTIVE')
ON CONFLICT (email) DO NOTHING;
