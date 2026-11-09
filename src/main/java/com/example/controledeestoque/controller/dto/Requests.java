package com.example.controledeestoque.controller.dto;

import com.example.controledeestoque.domain.Bloqueio;
import com.example.controledeestoque.domain.Produto;
import com.example.controledeestoque.domain.Reserva;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Payloads de entrada da API. Validação sintática aqui; regras de negócio no domínio. */
public final class Requests {

    private Requests() {
    }

    public record CriarProdutoRequest(
            @Schema(example = "Teclado mecânico ABNT2", description = "Nome do produto (obrigatório, até 150 caracteres)")
            @NotBlank @Size(max = Produto.TAMANHO_MAXIMO_NOME) String nome,
            @Schema(example = "100", description = "Saldo inicial disponível (opcional, padrão 0)")
            @PositiveOrZero Integer quantidadeInicial) {

        public int quantidadeInicialOuZero() {
            return quantidadeInicial == null ? 0 : quantidadeInicial;
        }
    }

    public record MovimentacaoRequest(
            @Schema(example = "10")
            @NotNull @Positive Integer quantidade) {
    }

    public record CriarReservaRequest(
            @Schema(example = "PED-2026-0001")
            @NotBlank @Size(max = Reserva.TAMANHO_MAXIMO_PEDIDO) String pedidoId,
            @Schema(example = "5")
            @NotNull @Positive Integer quantidade) {
    }

    public record CriarBloqueioRequest(
            @Schema(example = "PED-2026-0001")
            @NotBlank @Size(max = Reserva.TAMANHO_MAXIMO_PEDIDO) String pedidoId,
            @Schema(example = "Avaria identificada na conferência", description = "Opcional")
            @Size(max = Bloqueio.TAMANHO_MAXIMO_MOTIVO) String motivo,
            @Schema(example = "2")
            @NotNull @Positive Integer quantidade) {
    }
}
