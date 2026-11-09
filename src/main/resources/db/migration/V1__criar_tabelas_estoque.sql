-- Produto: saldo consolidado do estoque.
-- Invariante: quantidade_total = quantidade_disponivel + quantidade_reservada + quantidade_bloqueada
-- nome: obrigatório e não único (a identidade é o id).
CREATE TABLE produto (
    id                     UUID         NOT NULL,
    nome                   VARCHAR(150) NOT NULL,
    quantidade_total       INTEGER      NOT NULL,
    quantidade_disponivel  INTEGER      NOT NULL,
    quantidade_reservada   INTEGER      NOT NULL,
    quantidade_bloqueada   INTEGER      NOT NULL,
    version                BIGINT       NOT NULL,
    CONSTRAINT pk_produto PRIMARY KEY (id),
    CONSTRAINT ck_produto_nome_preenchido CHECK (LENGTH(TRIM(nome)) > 0),
    CONSTRAINT ck_produto_quantidades_nao_negativas CHECK (
        quantidade_total >= 0
        AND quantidade_disponivel >= 0
        AND quantidade_reservada >= 0
        AND quantidade_bloqueada >= 0
    ),
    CONSTRAINT ck_produto_total_consistente CHECK (
        quantidade_total = quantidade_disponivel + quantidade_reservada + quantidade_bloqueada
    )
);

CREATE TABLE reserva (
    id             UUID          NOT NULL,
    produto_id     UUID          NOT NULL,
    pedido_id      VARCHAR(100)  NOT NULL,
    quantidade     INTEGER       NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    data_criacao   TIMESTAMP     NOT NULL,
    CONSTRAINT pk_reserva PRIMARY KEY (id),
    CONSTRAINT fk_reserva_produto FOREIGN KEY (produto_id) REFERENCES produto (id),
    CONSTRAINT ck_reserva_quantidade_positiva CHECK (quantidade > 0),
    CONSTRAINT ck_reserva_status CHECK (status IN ('ATIVA', 'LIBERADA', 'EFETIVADA'))
);

CREATE INDEX ix_reserva_produto_status ON reserva (produto_id, status);
CREATE INDEX ix_reserva_pedido ON reserva (pedido_id);

CREATE TABLE bloqueio (
    id             UUID          NOT NULL,
    produto_id     UUID          NOT NULL,
    pedido_id      VARCHAR(100)  NOT NULL,
    motivo         VARCHAR(255),
    quantidade     INTEGER       NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    data_criacao   TIMESTAMP     NOT NULL,
    CONSTRAINT pk_bloqueio PRIMARY KEY (id),
    CONSTRAINT fk_bloqueio_produto FOREIGN KEY (produto_id) REFERENCES produto (id),
    CONSTRAINT ck_bloqueio_quantidade_positiva CHECK (quantidade > 0),
    CONSTRAINT ck_bloqueio_status CHECK (status IN ('ATIVO', 'LIBERADO'))
);

CREATE INDEX ix_bloqueio_produto_status ON bloqueio (produto_id, status);
CREATE INDEX ix_bloqueio_pedido ON bloqueio (pedido_id);
