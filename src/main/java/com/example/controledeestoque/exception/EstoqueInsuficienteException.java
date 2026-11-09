package com.example.controledeestoque.exception;

public class EstoqueInsuficienteException extends RegraNegocioException {

    public EstoqueInsuficienteException(int solicitada, int disponivel) {
        super("Quantidade disponível insuficiente: solicitada=%d, disponível=%d".formatted(solicitada, disponivel));
    }
}
