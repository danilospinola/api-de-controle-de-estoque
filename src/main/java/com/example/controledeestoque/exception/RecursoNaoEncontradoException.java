package com.example.controledeestoque.exception;

import java.util.UUID;

public class RecursoNaoEncontradoException extends RuntimeException {

    public RecursoNaoEncontradoException(String recurso, UUID id) {
        super("%s não encontrado(a): %s".formatted(recurso, id));
    }
}
