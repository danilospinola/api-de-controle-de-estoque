package com.example.controledeestoque.controller;

import com.example.controledeestoque.controller.dto.Requests.CriarBloqueioRequest;
import com.example.controledeestoque.controller.dto.Requests.CriarProdutoRequest;
import com.example.controledeestoque.controller.dto.Requests.CriarReservaRequest;
import com.example.controledeestoque.controller.dto.Requests.MovimentacaoRequest;
import com.example.controledeestoque.controller.dto.Responses.BloqueioResponse;
import com.example.controledeestoque.controller.dto.Responses.ProdutoResponse;
import com.example.controledeestoque.controller.dto.Responses.ReservaResponse;
import com.example.controledeestoque.domain.StatusBloqueio;
import com.example.controledeestoque.domain.StatusReserva;
import com.example.controledeestoque.service.EstoqueService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.example.controledeestoque.config.OpenApiConfig.TAG_BLOQUEIOS;
import static com.example.controledeestoque.config.OpenApiConfig.TAG_MOVIMENTACOES;
import static com.example.controledeestoque.config.OpenApiConfig.TAG_PRODUTOS;
import static com.example.controledeestoque.config.OpenApiConfig.TAG_RESERVAS;

/**
 * Saldo de estoque do produto e as operações que o alteram.
 * <p>
 * O produto é identificado pelo <b>SKU</b> nas rotas ({@code /api/produtos/{sku}/...}); maiúsculas e
 * minúsculas são equivalentes. Reservas e bloqueios criados aqui são identificados pelo {@code id}
 * devolvido no corpo.
 * <p>
 * Todo sucesso responde 200 com o recurso no corpo, inclusive os cadastros (produto, reserva e
 * bloqueio): decisão do projeto, em vez de 201 Created. Os erros mantêm os códigos 4xx/5xx (ver
 * {@link GlobalExceptionHandler}).
 * <p>
 * No Swagger, cada operação aparece na seção do fluxo a que pertence (produtos, movimentações,
 * reservas ou bloqueios), e não agrupada por controller.
 */
@RestController
@RequestMapping("/api/produtos")
@RequiredArgsConstructor
public class ProdutoController {

    private final EstoqueService estoqueService;

    @Operation(tags = TAG_PRODUTOS, summary = "Cadastrar produto",
            description = "Cria o produto com um SKU único e um saldo inicial disponível (opcional, padrão 0). "
                    + "O SKU identifica o produto em todas as demais operações. Um SKU já cadastrado responde 409: "
                    + "para acrescentar unidades a ele, use *Registrar entrada*.")
    @PostMapping
    public ProdutoResponse criar(@Valid @RequestBody CriarProdutoRequest request) {
        return ProdutoResponse.de(estoqueService.criarProduto(
                request.sku(), request.nome(), request.marca(), request.quantidadeInicialOuZero()));
    }

    @Operation(tags = TAG_PRODUTOS, summary = "Consultar saldo do produto",
            description = "Quantidades total, disponível, reservada e bloqueada do produto.")
    @GetMapping("/{sku}")
    public ProdutoResponse consultar(@PathVariable String sku) {
        return ProdutoResponse.de(estoqueService.consultarProduto(sku));
    }

    @Operation(tags = TAG_MOVIMENTACOES, summary = "Registrar entrada",
            description = "Novas unidades chegam ao estoque: total e disponível aumentam.")
    @PostMapping("/{sku}/entradas")
    public ProdutoResponse entrada(@PathVariable String sku, @Valid @RequestBody MovimentacaoRequest request) {
        return ProdutoResponse.de(estoqueService.entrada(sku, request.quantidade()));
    }

    @Operation(tags = TAG_MOVIMENTACOES, summary = "Registrar saída",
            description = "Retira unidades do disponível (total e disponível diminuem). "
                    + "Só é aceita se houver quantidade disponível suficiente; reservadas e bloqueadas não saem por aqui.")
    @PostMapping("/{sku}/saidas")
    public ProdutoResponse saida(@PathVariable String sku, @Valid @RequestBody MovimentacaoRequest request) {
        return ProdutoResponse.de(estoqueService.saida(sku, request.quantidade()));
    }

    @Operation(tags = TAG_RESERVAS, summary = "Reservar um produto",
            description = "Separa unidades disponíveis do produto para um pedido: disponível diminui e reservada aumenta. "
                    + "Guarde o `id` da reserva para liberá-la ou efetivá-la. Para vários produtos do mesmo pedido "
                    + "de uma vez, use *Reservar vários produtos de um pedido*.")
    @PostMapping("/{sku}/reservas")
    public ReservaResponse reservar(@PathVariable String sku, @Valid @RequestBody CriarReservaRequest request) {
        return ReservaResponse.de(estoqueService.reservar(sku, request.pedidoId(), request.quantidade()));
    }

    @Operation(tags = TAG_RESERVAS, summary = "Listar reservas do produto",
            description = "Todas as reservas do produto, da mais antiga para a mais recente.")
    @GetMapping("/{sku}/reservas")
    public List<ReservaResponse> listarReservas(@PathVariable String sku,
                                                @RequestParam(required = false) StatusReserva status) {
        return estoqueService.listarReservas(sku, status).stream().map(ReservaResponse::de).toList();
    }

    @Operation(tags = TAG_BLOQUEIOS, summary = "Bloquear unidades",
            description = "Torna unidades disponíveis indisponíveis para uso (ex.: avaria): disponível diminui e "
                    + "bloqueada aumenta. Guarde o `id` do bloqueio para liberá-lo.")
    @PostMapping("/{sku}/bloqueios")
    public BloqueioResponse bloquear(@PathVariable String sku, @Valid @RequestBody CriarBloqueioRequest request) {
        return BloqueioResponse.de(
                estoqueService.bloquear(sku, request.pedidoId(), request.motivo(), request.quantidade()));
    }

    @Operation(tags = TAG_BLOQUEIOS, summary = "Listar bloqueios do produto",
            description = "Todos os bloqueios do produto, do mais antigo para o mais recente.")
    @GetMapping("/{sku}/bloqueios")
    public List<BloqueioResponse> listarBloqueios(@PathVariable String sku,
                                                  @RequestParam(required = false) StatusBloqueio status) {
        return estoqueService.listarBloqueios(sku, status).stream().map(BloqueioResponse::de).toList();
    }
}
