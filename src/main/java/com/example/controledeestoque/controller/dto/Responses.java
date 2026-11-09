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

    @Schema(description = "Saldo do produto: total = disponível + reservada + bloqueada")
    public record ProdutoResponse(
            @Schema(description = "Identificador do produto, usado nas demais operações") UUID id,
            @Schema(description = "Código único do produto (SKU), em maiúsculas", example = "TEC-LOGI-K120") String sku,
            @Schema(description = "Nome do produto") String nome,
            @Schema(description = "Marca (pode ser nula)") String marca,
            @Schema(description = "Unidades em estoque (disponível + reservada + bloqueada)") int quantidadeTotal,
            @Schema(description = "Livres para saída, reserva ou bloqueio") int quantidadeDisponivel,
            @Schema(description = "Separadas para pedidos (reservas ATIVAS)") int quantidadeReservada,
            @Schema(description = "Indisponíveis para uso (bloqueios ATIVOS)") int quantidadeBloqueada,
            @Schema(description = "Versão do registro para controle de concorrência; aumenta a cada alteração")
            long version) {

        public static ProdutoResponse de(Produto produto) {
            return new ProdutoResponse(
                    produto.getId(),
                    produto.getSku(),
                    produto.getNome(),
                    produto.getMarca(),
                    produto.getQuantidadeTotal(),
                    produto.getQuantidadeDisponivel(),
                    produto.getQuantidadeReservada(),
                    produto.getQuantidadeBloqueada(),
                    produto.getVersion());
        }
    }

    @Schema(description = "Reserva de unidades de um produto para um pedido")
    public record ReservaResponse(
            @Schema(description = "Identificador da reserva, usado para consultar, liberar ou efetivar") UUID id,
            @Schema(description = "Produto reservado") UUID produtoId,
            @Schema(description = "Pedido no sistema de origem") String pedidoId,
            @Schema(description = "Unidades reservadas") int quantidade,
            @Schema(description = "ATIVA (separada), LIBERADA (devolvida ao disponível) ou EFETIVADA (saiu do estoque)")
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

    @Schema(description = "Bloqueio de unidades de um produto")
    public record BloqueioResponse(
            @Schema(description = "Identificador do bloqueio, usado para consultar ou liberar") UUID id,
            @Schema(description = "Produto bloqueado") UUID produtoId,
            @Schema(description = "Solicitação no sistema de origem") String pedidoId,
            @Schema(description = "Motivo informado (opcional)") String motivo,
            @Schema(description = "Unidades bloqueadas") int quantidade,
            @Schema(description = "ATIVO (indisponível) ou LIBERADO (devolvido ao disponível)") StatusBloqueio status,
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
