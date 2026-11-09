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

/** Contrato HTTP: rotas (produto identificado pelo SKU), códigos de status e formato de erro (ProblemDetail). */
@SpringBootTest
@AutoConfigureMockMvc
class EstoqueApiTest {

    private static final String NOME = "Monitor 27 polegadas";

    @Autowired
    private MockMvc mvc;

    @Test
    void cadastraEConsultaProdutoPeloSku() throws Exception {
        String sku = criarProduto(10);

        mvc.perform(get("/api/produtos/{sku}", sku))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value(sku))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.nome").value(NOME))
                .andExpect(jsonPath("$.quantidadeTotal").value(10))
                .andExpect(jsonPath("$.quantidadeDisponivel").value(10))
                .andExpect(jsonPath("$.quantidadeReservada").value(0))
                .andExpect(jsonPath("$.quantidadeBloqueada").value(0));

        // Maiúsculas e minúsculas são equivalentes também no endereço.
        mvc.perform(get("/api/produtos/{sku}", sku.toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value(sku));
    }

    @Test
    void entradaESaida() throws Exception {
        String sku = criarProduto(10);

        postJson("/api/produtos/" + sku + "/entradas", "{\"quantidade\": 5}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantidadeTotal").value(15));

        postJson("/api/produtos/" + sku.toLowerCase() + "/saidas", "{\"quantidade\": 7}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantidadeTotal").value(8))
                .andExpect(jsonPath("$.quantidadeDisponivel").value(8));
    }

    @Test
    void saidaAcimaDoDisponivelRetorna422() throws Exception {
        String sku = criarProduto(3);

        postJson("/api/produtos/" + sku + "/saidas", "{\"quantidade\": 4}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Estoque insuficiente"))
                .andExpect(jsonPath("$.detail", containsString(sku)))
                .andExpect(jsonPath("$.detail", containsString("disponível=3")));
    }

    @Test
    void cicloDeVidaDaReservaPelaApi() throws Exception {
        String sku = criarProduto(10);

        String reserva = postJson("/api/produtos/" + sku + "/reservas",
                "{\"pedidoId\": \"PED-1\", \"quantidade\": 4}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("ATIVA"))
                .andExpect(jsonPath("$.sku").value(sku))
                .andExpect(jsonPath("$.produtoId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String reservaId = JsonPath.read(reserva, "$.id");

        mvc.perform(get("/api/reservas/{id}", reservaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value(sku))
                .andExpect(jsonPath("$.pedidoId").value("PED-1"))
                .andExpect(jsonPath("$.quantidade").value(4));

        mvc.perform(get("/api/produtos/{sku}/reservas", sku).param("status", "ATIVA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value(sku));

        mvc.perform(post("/api/reservas/{id}/efetivacao", reservaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFETIVADA"))
                .andExpect(jsonPath("$.sku").value(sku));

        mvc.perform(post("/api/reservas/{id}/liberacao", reservaId))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Operação inválida"));

        mvc.perform(get("/api/produtos/{sku}", sku))
                .andExpect(jsonPath("$.quantidadeTotal").value(6))
                .andExpect(jsonPath("$.quantidadeReservada").value(0));
    }

    @Test
    void cicloDeVidaDoBloqueioPelaApi() throws Exception {
        String sku = criarProduto(10);

        String bloqueio = postJson("/api/produtos/" + sku + "/bloqueios",
                "{\"pedidoId\": \"PED-9\", \"motivo\": \"avaria\", \"quantidade\": 3}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ATIVO"))
                .andExpect(jsonPath("$.motivo").value("avaria"))
                .andExpect(jsonPath("$.sku").value(sku))
                .andReturn().getResponse().getContentAsString();
        String bloqueioId = JsonPath.read(bloqueio, "$.id");

        mvc.perform(get("/api/produtos/{sku}", sku))
                .andExpect(jsonPath("$.quantidadeDisponivel").value(7))
                .andExpect(jsonPath("$.quantidadeBloqueada").value(3));

        mvc.perform(get("/api/produtos/{sku}/bloqueios", sku).param("status", "ATIVO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku").value(sku));

        mvc.perform(post("/api/bloqueios/{id}/liberacao", bloqueioId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIBERADO"));

        mvc.perform(get("/api/bloqueios/{id}", bloqueioId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIBERADO"))
                .andExpect(jsonPath("$.sku").value(sku));
    }

    @Test
    void payloadInvalidoRetorna400ComErrosPorCampo() throws Exception {
        String sku = criarProduto(10);

        postJson("/api/produtos/" + sku + "/reservas", "{\"pedidoId\": \" \", \"quantidade\": 0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Requisição inválida"))
                .andExpect(jsonPath("$.erros.pedidoId").value("é obrigatório"))
                .andExpect(jsonPath("$.erros.quantidade").value("deve ser maior que zero"));
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
        String sku = skuUnico();
        postJson("/api/produtos",
                "{\"sku\": \"" + sku + "\", \"nome\": \"" + NOME + "\", \"quantidadeInicial\": " + Integer.MAX_VALUE + "}")
                .andExpect(status().isOk());

        postJson("/api/produtos/" + sku + "/entradas", "{\"quantidade\": 1}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Operação inválida"))
                .andExpect(jsonPath("$.detail", containsString("limite")));
    }

    @Test
    void contratoOpenApiUsaServidorRelativo() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servers[0].url").value("/"))
                .andExpect(jsonPath("$.info.title").value("API de Controle de Estoque"));
    }

    @Test
    void swaggerOrganizadoPorFluxoComRotasPorSku() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags[*].name",
                        org.hamcrest.Matchers.contains("1. Produtos", "2. Movimentações", "3. Reservas", "4. Bloqueios")))
                .andExpect(jsonPath("$.paths['/api/produtos'].post.tags[0]").value("1. Produtos"))
                .andExpect(jsonPath("$.paths['/api/produtos/{sku}'].get.tags[0]").value("1. Produtos"))
                .andExpect(jsonPath("$.paths['/api/produtos/{sku}'].get.parameters[0].name").value("sku"))
                .andExpect(jsonPath("$.paths['/api/produtos/{sku}'].get.parameters[0].example").value("TEC-LOGI-K120"))
                .andExpect(jsonPath("$.paths['/api/produtos/{sku}'].get.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/produtos/{sku}/saidas'].post.tags[0]").value("2. Movimentações"))
                .andExpect(jsonPath("$.paths['/api/produtos/{sku}/reservas'].post.tags[0]").value("3. Reservas"))
                .andExpect(jsonPath("$.paths['/api/pedidos/{pedidoId}/reservas'].post.tags[0]").value("3. Reservas"))
                .andExpect(jsonPath("$.paths['/api/reservas/{reservaId}/liberacao'].post.tags[0]").value("3. Reservas"))
                .andExpect(jsonPath("$.paths['/api/produtos/{sku}/bloqueios'].post.tags[0]").value("4. Bloqueios"))
                .andExpect(jsonPath("$.paths['/api/produtos/{sku}/saidas'].post.responses['200'].description")
                        .value("Sucesso"))
                .andExpect(jsonPath("$.paths['/api/produtos/{sku}/saidas'].post.responses['422']").exists())
                .andExpect(jsonPath("$.paths['/api/produtos/{sku}/saidas'].post.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/pedidos/{pedidoId}/reservas'].post.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/produtos'].post.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/api/produtos'].post.responses['422']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/produtos'].post.responses['409']").exists())
                // Nenhuma rota identifica o produto pelo UUID, e a busca separada por SKU deixou de existir.
                .andExpect(jsonPath("$.paths['/api/produtos/{produtoId}']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/produtos/busca']").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.ProdutoResponse.properties.id").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.ItemReservaRequest.properties.sku").exists());
    }

    @Test
    void raizRedirecionaParaOSwagger() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("swagger-ui")));
    }

    @Test
    void skuJaCadastradoResponde409ApontandoAEntradaDeEstoque() throws Exception {
        String sku = skuUnico();
        postJson("/api/produtos",
                "{\"sku\": \"" + sku.toLowerCase() + "\", \"nome\": \"Teclado\", \"marca\": \"Logitech\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value(sku.toUpperCase()))
                .andExpect(jsonPath("$.marca").value("Logitech"));

        postJson("/api/produtos", "{\"sku\": \" " + sku.toUpperCase() + " \", \"nome\": \"Outro nome\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("SKU já cadastrado"))
                .andExpect(jsonPath("$.detail", containsString("/api/produtos/" + sku.toUpperCase() + "/entradas")));

        mvc.perform(get("/api/produtos/{sku}", sku))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Teclado"));
    }

    @Test
    void skuAusenteOuForaDoFormatoRetorna400EmPortugues() throws Exception {
        postJson("/api/produtos", "{\"sku\": \"TEC 01\", \"nome\": \"Teclado\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros.sku", containsString("letras sem acento")));
        postJson("/api/produtos", "{\"nome\": \"Teclado\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros.sku").value("é obrigatório"));
    }

    @Test
    void reservaDeVariosProdutosPorPedidoETudoOuNada() throws Exception {
        String teclado = criarProduto(5);
        String mouse = criarProduto(1);
        String itens = "{\"itens\": [{\"sku\": \"%s\", \"quantidade\": 2}, {\"sku\": \"%s\", \"quantidade\": %d}]}";

        postJson("/api/pedidos/PED-API-1/reservas", itens.formatted(teclado, mouse, 3))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Estoque insuficiente"))
                .andExpect(jsonPath("$.detail", containsString(mouse)));
        mvc.perform(get("/api/pedidos/{pedidoId}/reservas", "PED-API-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        postJson("/api/pedidos/PED-API-1/reservas", itens.formatted(teclado, mouse.toLowerCase(), 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].sku", org.hamcrest.Matchers.containsInAnyOrder(teclado, mouse)))
                .andExpect(jsonPath("$[0].pedidoId").value("PED-API-1"))
                .andExpect(jsonPath("$[0].status").value("ATIVA"));
        mvc.perform(get("/api/pedidos/{pedidoId}/reservas", "PED-API-1").param("status", "ATIVA"))
                .andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/produtos/{sku}", teclado))
                .andExpect(jsonPath("$.quantidadeReservada").value(2));
    }

    @Test
    void reservaDePedidoValidaOsItensEmPortugues() throws Exception {
        postJson("/api/pedidos/PED-API-2/reservas", "{\"itens\": []}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros.itens").value("deve ter ao menos um item"));
        postJson("/api/pedidos/PED-API-2/reservas", "{\"itens\": [{\"quantidade\": 0}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros['itens[0].sku']").value("é obrigatório"))
                .andExpect(jsonPath("$.erros['itens[0].quantidade']").value("deve ser maior que zero"));
        postJson("/api/pedidos/PED-API-2/reservas", "{\"itens\": [{\"sku\": \"NAO-EXISTE-PED\", \"quantidade\": 1}]}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Produto com sku NAO-EXISTE-PED não existe."));
    }

    @Test
    void uuidMalformadoDeReservaRetorna400() throws Exception {
        mvc.perform(get("/api/reservas/{id}", "nao-e-um-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Requisição inválida"))
                .andExpect(jsonPath("$.detail", containsString("'reservaId'")))
                .andExpect(jsonPath("$.detail", containsString("UUID")));
    }

    @Test
    void errosDeFormatoSaoEmPortuguesEApontamOQueCorrigir() throws Exception {
        String sku = criarProduto(5);

        mvc.perform(get("/api/produtos/{sku}/reservas", sku).param("status", "QUALQUER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("ATIVA, LIBERADA, EFETIVADA")));

        postJson("/api/produtos", "{\"nome\":")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Requisição inválida"))
                .andExpect(jsonPath("$.detail", containsString("JSON válido")));

        postJson("/api/produtos/" + sku + "/entradas", "{\"quantidade\": \"dez\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("'quantidade'")))
                .andExpect(jsonPath("$.detail", containsString("número inteiro")));

        mvc.perform(get("/api/produtos"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.title").value("Método não permitido"))
                .andExpect(jsonPath("$.detail", containsString("POST")));

        mvc.perform(get("/api/endereco-inexistente"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso não encontrado"))
                .andExpect(jsonPath("$.detail", containsString("não existe")));
    }

    @Test
    void recursoInexistenteRetorna404() throws Exception {
        mvc.perform(get("/api/produtos/{sku}", "nao-existe-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso não encontrado"))
                .andExpect(jsonPath("$.detail").value("Produto com sku NAO-EXISTE-404 não existe."));
        postJson("/api/produtos/NAO-EXISTE-404/reservas", "{\"pedidoId\": \"PED-1\", \"quantidade\": 1}")
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/reservas/{id}/liberacao", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void jsonEstritoRecusaDecimalTextoECampoRepetidoSemAlterarOSaldo() throws Exception {
        String sku = criarProduto(10);
        String entradas = "/api/produtos/" + sku + "/entradas";

        // Antes da correção, 2.7 virava 2, 1e1 virava 10 e "3" virava 3, todos com 200.
        for (String quantidade : new String[]{"2.7", "2.0", "1e1", "\"3\""}) {
            postJson(entradas, "{\"quantidade\": " + quantidade + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail", containsString("'quantidade'")))
                    .andExpect(jsonPath("$.detail", containsString("número inteiro")));
        }
        postJson("/api/produtos/" + sku + "/saidas", "{\"quantidade\": 1, \"quantidade\": 9}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("O campo 'quantidade' aparece mais de uma vez no corpo da requisição."));
        postJson(entradas, "[1]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("objeto JSON")));
        postJson(entradas, "{\"quantidade\": 1} {\"quantidade\": 2}")
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/produtos/{sku}", sku))
                .andExpect(jsonPath("$.quantidadeTotal").value(10))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void campoDesconhecidoRespondeComOsCamposAceitosENaoCadastra() throws Exception {
        String sku = skuUnico();

        // Erro de digitação em campo opcional: antes, o produto era criado com 0 unidades.
        postJson("/api/produtos", "{\"sku\": \"" + sku + "\", \"nome\": \"Teclado\", \"quantidadeIncial\": 100}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("O campo 'quantidadeIncial' não existe nesta operação. "
                        + "Campos aceitos: marca, nome, quantidadeInicial, sku."));
        mvc.perform(get("/api/produtos/{sku}", sku)).andExpect(status().isNotFound());

        postJson("/api/pedidos/PED-API-3/reservas", "{\"itens\": [{\"skuu\": \"" + sku + "\", \"quantidade\": 1}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("'itens[0].skuu' não existe")));
    }

    @Test
    void caracteresDeControleSaoRecusadosComErroPorCampo() throws Exception {
        String sku = criarProduto(10);

        postJson("/api/produtos", "{\"sku\": \"" + skuUnico() + "\", \"nome\": \"Tec\\u0000lado\", \"marca\": \"Logi\\ntech\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros.nome", containsString("caracteres de controle")))
                .andExpect(jsonPath("$.erros.marca", containsString("caracteres de controle")));
        postJson("/api/produtos/" + sku + "/reservas", "{\"pedidoId\": \"PED-1\\nFALSO\", \"quantidade\": 1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros.pedidoId", containsString("quebra de linha")));
        postJson("/api/produtos/" + sku + "/bloqueios", "{\"pedidoId\": \"PED-2\", \"motivo\": \"ava\\tria\", \"quantidade\": 1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros.motivo", containsString("caracteres de controle")));

        // Espaços e tabulação nas pontas continuam aceitos: o domínio os remove.
        postJson("/api/produtos", "{\"sku\": \"" + skuUnico() + "\", \"nome\": \"  Teclado\\t\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Teclado"));
        mvc.perform(get("/api/produtos/{sku}", sku))
                .andExpect(jsonPath("$.quantidadeDisponivel").value(10));
    }

    @Test
    void pedidoIdDoEnderecoEValidadoComoNoCorpo() throws Exception {
        String sku = criarProduto(5);
        String item = "{\"itens\": [{\"sku\": \"" + sku + "\", \"quantidade\": 1}]}";
        String longo = "P".repeat(101);

        postJson("/api/pedidos/" + longo + "/reservas", item)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Requisição inválida"))
                .andExpect(jsonPath("$.erros.pedidoId").value("deve ter no máximo 100 caracteres"));
        mvc.perform(post("/api/pedidos/{pedidoId}/reservas", "PED\nFALSO")
                        .contentType(MediaType.APPLICATION_JSON).content(item))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros.pedidoId", containsString("caracteres de controle")));
        mvc.perform(get("/api/pedidos/{pedidoId}/reservas", longo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros.pedidoId").exists());
        mvc.perform(get("/api/produtos/{sku}", sku))
                .andExpect(jsonPath("$.quantidadeReservada").value(0));
    }

    @Test
    void corpoAcimaDoLimiteResponde413() throws Exception {
        postJson("/api/produtos", "{\"sku\": \"" + skuUnico() + "\", \"nome\": \"" + "x".repeat(100_000) + "\"}")
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.title").value("Requisição grande demais"))
                .andExpect(jsonPath("$.detail", containsString("tamanho máximo")));
    }

    /** Cadastra um produto com SKU único e devolve esse SKU, que identifica o produto nas demais rotas. */
    private String criarProduto(int quantidadeInicial) throws Exception {
        String corpo = postJson("/api/produtos",
                "{\"sku\": \"" + skuUnico() + "\", \"nome\": \"" + NOME + "\", \"quantidadeInicial\": " + quantidadeInicial + "}")
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.nome").value(NOME))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.sku");
    }

    /** SKU único por produto de teste: o banco é compartilhado entre os testes da classe. */
    private static String skuUnico() {
        return "API-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String url, String json) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
