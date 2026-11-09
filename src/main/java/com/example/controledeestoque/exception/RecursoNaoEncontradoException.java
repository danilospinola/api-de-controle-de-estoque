package com.example.controledeestoque.exception;

import java.util.UUID;

public class RecursoNaoEncontradoException extends RuntimeException {

    public RecursoNaoEncontradoException(String recurso, UUID id) {
        super("%s com id %s não existe.".formatted(recurso, id));
    }
}
