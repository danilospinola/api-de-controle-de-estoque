package com.example.controledeestoque.controller;

import com.example.controledeestoque.controller.dto.Requests.ReservarPedidoRequest;
import com.example.controledeestoque.controller.dto.Responses.ReservaResponse;
import com.example.controledeestoque.domain.StatusReserva;
import com.example.controledeestoque.service.EstoqueService;
import com.example.controledeestoque.service.EstoqueService.ItemReserva;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import static com.example.controledeestoque.config.OpenApiConfig.TAG_RESERVAS;
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

/**
 * Reservas vistas a partir do pedido: um pedido com vários produtos é reservado em uma única
 * chamada, tudo ou nada; cada item informa o SKU do produto. A rota por produto
 * ({@code /api/produtos/{sku}/reservas}) continua disponível para reservas avulsas.
 */
@Tag(name = TAG_RESERVAS)
@RestController
@RequestMapping("/api/pedidos")
@RequiredArgsConstructor
public class PedidoController {

    private final EstoqueService estoqueService;

    @Operation(summary = "Reservar vários produtos de um pedido",
            description = "Reserva todos os itens de uma vez, tudo ou nada: se qualquer item falhar (produto inexistente "
                    + "ou estoque insuficiente), nenhum é reservado e a mensagem diz qual SKU falhou. "
                    + "Cada SKU aparece uma única vez na lista; até 100 itens.")
    @PostMapping("/{pedidoId}/reservas")
    public List<ReservaResponse> reservar(@PathVariable String pedidoId,
                                          @Valid @RequestBody ReservarPedidoRequest request) {
        List<ItemReserva> itens = request.itens().stream()
                .map(item -> new ItemReserva(item.sku(), item.quantidade()))
                .toList();
        return estoqueService.reservarPedido(pedidoId, itens).stream().map(ReservaResponse::de).toList();
    }

    @Operation(summary = "Listar reservas do pedido", description = "Todas as reservas do pedido, de qualquer produto.")
    @GetMapping("/{pedidoId}/reservas")
    public List<ReservaResponse> listar(@PathVariable String pedidoId,
                                        @RequestParam(required = false) StatusReserva status) {
        return estoqueService.listarReservasDoPedido(pedidoId.strip(), status).stream()
                .map(ReservaResponse::de).toList();
    }
}
