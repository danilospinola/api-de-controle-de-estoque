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
import java.util.UUID;

import static com.example.controledeestoque.config.OpenApiConfig.TAG_BLOQUEIOS;
import static com.example.controledeestoque.config.OpenApiConfig.TAG_MOVIMENTACOES;
import static com.example.controledeestoque.config.OpenApiConfig.TAG_PRODUTOS;
import static com.example.controledeestoque.config.OpenApiConfig.TAG_RESERVAS;

/**
 * Saldo de estoque do produto e as operações que o alteram.
 * <p>
 * Todo sucesso responde 200 com o recurso no corpo, inclusive os cadastros (produto, reserva e
 * bloqueio): decisão do projeto, em vez de 201 Created. O identificador do recurso criado volta
 * no campo {@code id} do corpo. Os erros mantêm os códigos 4xx/5xx (ver {@link GlobalExceptionHandler}).
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
            description = "Cria o produto com um saldo inicial disponível (opcional, padrão 0). Guarde o `id` da resposta.")
    @PostMapping
    public ProdutoResponse criar(@Valid @RequestBody CriarProdutoRequest request) {
        return ProdutoResponse.de(estoqueService.criarProduto(request.nome(), request.quantidadeInicialOuZero()));
    }

    @Operation(tags = TAG_PRODUTOS, summary = "Consultar saldo do produto",
            description = "Quantidades total, disponível, reservada e bloqueada do produto.")
    @GetMapping("/{produtoId}")
    public ProdutoResponse consultar(@PathVariable UUID produtoId) {
        return ProdutoResponse.de(estoqueService.consultarProduto(produtoId));
    }

    @Operation(tags = TAG_MOVIMENTACOES, summary = "Registrar entrada",
            description = "Novas unidades chegam ao estoque: total e disponível aumentam.")
    @PostMapping("/{produtoId}/entradas")
    public ProdutoResponse entrada(@PathVariable UUID produtoId, @Valid @RequestBody MovimentacaoRequest request) {
        return ProdutoResponse.de(estoqueService.entrada(produtoId, request.quantidade()));
    }

    @Operation(tags = TAG_MOVIMENTACOES, summary = "Registrar saída",
            description = "Retira unidades do disponível (total e disponível diminuem). "
                    + "Só é aceita se houver quantidade disponível suficiente; reservadas e bloqueadas não saem por aqui.")
    @PostMapping("/{produtoId}/saidas")
    public ProdutoResponse saida(@PathVariable UUID produtoId, @Valid @RequestBody MovimentacaoRequest request) {
        return ProdutoResponse.de(estoqueService.saida(produtoId, request.quantidade()));
    }

    @Operation(tags = TAG_RESERVAS, summary = "Reservar um produto",
            description = "Separa unidades disponíveis do produto para um pedido: disponível diminui e reservada aumenta. "
                    + "Para vários produtos do mesmo pedido de uma vez, use *Reservar vários produtos de um pedido*.")
    @PostMapping("/{produtoId}/reservas")
    public ReservaResponse reservar(@PathVariable UUID produtoId, @Valid @RequestBody CriarReservaRequest request) {
        return ReservaResponse.de(estoqueService.reservar(produtoId, request.pedidoId(), request.quantidade()));
    }

    @Operation(tags = TAG_RESERVAS, summary = "Listar reservas do produto",
            description = "Todas as reservas do produto, da mais antiga para a mais recente.")
    @GetMapping("/{produtoId}/reservas")
    public List<ReservaResponse> listarReservas(@PathVariable UUID produtoId,
                                                @RequestParam(required = false) StatusReserva status) {
        return estoqueService.listarReservas(produtoId, status).stream().map(ReservaResponse::de).toList();
    }

    @Operation(tags = TAG_BLOQUEIOS, summary = "Bloquear unidades",
            description = "Torna unidades disponíveis indisponíveis para uso (ex.: avaria): disponível diminui e bloqueada aumenta.")
    @PostMapping("/{produtoId}/bloqueios")
    public BloqueioResponse bloquear(@PathVariable UUID produtoId, @Valid @RequestBody CriarBloqueioRequest request) {
        return BloqueioResponse.de(
                estoqueService.bloquear(produtoId, request.pedidoId(), request.motivo(), request.quantidade()));
    }

    @Operation(tags = TAG_BLOQUEIOS, summary = "Listar bloqueios do produto",
            description = "Todos os bloqueios do produto, do mais antigo para o mais recente.")
    @GetMapping("/{produtoId}/bloqueios")
    public List<BloqueioResponse> listarBloqueios(@PathVariable UUID produtoId,
                                                  @RequestParam(required = false) StatusBloqueio status) {
        return estoqueService.listarBloqueios(produtoId, status).stream().map(BloqueioResponse::de).toList();
    }
}
