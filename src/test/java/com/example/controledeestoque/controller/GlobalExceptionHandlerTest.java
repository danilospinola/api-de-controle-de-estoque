package com.example.controledeestoque.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

/** Erro inesperado: resposta 500 em português, com identificador e sem detalhes internos. */
class GlobalExceptionHandlerTest {

    @Test
    void erroInesperadoResponde500EmPortuguesSemExporDetalhesInternos() {
        ProblemDetail problema = new GlobalExceptionHandler()
                .erroInesperado(new IllegalStateException("detalhe interno que não pode vazar"));

        assertThat(problema.getStatus()).isEqualTo(500);
        assertThat(problema.getTitle()).isEqualTo("Erro interno");
        assertThat(problema.getDetail()).contains("erro inesperado").doesNotContain("detalhe interno");
        assertThat(problema.getProperties()).containsKey("identificador");
        assertThat(problema.getDetail()).contains(String.valueOf(problema.getProperties().get("identificador")));
    }
}
