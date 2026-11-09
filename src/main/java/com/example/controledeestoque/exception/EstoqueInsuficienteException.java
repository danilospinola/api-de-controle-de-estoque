package com.example.controledeestoque.exception;

public class EstoqueInsuficienteException extends RegraNegocioException {

    public EstoqueInsuficienteException(int solicitada, int disponivel) {
        this(null, solicitada, disponivel);
    }

    /** Com o SKU do produto: numa reserva de vários itens, o cliente precisa saber qual item faltou. */
    public EstoqueInsuficienteException(String sku, int solicitada, int disponivel) {
        super("Quantidade disponível insuficiente%s: solicitada=%d, disponível=%d".formatted(
                sku == null ? "" : " no produto " + sku, solicitada, disponivel));
    }
}
