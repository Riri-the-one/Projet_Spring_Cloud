-- Nullable additions preserve historical profiles. Never infer identity from email.
ALTER TABLE customers ADD COLUMN identity_issuer varchar(512);
ALTER TABLE customers ADD COLUMN identity_subject varchar(255);
ALTER TABLE customers ADD CONSTRAINT uk_customer_identity UNIQUE (identity_issuer, identity_subject);
ALTER TABLE customers ADD CONSTRAINT ck_customer_identity_pair CHECK (
    (identity_issuer IS NULL AND identity_subject IS NULL)
    OR (identity_issuer IS NOT NULL AND identity_subject IS NOT NULL
        AND length(trim(identity_issuer)) > 0 AND length(trim(identity_subject)) > 0)
);
