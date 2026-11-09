-- A V1 já foi aplicada em uma base persistente (RDS): mudanças de schema entram como novas
-- migrations, nunca editando uma versão aplicada (o Flyway recusaria o checksum alterado).
-- Defesa em profundidade da regra do domínio: pedido_id não pode ser vazio nem só espaços.
ALTER TABLE reserva ADD CONSTRAINT ck_reserva_pedido_preenchido CHECK (LENGTH(TRIM(pedido_id)) > 0);
ALTER TABLE bloqueio ADD CONSTRAINT ck_bloqueio_pedido_preenchido CHECK (LENGTH(TRIM(pedido_id)) > 0);
