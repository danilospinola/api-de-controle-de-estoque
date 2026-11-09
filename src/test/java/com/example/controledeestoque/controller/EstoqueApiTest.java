package com.example.controledeestoque.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrato HTTP: rotas, códigos de status e formato de erro (ProblemDetail). */
@SpringBootTest
@AutoConfigureMockMvc
class EstoqueApiTest {

    private static final String NOME = "Monitor 27 polegadas";

    @Autowired
    private MockMvc mvc;

    @Test
    void cadastraEConsultaProduto() throws Exception {
        String id = criarProduto(10);

        mvc.perform(get("/api/produtos/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value(NOME))
                .andExpect(jsonPath("$.quantidadeTotal").value(10))
                .andExpect(jsonPath("$.quantidadeDisponivel").value(10))
                .andExpect(jsonPath("$.quantidadeReservada").value(0))
                .andExpect(jsonPath("$.quantidadeBloqueada").value(0));
    }

    @Test
    void entradaESaida() throws Exception {
        String id = criarProduto(10);

        postJson("/api/produtos/" + id + "/entradas", "{\"quantidade\": 5}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantidadeTotal").value(15));

        postJson("/api/produtos/" + id + "/saidas", "{\"quantidade\": 7}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantidadeTotal").value(8))
                .andExpect(jsonPath("$.quantidadeDisponivel").value(8));
    }

    @Test
    void saidaAcimaDoDisponivelRetorna422() throws Exception {
        String id = criarProduto(3);

        postJson("/api/produtos/" + id + "/saidas", "{\"quantidade\": 4}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Estoque insuficiente"))
                .andExpect(jsonPath("$.detail", containsString("disponível=3")));
    }

    @Test
    void cicloDeVidaDaReservaPelaApi() throws Exception {
        String produtoId = criarProduto(10);

        String reserva = postJson("/api/produtos/" + produtoId + "/reservas",
                "{\"pedidoId\": \"PED-1\", \"quantidade\": 4}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/reservas/")))
                .andExpect(jsonPath("$.status").value("ATIVA"))
                .andExpect(jsonPath("$.produtoId").value(produtoId))
                .andReturn().getResponse().getContentAsString();
        String reservaId = JsonPath.read(reserva, "$.id");

        mvc.perform(get("/api/reservas/{id}", reservaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pedidoId").value("PED-1"))
                .andExpect(jsonPath("$.quantidade").value(4));

        mvc.perform(get("/api/produtos/{id}/reservas", produtoId).param("status", "ATIVA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mvc.perform(post("/api/reservas/{id}/efetivacao", reservaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFETIVADA"));

        mvc.perform(post("/api/reservas/{id}/liberacao", reservaId))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Operação inválida"));

        mvc.perform(get("/api/produtos/{id}", produtoId))
                .andExpect(jsonPath("$.quantidadeTotal").value(6))
                .andExpect(jsonPath("$.quantidadeReservada").value(0));
    }

    @Test
    void cicloDeVidaDoBloqueioPelaApi() throws Exception {
        String produtoId = criarProduto(10);

        String bloqueio = postJson("/api/produtos/" + produtoId + "/bloqueios",
                "{\"pedidoId\": \"PED-9\", \"motivo\": \"avaria\", \"quantidade\": 3}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ATIVO"))
                .andExpect(jsonPath("$.motivo").value("avaria"))
                .andReturn().getResponse().getContentAsString();
        String bloqueioId = JsonPath.read(bloqueio, "$.id");

        mvc.perform(get("/api/produtos/{id}", produtoId))
                .andExpect(jsonPath("$.quantidadeDisponivel").value(7))
                .andExpect(jsonPath("$.quantidadeBloqueada").value(3));

        mvc.perform(post("/api/bloqueios/{id}/liberacao", bloqueioId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIBERADO"));

        mvc.perform(get("/api/bloqueios/{id}", bloqueioId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIBERADO"));
    }

    @Test
    void payloadInvalidoRetorna400ComErrosPorCampo() throws Exception {
        String produtoId = criarProduto(10);

        postJson("/api/produtos/" + produtoId + "/reservas", "{\"pedidoId\": \" \", \"quantidade\": 0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros.pedidoId").exists())
                .andExpect(jsonPath("$.erros.quantidade").exists());
    }

    @Test
    void cadastroSemNomeValidoRetorna400() throws Exception {
        postJson("/api/produtos", "{\"quantidadeInicial\": 10}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros.nome").exists());
        postJson("/api/produtos", "{\"nome\": \"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros.nome").exists());
        postJson("/api/produtos", "{\"nome\": \"" + "x".repeat(151) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros.nome").exists());
        mvc.perform(post("/api/produtos").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void entradaQueUltrapassariaOLimiteRetorna422() throws Exception {
        String corpo = postJson("/api/produtos",
                "{\"nome\": \"" + NOME + "\", \"quantidadeInicial\": " + Integer.MAX_VALUE + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(corpo, "$.id");

        postJson("/api/produtos/" + id + "/entradas", "{\"quantidade\": 1}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Operação inválida"))
                .andExpect(jsonPath("$.detail", containsString("limite")));
    }

    @Test
    void contratoOpenApiUsaServidorRelativo() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servers[0].url").value("/"))
                .andExpect(jsonPath("$.info.title").value("Controle de Estoque"));
    }

    @Test
    void raizRedirecionaParaOSwagger() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("swagger-ui")));
    }

    @Test
    void uuidMalformadoRetorna400() throws Exception {
        mvc.perform(get("/api/produtos/{id}", "nao-e-um-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recursoInexistenteRetorna404() throws Exception {
        mvc.perform(get("/api/produtos/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso não encontrado"));
        mvc.perform(post("/api/reservas/{id}/liberacao", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    private String criarProduto(int quantidadeInicial) throws Exception {
        String corpo = postJson("/api/produtos",
                "{\"nome\": \"" + NOME + "\", \"quantidadeInicial\": " + quantidadeInicial + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nome").value(NOME))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.id");
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String url, String json) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
