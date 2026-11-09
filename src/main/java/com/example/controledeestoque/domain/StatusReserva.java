package com.example.controledeestoque.domain;

public enum StatusReserva {
    /** Unidades separadas para o pedido, fora do saldo disponível. */
    ATIVA,
    /** Reserva cancelada: as unidades voltaram para o disponível. */
    LIBERADA,
    /** Reserva consumida: as unidades saíram do estoque (baixa do total). */
    EFETIVADA
}
