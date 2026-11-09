package com.example.controledeestoque.controller;

import com.example.controledeestoque.controller.dto.Requests.CriarBloqueioRequest;
import com.example.controledeestoque.controller.dto.Requests.CriarProdutoRequest;
import com.example.controledeestoque.controller.dto.Requests.CriarReservaRequest;
import com.example.controledeestoque.controller.dto.Requests.MovimentacaoRequest;
import com.example.controledeestoque.controller.dto.Responses.BloqueioResponse;
import com.example.controledeestoque.controller.dto.Responses.ProdutoResponse;
import com.example.controledeestoque.controller.dto.Responses.ReservaResponse;
import com.example.controledeestoque.domain.Bloqueio;
import com.example.controledeestoque.domain.Produto;
import com.example.controledeestoque.domain.Reserva;
import com.example.controledeestoque.domain.StatusBloqueio;
import com.example.controledeestoque.domain.StatusReserva;
import com.example.controledeestoque.service.EstoqueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Tag(name = "Produtos", description = "Saldo de estoque, entradas, saídas, reservas e bloqueios")
@RestController
@RequestMapping("/api/produtos")
@RequiredArgsConstructor
public class ProdutoController {

    private final EstoqueService estoqueService;

    @Operation(summary = "Cadastra um produto com nome e saldo inicial disponível")
    @PostMapping
    public ResponseEntity<ProdutoResponse> criar(@Valid @RequestBody CriarProdutoRequest request) {
        Produto produto = estoqueService.criarProduto(request.nome(), request.quantidadeInicialOuZero());
        return ResponseEntity.created(URI.create("/api/produtos/" + produto.getId()))
                .body(ProdutoResponse.de(produto));
    }

    @Operation(summary = "Consulta a situação atual do estoque do produto")
    @GetMapping("/{produtoId}")
    public ProdutoResponse consultar(@PathVariable UUID produtoId) {
        return ProdutoResponse.de(estoqueService.consultarProduto(produtoId));
    }

    @Operation(summary = "Entrada: adiciona unidades ao estoque (total e disponível)")
    @PostMapping("/{produtoId}/entradas")
    public ProdutoResponse entrada(@PathVariable UUID produtoId, @Valid @RequestBody MovimentacaoRequest request) {
        return ProdutoResponse.de(estoqueService.entrada(produtoId, request.quantidade()));
    }

    @Operation(summary = "Saída: retira unidades do saldo disponível")
    @PostMapping("/{produtoId}/saidas")
    public ProdutoResponse saida(@PathVariable UUID produtoId, @Valid @RequestBody MovimentacaoRequest request) {
        return ProdutoResponse.de(estoqueService.saida(produtoId, request.quantidade()));
    }

    @Operation(summary = "Reserva unidades disponíveis para um pedido")
    @PostMapping("/{produtoId}/reservas")
    public ResponseEntity<ReservaResponse> reservar(@PathVariable UUID produtoId,
                                                    @Valid @RequestBody CriarReservaRequest request) {
        Reserva reserva = estoqueService.reservar(produtoId, request.pedidoId(), request.quantidade());
        return ResponseEntity.created(URI.create("/api/reservas/" + reserva.getId()))
                .body(ReservaResponse.de(reserva));
    }

    @Operation(summary = "Lista as reservas do produto (filtro opcional por status)")
    @GetMapping("/{produtoId}/reservas")
    public List<ReservaResponse> listarReservas(@PathVariable UUID produtoId,
                                                @RequestParam(required = false) StatusReserva status) {
        return estoqueService.listarReservas(produtoId, status).stream().map(ReservaResponse::de).toList();
    }

    @Operation(summary = "Bloqueia unidades disponíveis, tornando-as indisponíveis para uso")
    @PostMapping("/{produtoId}/bloqueios")
    public ResponseEntity<BloqueioResponse> bloquear(@PathVariable UUID produtoId,
                                                     @Valid @RequestBody CriarBloqueioRequest request) {
        Bloqueio bloqueio = estoqueService.bloquear(
                produtoId, request.pedidoId(), request.motivo(), request.quantidade());
        return ResponseEntity.created(URI.create("/api/bloqueios/" + bloqueio.getId()))
                .body(BloqueioResponse.de(bloqueio));
    }

    @Operation(summary = "Lista os bloqueios do produto (filtro opcional por status)")
    @GetMapping("/{produtoId}/bloqueios")
    public List<BloqueioResponse> listarBloqueios(@PathVariable UUID produtoId,
                                                  @RequestParam(required = false) StatusBloqueio status) {
        return estoqueService.listarBloqueios(produtoId, status).stream().map(BloqueioResponse::de).toList();
    }
}
