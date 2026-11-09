package com.example.controledeestoque.controller;

import com.example.controledeestoque.controller.dto.Responses.ReservaResponse;
import com.example.controledeestoque.service.EstoqueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Reservas")
@RestController
@RequestMapping("/api/reservas")
@RequiredArgsConstructor
public class ReservaController {

    private final EstoqueService estoqueService;

    @Operation(summary = "Consulta uma reserva pelo identificador")
    @GetMapping("/{reservaId}")
    public ReservaResponse consultar(@PathVariable UUID reservaId) {
        return ReservaResponse.de(estoqueService.consultarReserva(reservaId));
    }

    @Operation(summary = "Libera (cancela) a reserva, devolvendo as unidades ao disponível")
    @PostMapping("/{reservaId}/liberacao")
    public ReservaResponse liberar(@PathVariable UUID reservaId) {
        return ReservaResponse.de(estoqueService.liberarReserva(reservaId));
    }

    @Operation(summary = "Efetiva a reserva: as unidades saem do estoque (baixa do total)")
    @PostMapping("/{reservaId}/efetivacao")
    public ReservaResponse efetivar(@PathVariable UUID reservaId) {
        return ReservaResponse.de(estoqueService.efetivarReserva(reservaId));
    }
}
