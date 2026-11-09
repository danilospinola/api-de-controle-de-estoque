package com.example.controledeestoque.config;

import com.example.controledeestoque.exception.EstoqueInsuficienteException;
import com.example.controledeestoque.exception.SkuDuplicadoException;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Documentação OpenAPI / Swagger UI organizada pelo fluxo de uso.
 * <ul>
 *   <li>Seções numeradas (1. Produtos → 4. Bloqueios) com descrição e um roteiro de teste na página inicial.</li>
 *   <li>Respostas de erro (400, 404, 409, 422, 500) adicionadas automaticamente a cada operação conforme o que
 *       ela pode devolver, com exemplos reais em português; o 200 aparece como "Sucesso".</li>
 *   <li>Servidor relativo ("/"): o "Try it out" chama a API na mesma origem em que o Swagger foi aberto. Sem isso,
 *       atrás do CloudFront o springdoc anunciaria o endereço interno da EC2, bloqueado pelo security group.</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    public static final String TAG_PRODUTOS = "1. Produtos";
    public static final String TAG_MOVIMENTACOES = "2. Movimentações";
    public static final String TAG_RESERVAS = "3. Reservas";
    public static final String TAG_BLOQUEIOS = "4. Bloqueios";

    private static final String PROBLEMA = "Problema";
    private static final String TIPO_PROBLEMA = "application/problem+json";
    /** Parâmetros que identificam um recurso: se ele não existir, a operação responde 404. */
    private static final Set<String> IDS_DE_RECURSO = Set.of("sku", "reservaId", "bloqueioId");
    private static final String SKU_EXEMPLO = "TEC-LOGI-K120";

    private static final Map<String, String> DESCRICAO_PARAMETROS = Map.of(
            "sku", "SKU informado no cadastro do produto (maiúsculas e minúsculas são equivalentes)",
            "reservaId", "Identificador da reserva (o `id` devolvido ao reservar)",
            "bloqueioId", "Identificador do bloqueio (o `id` devolvido ao bloquear)",
            "pedidoId", "Identificador do pedido no sistema de origem, por exemplo PED-2026-0001",
            "status", "Filtro opcional por status");

    private static final String DESCRICAO = """
            Controle de estoque com saldos **total, disponível, reservado e bloqueado** por produto, sempre com \
            `total = disponível + reservada + bloqueada`. Cada produto tem um **SKU** único (ex.: `TEC-LOGI-K120`), \
            que o identifica em todas as rotas (`/api/produtos/{sku}/...`); maiúsculas e minúsculas são equivalentes. \
            Reservas e bloqueios são identificados pelo `id` devolvido ao criá-los.

            ### Como testar em 1 minuto
            1. Na seção **Produtos**, use **Cadastrar produto** com um SKU novo (ex.: `TEC-LOGI-K120`) e clique em *Execute*.
            2. Na seção **Reservas**, use **Reservar um produto** informando esse SKU no campo `sku` (ou **Reservar vários \
            produtos de um pedido**, com o SKU de cada item) e copie o `id` da reserva.
            3. Volte a **Produtos** e use **Consultar saldo do produto** com o mesmo SKU para ver o efeito no saldo.
            4. Em **Reservas**, use **Liberar reserva** ou **Efetivar reserva** com o `id` da reserva.

            ### Respostas
            - **200 Sucesso**: sempre com o recurso no corpo, inclusive nos cadastros (o `id` da reserva ou do bloqueio \
            criado vem no corpo).
            - **400** requisição inválida: o corpo diz qual campo corrigir.
            - **404** recurso não encontrado (SKU, reserva ou bloqueio inexistente).
            - **409** SKU já cadastrado (use a entrada de estoque desse SKU) ou conflito de concorrência (o estoque \
            mudou ao mesmo tempo; repita a requisição).
            - **422** regra de negócio: estoque insuficiente ou mudança de status não permitida.
            - **500** erro inesperado: informe o `identificador` da resposta.

            Os erros seguem a RFC 9457 (`application/problem+json`) e são sempre em português.
            """;

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("API de Controle de Estoque")
                        .version("v1")
                        .description(DESCRICAO))
                .externalDocs(new ExternalDocumentation()
                        .description("Código-fonte, decisões e limitações (README)")
                        .url("https://github.com/danilospinola/api-de-controle-de-estoque"))
                .servers(List.of(new Server().url("/").description("Mesma origem desta página")))
                .tags(List.of(
                        new Tag().name(TAG_PRODUTOS).description("Cadastro e consulta do saldo de cada produto"),
                        new Tag().name(TAG_MOVIMENTACOES).description("Entradas e saídas de unidades do estoque"),
                        new Tag().name(TAG_RESERVAS).description(
                                "Separar unidades para pedidos: por produto ou vários produtos de um pedido (tudo ou nada); "
                                        + "liberar devolve ao disponível e efetivar dá baixa no estoque"),
                        new Tag().name(TAG_BLOQUEIOS).description(
                                "Tornar unidades indisponíveis (ex.: avaria) e liberá-las depois")))
                .components(new Components().addSchemas(PROBLEMA, schemaProblema()));
    }

    /** Acrescenta a cada operação os erros que ela pode devolver e descreve os parâmetros. */
    @Bean
    OperationCustomizer respostasEParametrosPadrao() {
        return (operation, handlerMethod) -> {
            ApiResponses respostas = operation.getResponses();
            ApiResponse sucesso = respostas.get("200");
            if (sucesso != null) {
                sucesso.setDescription("Sucesso");
            }
            boolean escrita = handlerMethod.hasMethodAnnotation(PostMapping.class);
            List<String> parametros = parametrosDeEntrada(handlerMethod);
            boolean recursoIdentificado = parametros.stream().anyMatch(IDS_DE_RECURSO::contains);
            boolean listaDeItens = parametros.contains("pedidoId") && escrita;
            boolean cadastro = escrita && parametros.isEmpty();

            adicionar(respostas, "400", "Requisição inválida: campo ausente, tipo ou formato errado", exemplo400());
            if (recursoIdentificado || listaDeItens) {
                adicionar(respostas, "404", "Recurso não encontrado", exemplo404());
            }
            if (cadastro) {
                adicionar(respostas, "409", "SKU já cadastrado: use a entrada de estoque do produto existente",
                        exemplo409Sku());
            }
            if (escrita && (recursoIdentificado || listaDeItens)) {
                adicionar(respostas, "409", "Conflito de concorrência: repita a requisição", exemplo409());
                adicionar(respostas, "422", "Regra de negócio: estoque insuficiente ou status que não permite a operação",
                        exemplo422());
            }
            adicionar(respostas, "500", "Erro inesperado", exemplo500());

            if (operation.getParameters() != null) {
                for (Parameter parametro : operation.getParameters()) {
                    if (parametro.getDescription() == null) {
                        parametro.setDescription(DESCRICAO_PARAMETROS.get(parametro.getName()));
                    }
                    if ("sku".equals(parametro.getName()) && parametro.getExample() == null) {
                        parametro.setExample(SKU_EXEMPLO);
                    }
                }
            }
            return operation;
        };
    }

    /** Nomes dos parâmetros de caminho e de consulta (o corpo não conta). */
    private static List<String> parametrosDeEntrada(HandlerMethod handlerMethod) {
        return Arrays.stream(handlerMethod.getMethodParameters())
                .filter(p -> p.hasParameterAnnotation(PathVariable.class) || p.hasParameterAnnotation(RequestParam.class))
                .map(MethodParameter::getParameterName)
                .toList();
    }

    private static void adicionar(ApiResponses respostas, String codigo, String descricao, Map<String, Object> exemplo) {
        if (respostas.containsKey(codigo)) {
            return;
        }
        Schema<?> referencia = new Schema<>().$ref("#/components/schemas/" + PROBLEMA);
        respostas.addApiResponse(codigo, new ApiResponse()
                .description(descricao)
                .content(new Content().addMediaType(TIPO_PROBLEMA, new MediaType().schema(referencia).example(exemplo))));
    }

    private static Schema<?> schemaProblema() {
        // Mapa "campo → mensagem". additionalProperties como Boolean: o springdoc copia a definição
        // internamente e não consegue reconstruir um Schema nesse campo (geraria aviso a cada carga).
        Schema<Object> erros = new ObjectSchema()
                .description("Erros por campo (só no 400 de validação): nome do campo → mensagem")
                .example(Map.of("quantidade", "deve ser maior que zero"));
        erros.setAdditionalProperties(Boolean.TRUE);
        return new ObjectSchema()
                .description("Erro no formato RFC 9457, em português")
                .addProperty("title", new StringSchema().description("Resumo do erro"))
                .addProperty("status", new IntegerSchema().description("Código HTTP"))
                .addProperty("detail", new StringSchema().description("O que aconteceu e, quando possível, como corrigir"))
                .addProperty("instance", new StringSchema().description("Endereço chamado"))
                .addProperty("erros", erros)
                .addProperty("identificador", new StringSchema().description("Só no 500: código para localizar o erro no log"));
    }

    private static Map<String, Object> exemplo(int status, String titulo, String detalhe) {
        Map<String, Object> exemplo = new LinkedHashMap<>();
        exemplo.put("title", titulo);
        exemplo.put("status", status);
        exemplo.put("detail", detalhe);
        exemplo.put("instance", "/api/produtos/" + SKU_EXEMPLO);
        return exemplo;
    }

    private static Map<String, Object> exemplo400() {
        Map<String, Object> exemplo = exemplo(400, "Requisição inválida", "Um ou mais campos são inválidos.");
        exemplo.put("erros", Map.of("quantidade", "deve ser maior que zero"));
        return exemplo;
    }

    private static Map<String, Object> exemplo404() {
        return exemplo(404, "Recurso não encontrado", "Produto com sku " + SKU_EXEMPLO + " não existe.");
    }

    private static Map<String, Object> exemplo409() {
        return exemplo(409, "Conflito de concorrência", "O estoque foi alterado concorrentemente. Tente novamente.");
    }

    private static Map<String, Object> exemplo409Sku() {
        Map<String, Object> exemplo = exemplo(409, "SKU já cadastrado",
                new SkuDuplicadoException(SKU_EXEMPLO).getMessage());
        exemplo.put("instance", "/api/produtos");
        return exemplo;
    }

    private static Map<String, Object> exemplo422() {
        return exemplo(422, "Estoque insuficiente",
                new EstoqueInsuficienteException(SKU_EXEMPLO, 5, 4).getMessage());
    }

    private static Map<String, Object> exemplo500() {
        Map<String, Object> exemplo = exemplo(500, "Erro interno",
                "Ocorreu um erro inesperado. Tente novamente; se persistir, informe o identificador "
                        + "9b2f1c3e-7a64-4d1b-8e0f-2c5d6a7b8c9d.");
        exemplo.put("identificador", "9b2f1c3e-7a64-4d1b-8e0f-2c5d6a7b8c9d");
        return exemplo;
    }
}
