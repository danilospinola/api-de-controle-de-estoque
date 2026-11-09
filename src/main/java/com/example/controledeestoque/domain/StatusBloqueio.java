package com.example.controledeestoque.domain;

public enum StatusBloqueio {
    /** Unidades indisponíveis para uso (ex.: avaria, inspeção de qualidade). */
    ATIVO,
    /** Bloqueio encerrado: as unidades voltaram para o disponível. */
    LIBERADO
}
