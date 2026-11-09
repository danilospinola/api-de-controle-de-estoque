package com.example.controledeestoque.exception;

/**
 * Lançada quando a operação não conseguiu ser concluída após esgotar as tentativas
 * por conflito de versão (lock otimista). O cliente pode repetir a requisição.
 */
public class ConflitoConcorrenciaException extends RuntimeException {

    public ConflitoConcorrenciaException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
