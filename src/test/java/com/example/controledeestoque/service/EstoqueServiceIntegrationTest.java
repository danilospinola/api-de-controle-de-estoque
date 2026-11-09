package com.example.controledeestoque.service;

import com.example.controledeestoque.domain.Bloqueio;
import com.example.controledeestoque.domain.Produto;
import com.example.controledeestoque.domain.Reserva;
import com.example.controledeestoque.domain.StatusReserva;
import com.example.controledeestoque.exception.EstoqueInsuficienteException;
import com.example.controledeestoque.exception.RecursoNaoEncontradoException;
import com.example.controledeestoque.exception.RegraNegocioException;
import com.example.controledeestoque.exception.SkuDuplicadoException;
import com.example.controledeestoque.service.EstoqueService.ItemReserva;
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
    private JdbcTemplate jdbcTemplate;

    @Test
    void fluxoCompletoPersisteSaldosEStatus() {
        String sku = criarProduto(NOME,20).getSku();

        service.entrada(sku, 5);                                     // 25 disponível
        service.saida(sku, 3);                                       // 22 disponível
        Reserva r1 = service.reservar(sku, "PED-1", 4);              // 18 disp / 4 res
        Reserva r2 = service.reservar(sku, "PED-2", 6);              // 12 disp / 10 res
        Bloqueio b1 = service.bloquear(sku, "PED-3", "avaria", 2);   // 10 disp / 2 bloq
        service.liberarReserva(r1.getId());                                // 14 disp / 6 res
        service.efetivarReserva(r2.getId());                               // total 16, 0 res
        service.liberarBloqueio(b1.getId());                               // 16 disp / 0 bloq

        Produto produto = service.consultarProduto(sku);
        assertThat(produto.getNome()).isEqualTo(NOME);
        assertSaldo(produto, 16, 16, 0, 0);
        assertThat(service.consultarReserva(r1.getId()).getStatus()).isEqualTo(StatusReserva.LIBERADA);
        assertThat(service.consultarReserva(r2.getId()).getStatus()).isEqualTo(StatusReserva.EFETIVADA);
        assertThat(service.listarReservas(sku, null)).hasSize(2);
        assertThat(service.listarReservas(sku, StatusReserva.ATIVA)).isEmpty();
    }

    @Test
    void operacaoInvalidaNaoPersisteNadaParcialmente() {
        String sku = criarProduto(NOME,5).getSku();

        assertThatThrownBy(() -> service.reservar(sku, "PED-1", 6))
                .isInstanceOf(EstoqueInsuficienteException.class);

        assertSaldo(service.consultarProduto(sku), 5, 5, 0, 0);
        assertThat(service.listarReservas(sku, null)).isEmpty();
    }

    @Test
    void liberarReservaDuasVezesERejeitado() {
        String sku = criarProduto(NOME,5).getSku();
        Reserva reserva = service.reservar(sku, "PED-1", 2);
        service.liberarReserva(reserva.getId());

        assertThatThrownBy(() -> service.liberarReserva(reserva.getId()))
                .isInstanceOf(RegraNegocioException.class);
        assertSaldo(service.consultarProduto(sku), 5, 5, 0, 0);
    }

    @Test
    void recursosInexistentesGeramNaoEncontrado() {
        String skuInexistente = "it-nao-existe";
        UUID inexistente = UUID.randomUUID();

        assertThatThrownBy(() -> service.consultarProduto(skuInexistente))
                .isInstanceOf(RecursoNaoEncontradoException.class)
                .hasMessage("Produto com sku IT-NAO-EXISTE não existe.");
        assertThatThrownBy(() -> service.entrada(skuInexistente, 1)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> service.reservar(skuInexistente, "PED-1", 1)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> service.bloquear(skuInexistente, "PED-1", null, 1)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> service.listarReservas(skuInexistente, null)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> service.listarBloqueios(skuInexistente, null)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> service.liberarReserva(inexistente)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> service.liberarBloqueio(inexistente)).isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    void produtoEIdentificadoPeloSkuEmQualquerCaixa() {
        String sku = criarProduto(NOME, 10).getSku();
        String minusculo = sku.toLowerCase();

        service.entrada(minusculo, 2);
        Reserva reserva = service.reservar("  " + minusculo + " ", "PED-1", 3);
        Bloqueio bloqueio = service.bloquear(minusculo, "PED-2", null, 1);

        assertThat(reserva.getProduto().getSku()).isEqualTo(sku);
        assertThat(bloqueio.getProduto().getSku()).isEqualTo(sku);
        assertSaldo(service.consultarProduto(minusculo), 12, 8, 3, 1);
        assertThat(service.listarReservas(minusculo, null)).hasSize(1);
        assertThat(service.listarBloqueios(minusculo, null)).hasSize(1);
    }

    @Test
    void dataDeCriacaoDevolvidaNaCriacaoEIgualAGravada() {
        String sku = criarProduto(NOME, 10).getSku();
        Reserva criada = service.reservar(sku, "PED-DATA", 1);
        Bloqueio bloqueio = service.bloquear(sku, "PED-DATA", null, 1);

        // Antes, a criação devolvia nanossegundos que o banco descarta: a mesma reserva tinha duas datas.
        assertThat(criada.getDataCriacao().getNano() % 1_000).isZero();
        assertThat(service.consultarReserva(criada.getId()).getDataCriacao()).isEqualTo(criada.getDataCriacao());
        assertThat(service.consultarBloqueio(bloqueio.getId()).getDataCriacao()).isEqualTo(bloqueio.getDataCriacao());
    }

    @Test
    void reservasDoPedidoTemOMesmoInstanteEOrdemDeterministicaPorSku() {
        List<String> skus = new ArrayList<>(List.of(
                criarProduto("Produto 1", 5).getSku(),
                criarProduto("Produto 2", 5).getSku(),
                criarProduto("Produto 3", 5).getSku()));
        List<String> emOrdem = skus.stream().sorted().toList();

        // Itens enviados na ordem inversa (alvo Java 17: sem List.reversed()).
        List<Reserva> reservas = service.reservarPedido("PED-ORDEM", skus.stream()
                .sorted(java.util.Comparator.reverseOrder()).map(sku -> new ItemReserva(sku, 1)).toList());

        assertThat(reservas).extracting(r -> r.getProduto().getSku()).containsExactlyElementsOf(emOrdem);
        assertThat(reservas).extracting(Reserva::getDataCriacao).containsOnly(reservas.get(0).getDataCriacao());
        for (int i = 0; i < 3; i++) {
            assertThat(service.listarReservasDoPedido("PED-ORDEM", null))
                    .extracting(r -> r.getProduto().getSku()).containsExactlyElementsOf(emOrdem);
        }
    }

    @Test
    void consultasDeReservaEBloqueioJaTrazemOProdutoParaUsoForaDaTransacao() {
        String sku = criarProduto(NOME, 10).getSku();
        UUID reservaId = service.reservar(sku, "PED-LAZY", 2).getId();
        UUID bloqueioId = service.bloquear(sku, "PED-LAZY", null, 1).getId();

        // Fora de qualquer transação (open-in-view=false), como na montagem da resposta HTTP.
        assertThat(service.consultarReserva(reservaId).getProduto().getSku()).isEqualTo(sku);
        assertThat(service.consultarBloqueio(bloqueioId).getProduto().getSku()).isEqualTo(sku);
        assertThat(service.listarReservas(sku, null).get(0).getProduto().getSku()).isEqualTo(sku);
        assertThat(service.listarBloqueios(sku, null).get(0).getProduto().getSku()).isEqualTo(sku);
        assertThat(service.listarReservasDoPedido("PED-LAZY", null).get(0).getProduto().getSku()).isEqualTo(sku);
    }

    @Test
    void bancoRejeitaEstadoInconsistenteMesmoFuraDaAplicacao() {
        String sku = criarProduto(NOME,5).getSku();

        // Simula um bug/acesso direto que quebraria a invariante total = disp + res + bloq.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE produto SET quantidade_disponivel = quantidade_disponivel + 1 WHERE sku = ?", sku))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE produto SET quantidade_total = -1, quantidade_disponivel = -1 WHERE sku = ?", sku))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE produto SET nome = '   ' WHERE sku = ?", sku))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE produto SET nome = NULL WHERE sku = ?", sku))
                .isInstanceOf(DataIntegrityViolationException.class);

        // V2: pedido_id em branco é barrado pelo banco mesmo sem passar pelo domínio.
        Reserva reserva = service.reservar(sku, "PED-1", 1);
        Bloqueio bloqueio = service.bloquear(sku, "PED-2", null, 1);
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
        String sku = criarProduto(NOME,estoqueInicial).getSku();

        List<Resultado> resultados = executarEmParalelo(requisicoes, i ->
                () -> service.reservar(sku, "PED-" + i, quantidadePorReserva));

        long sucessos = resultados.stream().filter(r -> r == Resultado.SUCESSO).count();
        long insuficientes = resultados.stream().filter(r -> r == Resultado.ESTOQUE_INSUFICIENTE).count();

        assertThat(sucessos).isEqualTo(estoqueInicial / quantidadePorReserva);
        assertThat(insuficientes).isEqualTo(requisicoes - sucessos);

        Produto produto = service.consultarProduto(sku);
        assertSaldo(produto, estoqueInicial, 0, estoqueInicial, 0);
        int somaReservasAtivas = service.listarReservas(sku, StatusReserva.ATIVA).stream()
                .mapToInt(Reserva::getQuantidade).sum();
        assertThat(somaReservasAtivas).isEqualTo(produto.getQuantidadeReservada());
    }

    @Test
    void liberacoesConcorrentesDaMesmaReservaDevolvemUnidadesUmaUnicaVez() throws Exception {
        String sku = criarProduto(NOME,10).getSku();
        UUID reservaId = service.reservar(sku, "PED-1", 4).getId();

        List<Resultado> resultados = executarEmParalelo(8, i -> () -> service.liberarReserva(reservaId));

        assertThat(resultados).filteredOn(r -> r == Resultado.SUCESSO).hasSize(1);
        assertThat(resultados).filteredOn(r -> r == Resultado.REGRA_NEGOCIO).hasSize(7);
        assertSaldo(service.consultarProduto(sku), 10, 10, 0, 0);
    }

    @Test
    void operacoesMistasConcorrentesPreservamAInvariante() throws Exception {
        String sku = criarProduto(NOME,30).getSku();

        List<Resultado> resultados = executarEmParalelo(30, i -> switch (i % 4) {
            case 0 -> () -> service.entrada(sku, 2);
            case 1 -> () -> service.saida(sku, 3);
            case 2 -> () -> service.reservar(sku, "PED-" + i, 2);
            default -> () -> service.bloquear(sku, "PED-" + i, null, 1);
        });

        assertThat(resultados).doesNotContain(Resultado.OUTRO_ERRO);
        Produto produto = service.consultarProduto(sku);
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

    @Test
    void reservaDePedidoComVariosProdutosETudoOuNada() {
        String teclado = criarProduto("Teclado", 5).getSku();
        String mouse = criarProduto("Mouse", 1).getSku();

        assertThatThrownBy(() -> service.reservarPedido("PED-MULTI-1",
                List.of(new ItemReserva(teclado, 2), new ItemReserva(mouse, 3))))
                .isInstanceOf(EstoqueInsuficienteException.class)
                .hasMessageContaining(mouse);
        assertSaldo(service.consultarProduto(teclado), 5, 5, 0, 0);
        assertSaldo(service.consultarProduto(mouse), 1, 1, 0, 0);
        assertThat(service.listarReservasDoPedido("PED-MULTI-1", null)).isEmpty();

        List<Reserva> reservas = service.reservarPedido("PED-MULTI-1",
                List.of(new ItemReserva(teclado, 2), new ItemReserva(mouse, 1)));

        assertThat(reservas).hasSize(2).allMatch(r -> r.getStatus() == StatusReserva.ATIVA);
        assertSaldo(service.consultarProduto(teclado), 5, 3, 2, 0);
        assertSaldo(service.consultarProduto(mouse), 1, 0, 1, 0);
        assertThat(service.listarReservasDoPedido("PED-MULTI-1", StatusReserva.ATIVA)).hasSize(2);
    }

    @Test
    void reservaDePedidoRejeitaItensInvalidosSemAlterarNada() {
        String produto = criarProduto("Monitor", 5).getSku();

        assertThatThrownBy(() -> service.reservarPedido("PED-MULTI-2", List.of()))
                .isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> service.reservarPedido("PED-MULTI-2",
                List.of(new ItemReserva(produto, 1), new ItemReserva(produto.toLowerCase(), 2))))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("mais de uma vez");
        assertThatThrownBy(() -> service.reservarPedido("PED-MULTI-2",
                List.of(new ItemReserva(produto, 1), new ItemReserva(" ", 1))))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("sku");
        assertThatThrownBy(() -> service.reservarPedido("PED-MULTI-2",
                List.of(new ItemReserva(produto, 1), new ItemReserva("IT-NAO-EXISTE", 1))))
                .isInstanceOf(RecursoNaoEncontradoException.class)
                .hasMessageContaining("IT-NAO-EXISTE");

        assertSaldo(service.consultarProduto(produto), 5, 5, 0, 0);
        assertThat(service.listarReservasDoPedido("PED-MULTI-2", null)).isEmpty();
    }

    @Test
    void reservasDePedidoConcorrentesEmOrdensInvertidasNaoVendemAMaisNemTravam() throws Exception {
        String a = criarProduto("Produto A", 5).getSku();
        String b = criarProduto("Produto B", 5).getSku();
        int pedidos = 10;
        ExecutorService pool = Executors.newFixedThreadPool(pedidos);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futuros = new ArrayList<>();
            for (int i = 0; i < pedidos; i++) {
                // Metade dos pedidos lista A antes de B e a outra metade, B antes de A.
                List<ItemReserva> itens = i % 2 == 0
                        ? List.of(new ItemReserva(a, 1), new ItemReserva(b, 1))
                        : List.of(new ItemReserva(b, 1), new ItemReserva(a, 1));
                String pedido = "PED-CONC-" + i;
                futuros.add(pool.submit(() -> {
                    largada.await();
                    try {
                        service.reservarPedido(pedido, itens);
                        return true;
                    } catch (EstoqueInsuficienteException e) {
                        return false;
                    }
                }));
            }
            largada.countDown();
            long sucessos = 0;
            for (Future<Boolean> futuro : futuros) {
                if (futuro.get(30, TimeUnit.SECONDS)) {
                    sucessos++;
                }
            }

            assertThat(sucessos).isEqualTo(5);
            assertSaldo(service.consultarProduto(a), 5, 0, 5, 0);
            assertSaldo(service.consultarProduto(b), 5, 0, 5, 0);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void skuRepetidoEmQualquerCaixaResponde409SemGravarNada() {
        Produto original = service.criarProduto("int-sku-dup", "Teclado", "Logitech", 5);

        assertThat(original.getSku()).isEqualTo("INT-SKU-DUP");
        assertThatThrownBy(() -> service.criarProduto("  INT-sku-DUP ", "Outro nome", null, 1))
                .isInstanceOf(SkuDuplicadoException.class)
                .hasMessageContaining("INT-SKU-DUP")
                .hasMessageContaining("/api/produtos/INT-SKU-DUP/entradas");
        Produto gravado = service.consultarProduto("int-sku-dup");
        assertThat(gravado.getNome()).isEqualTo("Teclado");
        assertSaldo(gravado, 5, 5, 0, 0);
    }

    @Test
    void cadastrosSimultaneosDoMesmoSkuDeixamSoUmVencer() throws Exception {
        int tentativas = 8;
        ExecutorService pool = Executors.newFixedThreadPool(tentativas);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futuros = new ArrayList<>();
            for (int i = 0; i < tentativas; i++) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    try {
                        service.criarProduto("INT-SKU-CONCORRENTE", "Mouse", null, 1);
                        return true;
                    } catch (SkuDuplicadoException e) {
                        return false;
                    }
                }));
            }
            largada.countDown();
            long vencedores = 0;
            for (Future<Boolean> futuro : futuros) {
                if (futuro.get(30, TimeUnit.SECONDS)) {
                    vencedores++;
                }
            }
            assertThat(vencedores).isEqualTo(1);
            assertThat(service.consultarProduto("INT-SKU-CONCORRENTE").getQuantidadeTotal()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void bancoRejeitaSkuForaDoFormatoEMarcaEmBranco() {
        String sku = criarProduto("Webcam", 1).getSku();

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE produto SET sku = 'minusculo' WHERE sku = ?", sku))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE produto SET sku = NULL WHERE sku = ?", sku))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE produto SET marca = '   ' WHERE sku = ?", sku))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Cada produto de teste recebe um SKU único: o banco é compartilhado entre os testes da classe. */
    private Produto criarProduto(String nome, int quantidade) {
        return service.criarProduto("IT-" + UUID.randomUUID().toString().substring(0, 13), nome, null, quantidade);
    }

    private static void assertSaldo(Produto produto, int total, int disponivel, int reservada, int bloqueada) {
        assertThat(produto.getQuantidadeTotal()).as("total").isEqualTo(total);
        assertThat(produto.getQuantidadeDisponivel()).as("disponível").isEqualTo(disponivel);
        assertThat(produto.getQuantidadeReservada()).as("reservada").isEqualTo(reservada);
        assertThat(produto.getQuantidadeBloqueada()).as("bloqueada").isEqualTo(bloqueada);
    }
}
