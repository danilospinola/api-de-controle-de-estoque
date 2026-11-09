package com.example.controledeestoque.exception;

/**
 * Violação de regra de negócio do estoque. A operação é rejeitada e nada é alterado.
 */
public class RegraNegocioException extends RuntimeException {

    public RegraNegocioException(String mensagem) {
        super(mensagem);
    }
}
