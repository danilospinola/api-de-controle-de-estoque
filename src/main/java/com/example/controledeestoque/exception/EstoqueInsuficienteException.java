package com.example.controledeestoque.exception;

import java.util.UUID;

public class EstoqueInsuficienteException extends RegraNegocioException {

    public EstoqueInsuficienteException(int solicitada, int disponivel) {
        this(null, solicitada, disponivel);
    }

    /** Com o id do produto: numa reserva de vários itens, o cliente precisa saber qual item faltou. */
    public EstoqueInsuficienteException(UUID produtoId, int solicitada, int disponivel) {
        super("Quantidade disponível insuficiente%s: solicitada=%d, disponível=%d".formatted(
                produtoId == null ? "" : " no produto " + produtoId, solicitada, disponivel));
    }
}
