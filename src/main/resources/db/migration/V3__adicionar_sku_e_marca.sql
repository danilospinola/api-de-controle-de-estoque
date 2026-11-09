-- SKU: chave de negócio do produto (obrigatória e única) e marca (opcional).
-- Sem o SKU, o mesmo item podia ser cadastrado várias vezes, cada cadastro com o próprio saldo.
--
-- A V1 e a V2 já estão aplicadas no RDS, por isso a mudança entra como nova migration. Os produtos
-- que já existem recebem um SKU provisório e único (LEGADO-<id sem hífens>) antes de a coluna
-- se tornar obrigatória.
ALTER TABLE produto ADD COLUMN sku VARCHAR(50);
ALTER TABLE produto ADD COLUMN marca VARCHAR(100);

UPDATE produto
   SET sku = 'LEGADO-' || UPPER(REPLACE(CAST(id AS VARCHAR(36)), '-', ''))
 WHERE sku IS NULL;

ALTER TABLE produto ALTER COLUMN sku SET NOT NULL;
ALTER TABLE produto ADD CONSTRAINT uk_produto_sku UNIQUE (sku);

-- Mesmo formato do domínio, já em maiúsculas: letras sem acento, números e hífens entre eles.
-- REGEXP_LIKE existe no PostgreSQL 15+ e no H2 usado nos testes.
ALTER TABLE produto ADD CONSTRAINT ck_produto_sku_formato CHECK (REGEXP_LIKE(sku, '^[A-Z0-9]+(-[A-Z0-9]+)*$'));
ALTER TABLE produto ADD CONSTRAINT ck_produto_marca_preenchida CHECK (marca IS NULL OR LENGTH(TRIM(marca)) > 0);
