package com.example.controledeestoque.service;

import com.example.controledeestoque.domain.Bloqueio;
import com.example.controledeestoque.domain.Produto;
import com.example.controledeestoque.domain.Reserva;
import com.example.controledeestoque.domain.StatusReserva;
import com.example.controledeestoque.exception.EstoqueInsuficienteException;
import com.example.controledeestoque.exception.RecursoNaoEncontradoException;
import com.example.controledeestoque.exception.RegraNegocioException;
import com.example.controledeestoque.repository.ReservaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integração real com JPA + Flyway + banco (H2 em modo PostgreSQL).
 * Foco: atomicidade, persistência do ciclo de vida e consistência sob concorrência.
 */
@SpringBootTest(properties = {
        // Alta contenção proposital nos testes de concorrência: permite tentativas suficientes.
        "estoque.concorrencia.max-tentativas=50",
        "estoque.concorrencia.espera-base-ms=5"
})
class EstoqueServiceIntegrationTest {

    private static final String NOME = "Cadeira ergonômica";

    @Autowired
    private EstoqueService service;

    @Autowired
    private ReservaRepository reservaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void fluxoCompletoPersisteSaldosEStatus() {
        UUID produtoId = service.criarProduto(NOME,20).getId();

        service.entrada(produtoId, 5);                                     // 25 disponível
        service.saida(produtoId, 3);                                       // 22 disponível
        Reserva r1 = service.reservar(produtoId, "PED-1", 4);              // 18 disp / 4 res
        Reserva r2 = service.reservar(produtoId, "PED-2", 6);              // 12 disp / 10 res
        Bloqueio b1 = service.bloquear(produtoId, "PED-3", "avaria", 2);   // 10 disp / 2 bloq
        service.liberarReserva(r1.getId());                                // 14 disp / 6 res
        service.efetivarReserva(r2.getId());                               // total 16, 0 res
        service.liberarBloqueio(b1.getId());                               // 16 disp / 0 bloq

        Produto produto = service.consultarProduto(produtoId);
        assertThat(produto.getNome()).isEqualTo(NOME);
        assertSaldo(produto, 16, 16, 0, 0);
        assertThat(service.consultarReserva(r1.getId()).getStatus()).isEqualTo(StatusReserva.LIBERADA);
        assertThat(service.consultarReserva(r2.getId()).getStatus()).isEqualTo(StatusReserva.EFETIVADA);
        assertThat(service.listarReservas(produtoId, null)).hasSize(2);
        assertThat(service.listarReservas(produtoId, StatusReserva.ATIVA)).isEmpty();
    }

    @Test
    void operacaoInvalidaNaoPersisteNadaParcialmente() {
        UUID produtoId = service.criarProduto(NOME,5).getId();

        assertThatThrownBy(() -> service.reservar(produtoId, "PED-1", 6))
                .isInstanceOf(EstoqueInsuficienteException.class);

        assertSaldo(service.consultarProduto(produtoId), 5, 5, 0, 0);
        assertThat(service.listarReservas(produtoId, null)).isEmpty();
    }

    @Test
    void liberarReservaDuasVezesERejeitado() {
        UUID produtoId = service.criarProduto(NOME,5).getId();
        Reserva reserva = service.reservar(produtoId, "PED-1", 2);
        service.liberarReserva(reserva.getId());

        assertThatThrownBy(() -> service.liberarReserva(reserva.getId()))
                .isInstanceOf(RegraNegocioException.class);
        assertSaldo(service.consultarProduto(produtoId), 5, 5, 0, 0);
    }

    @Test
    void recursosInexistentesGeramNaoEncontrado() {
        UUID inexistente = UUID.randomUUID();

        assertThatThrownBy(() -> service.consultarProduto(inexistente)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> service.entrada(inexistente, 1)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> service.liberarReserva(inexistente)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> service.liberarBloqueio(inexistente)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> service.listarReservas(inexistente, null)).isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    void bancoRejeitaEstadoInconsistenteMesmoFuraDaAplicacao() {
        UUID produtoId = service.criarProduto(NOME,5).getId();

        // Simula um bug/acesso direto que quebraria a invariante total = disp + res + bloq.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE produto SET quantidade_disponivel = quantidade_disponivel + 1 WHERE id = ?", produtoId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE produto SET quantidade_total = -1, quantidade_disponivel = -1 WHERE id = ?", produtoId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE produto SET nome = '   ' WHERE id = ?", produtoId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE produto SET nome = NULL WHERE id = ?", produtoId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // V2: pedido_id em branco é barrado pelo banco mesmo sem passar pelo domínio.
        Reserva reserva = service.reservar(produtoId, "PED-1", 1);
        Bloqueio bloqueio = service.bloquear(produtoId, "PED-2", null, 1);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE reserva SET pedido_id = '   ' WHERE id = ?", reserva.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE bloqueio SET pedido_id = '' WHERE id = ?", bloqueio.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reservasConcorrentesNuncaUltrapassamODisponivel() throws Exception {
        int estoqueInicial = 50;
        int quantidadePorReserva = 5;
        int requisicoes = 20; // demanda de 100 unidades para 50 disponíveis
        UUID produtoId = service.criarProduto(NOME,estoqueInicial).getId();

        List<Resultado> resultados = executarEmParalelo(requisicoes, i ->
                () -> service.reservar(produtoId, "PED-" + i, quantidadePorReserva));

        long sucessos = resultados.stream().filter(r -> r == Resultado.SUCESSO).count();
        long insuficientes = resultados.stream().filter(r -> r == Resultado.ESTOQUE_INSUFICIENTE).count();

        assertThat(sucessos).isEqualTo(estoqueInicial / quantidadePorReserva);
        assertThat(insuficientes).isEqualTo(requisicoes - sucessos);

        Produto produto = service.consultarProduto(produtoId);
        assertSaldo(produto, estoqueInicial, 0, estoqueInicial, 0);
        int somaReservasAtivas = reservaRepository
                .findByProdutoIdAndStatusOrderByDataCriacaoAsc(produtoId, StatusReserva.ATIVA).stream()
                .mapToInt(Reserva::getQuantidade).sum();
        assertThat(somaReservasAtivas).isEqualTo(produto.getQuantidadeReservada());
    }

    @Test
    void liberacoesConcorrentesDaMesmaReservaDevolvemUnidadesUmaUnicaVez() throws Exception {
        UUID produtoId = service.criarProduto(NOME,10).getId();
        UUID reservaId = service.reservar(produtoId, "PED-1", 4).getId();

        List<Resultado> resultados = executarEmParalelo(8, i -> () -> service.liberarReserva(reservaId));

        assertThat(resultados).filteredOn(r -> r == Resultado.SUCESSO).hasSize(1);
        assertThat(resultados).filteredOn(r -> r == Resultado.REGRA_NEGOCIO).hasSize(7);
        assertSaldo(service.consultarProduto(produtoId), 10, 10, 0, 0);
    }

    @Test
    void operacoesMistasConcorrentesPreservamAInvariante() throws Exception {
        UUID produtoId = service.criarProduto(NOME,30).getId();

        List<Resultado> resultados = executarEmParalelo(30, i -> switch (i % 4) {
            case 0 -> () -> service.entrada(produtoId, 2);
            case 1 -> () -> service.saida(produtoId, 3);
            case 2 -> () -> service.reservar(produtoId, "PED-" + i, 2);
            default -> () -> service.bloquear(produtoId, "PED-" + i, null, 1);
        });

        assertThat(resultados).doesNotContain(Resultado.OUTRO_ERRO);
        Produto produto = service.consultarProduto(produtoId);
        assertThat(produto.getQuantidadeDisponivel()).isNotNegative();
        assertThat(produto.getQuantidadeDisponivel() + produto.getQuantidadeReservada() + produto.getQuantidadeBloqueada())
                .isEqualTo(produto.getQuantidadeTotal());
    }

    // ---------- utilitários ----------

    private enum Resultado { SUCESSO, ESTOQUE_INSUFICIENTE, REGRA_NEGOCIO, OUTRO_ERRO }

    private interface FabricaDeTarefa {
        Callable<?> criar(int indice);
    }

    /** Dispara todas as tarefas ao mesmo tempo (latch de largada) para maximizar a disputa. */
    private static List<Resultado> executarEmParalelo(int quantidade, FabricaDeTarefa fabrica) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(quantidade);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<Resultado>> futuros = new ArrayList<>();
            for (int i = 0; i < quantidade; i++) {
                Callable<?> tarefa = fabrica.criar(i);
                futuros.add(pool.submit(() -> {
                    largada.await();
                    try {
                        tarefa.call();
                        return Resultado.SUCESSO;
                    } catch (EstoqueInsuficienteException e) {
                        return Resultado.ESTOQUE_INSUFICIENTE;
                    } catch (RegraNegocioException e) {
                        return Resultado.REGRA_NEGOCIO;
                    } catch (Exception e) {
                        return Resultado.OUTRO_ERRO;
                    }
                }));
            }
            largada.countDown();
            List<Resultado> resultados = new ArrayList<>();
            for (Future<Resultado> futuro : futuros) {
                resultados.add(futuro.get(30, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertSaldo(Produto produto, int total, int disponivel, int reservada, int bloqueada) {
        assertThat(produto.getQuantidadeTotal()).as("total").isEqualTo(total);
        assertThat(produto.getQuantidadeDisponivel()).as("disponível").isEqualTo(disponivel);
        assertThat(produto.getQuantidadeReservada()).as("reservada").isEqualTo(reservada);
        assertThat(produto.getQuantidadeBloqueada()).as("bloqueada").isEqualTo(bloqueada);
    }
}
