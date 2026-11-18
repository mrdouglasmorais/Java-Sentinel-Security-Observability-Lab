-- Synthetic lab data. Loaded only by the "lab" profile. Owners match usernames in the Keycloak realm.
-- Fixed ids make k6 scripts and walkthroughs reproducible.

INSERT INTO account (id, owner_id, holder_name, currency, balance) VALUES
    ('0193a000-0000-7000-8000-00000000a001', 'alice',    'Alice Andrade (checking)', 'BRL', 1000000.00),
    ('0193a000-0000-7000-8000-00000000a002', 'alice',    'Alice Andrade (savings)',  'BRL',  250000.00),
    ('0193a000-0000-7000-8000-00000000b001', 'bob',      'Bob Barbosa (checking)',   'BRL', 1000000.00),
    ('0193a000-0000-7000-8000-00000000c001', 'merchant', 'Loja Exemplo LTDA',        'BRL',      10.00)
ON CONFLICT (id) DO NOTHING;
