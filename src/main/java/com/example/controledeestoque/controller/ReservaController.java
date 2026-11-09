package com.example.controledeestoque.controller;

import com.example.controledeestoque.controller.dto.Responses.ReservaResponse;
import com.example.controledeestoque.service.EstoqueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import static com.example.controledeestoque.config.OpenApiConfig.TAG_RESERVAS;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = TAG_RESERVAS)
@RestController
@RequestMapping("/api/reservas")
@RequiredArgsConstructor
public class ReservaController {

    private final EstoqueService estoqueService;

    @Operation(summary = "Consultar reserva", description = "Dados e status atual da reserva.")
    @GetMapping("/{reservaId}")
    public ReservaResponse consultar(@PathVariable UUID reservaId) {
        return ReservaResponse.de(estoqueService.consultarReserva(reservaId));
    }

    @Operation(summary = "Liberar reserva", description = "Cancela a reserva ATIVA: as unidades voltam para o disponível.")
    @PostMapping("/{reservaId}/liberacao")
    public ReservaResponse liberar(@PathVariable UUID reservaId) {
        return ReservaResponse.de(estoqueService.liberarReserva(reservaId));
    }

    @Operation(summary = "Efetivar reserva", description = "Consome a reserva ATIVA: as unidades saem do estoque (reservada e total diminuem).")
    @PostMapping("/{reservaId}/efetivacao")
    public ReservaResponse efetivar(@PathVariable UUID reservaId) {
        return ReservaResponse.de(estoqueService.efetivarReserva(reservaId));
    }
}
