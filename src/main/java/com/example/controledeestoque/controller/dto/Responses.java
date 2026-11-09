package com.example.controledeestoque.controller.dto;

import com.example.controledeestoque.domain.Bloqueio;
import com.example.controledeestoque.domain.Produto;
import com.example.controledeestoque.domain.Reserva;
import com.example.controledeestoque.domain.StatusBloqueio;
import com.example.controledeestoque.domain.StatusReserva;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

/** Representações de saída. As entidades JPA nunca são serializadas diretamente. */
public final class Responses {

    private Responses() {
    }

    public record ProdutoResponse(
            UUID id,
            String nome,
            int quantidadeTotal,
            int quantidadeDisponivel,
            int quantidadeReservada,
            int quantidadeBloqueada,
            long version) {

        public static ProdutoResponse de(Produto produto) {
            return new ProdutoResponse(
                    produto.getId(),
                    produto.getNome(),
                    produto.getQuantidadeTotal(),
                    produto.getQuantidadeDisponivel(),
                    produto.getQuantidadeReservada(),
                    produto.getQuantidadeBloqueada(),
                    produto.getVersion());
        }
    }

    public record ReservaResponse(
            UUID id,
            UUID produtoId,
            String pedidoId,
            int quantidade,
            StatusReserva status,
            @Schema(description = "Data e hora de criação, em UTC") LocalDateTime dataCriacao) {

        public static ReservaResponse de(Reserva reserva) {
            return new ReservaResponse(
                    reserva.getId(),
                    reserva.getProduto().getId(),
                    reserva.getPedidoId(),
                    reserva.getQuantidade(),
                    reserva.getStatus(),
                    reserva.getDataCriacao());
        }
    }

    public record BloqueioResponse(
            UUID id,
            UUID produtoId,
            String pedidoId,
            String motivo,
            int quantidade,
            StatusBloqueio status,
            @Schema(description = "Data e hora de criação, em UTC") LocalDateTime dataCriacao) {

        public static BloqueioResponse de(Bloqueio bloqueio) {
            return new BloqueioResponse(
                    bloqueio.getId(),
                    bloqueio.getProduto().getId(),
                    bloqueio.getPedidoId(),
                    bloqueio.getMotivo(),
                    bloqueio.getQuantidade(),
                    bloqueio.getStatus(),
                    bloqueio.getDataCriacao());
        }
    }
}
