package com.example.controledeestoque.controller;

import com.example.controledeestoque.controller.dto.Responses.BloqueioResponse;
import com.example.controledeestoque.service.EstoqueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import static com.example.controledeestoque.config.OpenApiConfig.TAG_BLOQUEIOS;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = TAG_BLOQUEIOS)
@RestController
@RequestMapping("/api/bloqueios")
@RequiredArgsConstructor
public class BloqueioController {

    private final EstoqueService estoqueService;

    @Operation(summary = "Consultar bloqueio", description = "Dados e status atual do bloqueio.")
    @GetMapping("/{bloqueioId}")
    public BloqueioResponse consultar(@PathVariable UUID bloqueioId) {
        return BloqueioResponse.de(estoqueService.consultarBloqueio(bloqueioId));
    }

    @Operation(summary = "Liberar bloqueio", description = "Encerra o bloqueio ATIVO: as unidades voltam para o disponível.")
    @PostMapping("/{bloqueioId}/liberacao")
    public BloqueioResponse liberar(@PathVariable UUID bloqueioId) {
        return BloqueioResponse.de(estoqueService.liberarBloqueio(bloqueioId));
    }
}
