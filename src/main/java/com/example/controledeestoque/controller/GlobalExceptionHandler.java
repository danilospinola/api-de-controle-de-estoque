package com.example.controledeestoque.controller;

import com.example.controledeestoque.exception.ConflitoConcorrenciaException;
import com.example.controledeestoque.exception.EstoqueInsuficienteException;
import com.example.controledeestoque.exception.RecursoNaoEncontradoException;
import com.example.controledeestoque.exception.RegraNegocioException;
import com.example.controledeestoque.exception.SkuDuplicadoException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.MismatchedInputException;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Traduz exceções em respostas RFC 9457 (application/problem+json), sempre em português e sem
 * alterar os códigos HTTP de erro.
 * <ul>
 *   <li>Regras de negócio e recursos inexistentes têm handlers próprios (422, 404, 409).</li>
 *   <li>Erros do Spring MVC (JSON inválido, tipo errado, método ou endereço inexistente) passam por
 *       {@link #handleExceptionInternal}, que diz o que corrigir sempre que possível.</li>
 *   <li>Qualquer outra exceção vira 500 com uma mensagem genérica e um identificador que também vai
 *       para o log; detalhes internos nunca chegam ao cliente.</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Map<Integer, String> TITULOS = Map.of(
            400, "Requisição inválida",
            404, "Recurso não encontrado",
            405, "Método não permitido",
            406, "Formato de resposta não aceito",
            409, "Conflito",
            413, "Requisição grande demais",
            415, "Tipo de conteúdo não suportado",
            422, "Operação inválida",
            500, "Erro interno",
            503, "Serviço indisponível");

    @ExceptionHandler(RecursoNaoEncontradoException.class)
    public ProblemDetail naoEncontrado(RecursoNaoEncontradoException e) {
        return problema(HttpStatus.NOT_FOUND, "Recurso não encontrado", e.getMessage());
    }

    @ExceptionHandler(EstoqueInsuficienteException.class)
    public ProblemDetail estoqueInsuficiente(EstoqueInsuficienteException e) {
        return problema(HttpStatus.UNPROCESSABLE_CONTENT, "Estoque insuficiente", e.getMessage());
    }

    @ExceptionHandler(RegraNegocioException.class)
    public ProblemDetail regraNegocio(RegraNegocioException e) {
        return problema(HttpStatus.UNPROCESSABLE_CONTENT, "Operação inválida", e.getMessage());
    }

    @ExceptionHandler(SkuDuplicadoException.class)
    public ProblemDetail skuDuplicado(SkuDuplicadoException e) {
        ProblemDetail problema = problema(HttpStatus.CONFLICT, "SKU já cadastrado", e.getMessage());
        if (e.getProdutoExistente() != null) {
            problema.setProperty("produtoExistente", e.getProdutoExistente());
        }
        return problema;
    }

    @ExceptionHandler(ConflitoConcorrenciaException.class)
    public ProblemDetail conflito(ConflitoConcorrenciaException e) {
        return problema(HttpStatus.CONFLICT, "Conflito de concorrência", e.getMessage());
    }

    /** Última linha de defesa: uma CHECK constraint do banco barrou um estado inconsistente. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail integridade(DataIntegrityViolationException e) {
        log.error("Violação de integridade no banco de dados", e);
        return problema(HttpStatus.CONFLICT, "Violação de integridade",
                "A operação violaria a consistência do estoque e foi rejeitada.");
    }

    /** Erro não previsto: resposta genérica com identificador para localizar o stack trace no log. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail erroInesperado(Exception e) {
        String identificador = UUID.randomUUID().toString();
        log.error("Erro inesperado [{}]", identificador, e);
        ProblemDetail problema = problema(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno",
                "Ocorreu um erro inesperado. Tente novamente; se persistir, informe o identificador %s."
                        .formatted(identificador));
        problema.setProperty("identificador", identificador);
        return problema;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> erros = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        erro -> erro.getField(),
                        erro -> String.valueOf(erro.getDefaultMessage()),
                        (primeiro, segundo) -> primeiro));
        ProblemDetail problema = problema(HttpStatus.BAD_REQUEST, "Requisição inválida",
                "Um ou mais campos são inválidos.");
        problema.setProperty("erros", erros);
        return ResponseEntity.badRequest().body(problema);
    }

    /**
     * Todos os demais erros do Spring MVC chegam aqui com título e detalhe em inglês: o corpo é
     * substituído por um equivalente em português. Os cabeçalhos (ex.: {@code Allow} no 405) e o
     * código HTTP são mantidos.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        return super.handleExceptionInternal(ex, traduzir(ex, statusCode), headers, statusCode, request);
    }

    private static ProblemDetail traduzir(Exception ex, HttpStatusCode status) {
        String detalhe;
        if (ex instanceof HttpMessageNotReadableException e) {
            detalhe = detalheCorpoInvalido(e);
        } else if (ex instanceof TypeMismatchException e) {
            String nome = e instanceof MethodArgumentTypeMismatchException m ? m.getName() : e.getPropertyName();
            detalhe = "O parâmetro '%s' recebeu um valor inválido: '%s'. %s"
                    .formatted(nome, e.getValue(), esperado(e.getRequiredType()));
        } else if (ex instanceof MissingServletRequestParameterException e) {
            detalhe = "O parâmetro '%s' é obrigatório.".formatted(e.getParameterName());
        } else if (ex instanceof HttpRequestMethodNotSupportedException e) {
            Set<HttpMethod> aceitos = e.getSupportedHttpMethods();
            detalhe = "O método %s não é aceito neste endereço.%s".formatted(e.getMethod(),
                    aceitos == null || aceitos.isEmpty() ? ""
                            : " Métodos aceitos: " + aceitos.stream().map(HttpMethod::name).sorted()
                            .collect(Collectors.joining(", ")) + ".");
        } else if (ex instanceof NoResourceFoundException e) {
            detalhe = "O endereço /%s não existe.".formatted(e.getResourcePath());
        } else if (ex instanceof HttpMediaTypeNotSupportedException) {
            detalhe = "Tipo de conteúdo não suportado. Envie o corpo como application/json.";
        } else {
            detalhe = "Não foi possível processar a requisição.";
        }
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        problema.setTitle(TITULOS.getOrDefault(status.value(), "Erro na requisição"));
        return problema;
    }

    /** Erro de leitura do corpo: aponta o campo quando o JSON é válido mas um valor não serve. */
    private static String detalheCorpoInvalido(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof DatabindException erro && !erro.getPath().isEmpty()) {
            String campo = erro.getPath().stream()
                    .map(ref -> ref.getPropertyName() != null ? ref.getPropertyName() : "[" + ref.getIndex() + "]")
                    .collect(Collectors.joining("."));
            String esperado = erro instanceof MismatchedInputException m && m.getTargetType() != null
                    ? " " + esperado(m.getTargetType()) : "";
            return "O campo '%s' recebeu um valor em formato inválido.%s".formatted(campo, esperado);
        }
        return "O corpo da requisição está ausente ou não é um JSON válido em UTF-8.";
    }

    private static String esperado(Class<?> tipo) {
        if (tipo == null) {
            return "Verifique o formato do valor.";
        }
        if (tipo.isEnum()) {
            return "Valores aceitos: " + Arrays.stream(tipo.getEnumConstants()).map(String::valueOf)
                    .collect(Collectors.joining(", ")) + ".";
        }
        if (tipo == UUID.class) {
            return "Esperado: um UUID, por exemplo 3fa85f64-5717-4562-b3fc-2c963f66afa6.";
        }
        if (tipo == Integer.class || tipo == int.class || tipo == Long.class || tipo == long.class) {
            return "Esperado: um número inteiro.";
        }
        return "Verifique o formato do valor.";
    }

    private static ProblemDetail problema(HttpStatus status, String titulo, String detalhe) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        problema.setTitle(titulo);
        return problema;
    }
}
