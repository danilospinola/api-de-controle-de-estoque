package com.example.controledeestoque.controller.dto;

import com.example.controledeestoque.domain.Bloqueio;
import com.example.controledeestoque.domain.Produto;
import com.example.controledeestoque.domain.Reserva;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Payloads de entrada da API. Validação sintática aqui; regras de negócio no domínio.
 * <p>
 * As mensagens são explícitas e em português: sem isso, o Bean Validation usa o idioma do servidor
 * (em inglês na EC2, por exemplo) e a mesma requisição teria respostas diferentes em cada ambiente.
 */
public final class Requests {

    private static final String OBRIGATORIO = "é obrigatório";
    private static final String OBRIGATORIA = "é obrigatória";
    private static final String TAMANHO_MAXIMO = "deve ter no máximo {max} caracteres";
    private static final String MAIOR_QUE_ZERO = "deve ser maior que zero";

    private Requests() {
    }

    public record CriarProdutoRequest(
            @Schema(example = "TEC-LOGI-K120", description = "Código único do produto (obrigatório, até 50 caracteres): "
                    + "letras sem acento, números e hífens entre eles. Gravado em maiúsculas; tec-01 e TEC-01 são o mesmo SKU.")
            @NotBlank(message = OBRIGATORIO)
            @Size(max = Produto.TAMANHO_MAXIMO_SKU, message = TAMANHO_MAXIMO)
            @Pattern(regexp = "\\s*" + Produto.FORMATO_SKU + "\\s*",
                    message = "deve conter apenas letras sem acento, números e hífens entre eles (ex.: TEC-LOGI-K120)")
            String sku,
            @Schema(example = "Teclado mecânico ABNT2", description = "Nome do produto (obrigatório, até 150 caracteres)")
            @NotBlank(message = OBRIGATORIO)
            @Size(max = Produto.TAMANHO_MAXIMO_NOME, message = TAMANHO_MAXIMO) String nome,
            @Schema(example = "Logitech", description = "Marca (opcional, até 100 caracteres)")
            @Size(max = Produto.TAMANHO_MAXIMO_MARCA, message = TAMANHO_MAXIMO) String marca,
            @Schema(example = "100", description = "Saldo inicial disponível (opcional, padrão 0)")
            @PositiveOrZero(message = "não pode ser negativa") Integer quantidadeInicial) {

        public int quantidadeInicialOuZero() {
            return quantidadeInicial == null ? 0 : quantidadeInicial;
        }
    }

    public record MovimentacaoRequest(
            @Schema(example = "10")
            @NotNull(message = OBRIGATORIA) @Positive(message = MAIOR_QUE_ZERO) Integer quantidade) {
    }

    public record CriarReservaRequest(
            @Schema(example = "PED-2026-0001")
            @NotBlank(message = OBRIGATORIO)
            @Size(max = Reserva.TAMANHO_MAXIMO_PEDIDO, message = TAMANHO_MAXIMO) String pedidoId,
            @Schema(example = "5")
            @NotNull(message = OBRIGATORIA) @Positive(message = MAIOR_QUE_ZERO) Integer quantidade) {
    }

    /** Limite de itens por reserva de pedido: mantém a transação curta. */
    public static final int MAXIMO_ITENS_PEDIDO = 100;

    public record ItemReservaRequest(
            @Schema(example = "TEC-LOGI-K120", description = "SKU do produto (maiúsculas e minúsculas são equivalentes)")
            @NotBlank(message = OBRIGATORIO)
            @Size(max = Produto.TAMANHO_MAXIMO_SKU, message = TAMANHO_MAXIMO) String sku,
            @Schema(example = "2")
            @NotNull(message = OBRIGATORIA) @Positive(message = MAIOR_QUE_ZERO) Integer quantidade) {
    }

    public record ReservarPedidoRequest(
            @Schema(description = "Itens do pedido: cada SKU aparece uma única vez")
            @NotEmpty(message = "deve ter ao menos um item")
            @Size(max = MAXIMO_ITENS_PEDIDO, message = "deve ter no máximo {max} itens")
            List<@NotNull(message = "não pode ser nulo") @Valid ItemReservaRequest> itens) {
    }

    public record CriarBloqueioRequest(
            @Schema(example = "PED-2026-0001")
            @NotBlank(message = OBRIGATORIO)
            @Size(max = Reserva.TAMANHO_MAXIMO_PEDIDO, message = TAMANHO_MAXIMO) String pedidoId,
            @Schema(example = "Avaria identificada na conferência", description = "Opcional")
            @Size(max = Bloqueio.TAMANHO_MAXIMO_MOTIVO, message = TAMANHO_MAXIMO) String motivo,
            @Schema(example = "2")
            @NotNull(message = OBRIGATORIA) @Positive(message = MAIOR_QUE_ZERO) Integer quantidade) {
    }
}
