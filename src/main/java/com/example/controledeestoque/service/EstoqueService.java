package com.example.controledeestoque.service;

import com.example.controledeestoque.domain.Bloqueio;
import com.example.controledeestoque.domain.Produto;
import com.example.controledeestoque.domain.Reserva;
import com.example.controledeestoque.domain.StatusBloqueio;
import com.example.controledeestoque.domain.StatusReserva;
import com.example.controledeestoque.exception.RecursoNaoEncontradoException;
import com.example.controledeestoque.exception.RegraNegocioException;
import com.example.controledeestoque.exception.SkuDuplicadoException;
import com.example.controledeestoque.repository.BloqueioRepository;
import com.example.controledeestoque.repository.ProdutoRepository;
import com.example.controledeestoque.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Casos de uso do estoque. As regras ficam nas entidades de domínio; este serviço
 * orquestra carregamento, transação e persistência.
 * <p>
 * O produto é identificado pelo <b>SKU</b> em todas as operações (maiúsculas e minúsculas são
 * equivalentes); o UUID do produto fica restrito ao banco. Reservas e bloqueios continuam
 * identificados pelo próprio id.
 * <p>
 * Toda operação de escrita sobre saldo passa por {@link ExecutorTransacionalComRetry}:
 * é atômica (tudo ou nada) e resiliente a conflitos de concorrência.
 */
@Service
@RequiredArgsConstructor
public class EstoqueService {

    private final ProdutoRepository produtoRepository;
    private final ReservaRepository reservaRepository;
    private final BloqueioRepository bloqueioRepository;
    private final ExecutorTransacionalComRetry executor;
    private final Clock clock;

    // ---------- Produto ----------

    /**
     * Cadastra o produto. Um SKU já existente responde {@link SkuDuplicadoException} (409). Em dois
     * cadastros simultâneos do mesmo SKU, os dois passam pela verificação prévia, mas a constraint
     * única do banco deixa só um gravar: o outro recebe o mesmo 409.
     */
    @Transactional
    public Produto criarProduto(String sku, String nome, String marca, int quantidadeInicial) {
        Produto novo = Produto.novo(sku, nome, marca, quantidadeInicial);
        if (produtoRepository.findBySku(novo.getSku()).isPresent()) {
            throw new SkuDuplicadoException(novo.getSku());
        }
        try {
            return produtoRepository.saveAndFlush(novo);
        } catch (DataIntegrityViolationException e) {
            if (violouSkuUnico(e)) {
                throw new SkuDuplicadoException(novo.getSku());
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Produto consultarProduto(String sku) {
        return buscarProduto(sku);
    }

    public Produto entrada(String sku, int quantidade) {
        return executor.executar(() -> {
            Produto produto = buscarProduto(sku);
            produto.entrada(quantidade);
            return produtoRepository.saveAndFlush(produto);
        });
    }

    public Produto saida(String sku, int quantidade) {
        return executor.executar(() -> {
            Produto produto = buscarProduto(sku);
            produto.saida(quantidade);
            return produtoRepository.saveAndFlush(produto);
        });
    }

    // ---------- Reserva ----------

    public Reserva reservar(String sku, String pedidoId, int quantidade) {
        return executor.executar(() -> {
            Produto produto = buscarProduto(sku);
            Reserva reserva = Reserva.criar(produto, pedidoId, quantidade, agora());
            return reservaRepository.saveAndFlush(reserva);
        });
    }

    /** Um item de uma reserva de pedido. */
    public record ItemReserva(String sku, int quantidade) {
    }

    /**
     * Reserva vários produtos para o mesmo pedido, tudo ou nada: se um item falhar (produto
     * inexistente, estoque insuficiente), a transação inteira é desfeita e nenhum saldo muda.
     * <p>
     * Os itens são processados em ordem fixa de SKU e o Hibernate ordena os UPDATEs pela chave
     * primária ({@code order_updates}), para que duas reservas concorrentes travem as linhas dos
     * produtos sempre na mesma ordem e não entrem em deadlock no PostgreSQL.
     */
    public List<Reserva> reservarPedido(String pedidoId, List<ItemReserva> itens) {
        List<ItemReserva> emOrdem = normalizarItens(itens).stream()
                .sorted(Comparator.comparing(ItemReserva::sku))
                .toList();
        return executor.executar(() -> {
            List<Reserva> reservas = new ArrayList<>();
            for (ItemReserva item : emOrdem) {
                Produto produto = buscarProduto(item.sku());
                reservas.add(Reserva.criar(produto, pedidoId, item.quantidade(), agora()));
            }
            return reservaRepository.saveAllAndFlush(reservas);
        });
    }

    @Transactional(readOnly = true)
    public List<Reserva> listarReservasDoPedido(String pedidoId, StatusReserva status) {
        return status == null
                ? reservaRepository.findByPedidoIdOrderByDataCriacaoAsc(pedidoId)
                : reservaRepository.findByPedidoIdAndStatusOrderByDataCriacaoAsc(pedidoId, status);
    }

    public Reserva liberarReserva(UUID reservaId) {
        return executor.executar(() -> {
            Reserva reserva = buscarReserva(reservaId);
            reserva.liberar();
            return reservaRepository.saveAndFlush(reserva);
        });
    }

    public Reserva efetivarReserva(UUID reservaId) {
        return executor.executar(() -> {
            Reserva reserva = buscarReserva(reservaId);
            reserva.efetivar();
            return reservaRepository.saveAndFlush(reserva);
        });
    }

    @Transactional(readOnly = true)
    public Reserva consultarReserva(UUID reservaId) {
        return buscarReserva(reservaId);
    }

    @Transactional(readOnly = true)
    public List<Reserva> listarReservas(String sku, StatusReserva status) {
        UUID produtoId = buscarProduto(sku).getId();
        return status == null
                ? reservaRepository.findByProdutoIdOrderByDataCriacaoAsc(produtoId)
                : reservaRepository.findByProdutoIdAndStatusOrderByDataCriacaoAsc(produtoId, status);
    }

    // ---------- Bloqueio ----------

    public Bloqueio bloquear(String sku, String pedidoId, String motivo, int quantidade) {
        return executor.executar(() -> {
            Produto produto = buscarProduto(sku);
            Bloqueio bloqueio = Bloqueio.criar(produto, pedidoId, motivo, quantidade, agora());
            return bloqueioRepository.saveAndFlush(bloqueio);
        });
    }

    public Bloqueio liberarBloqueio(UUID bloqueioId) {
        return executor.executar(() -> {
            Bloqueio bloqueio = buscarBloqueio(bloqueioId);
            bloqueio.liberar();
            return bloqueioRepository.saveAndFlush(bloqueio);
        });
    }

    @Transactional(readOnly = true)
    public Bloqueio consultarBloqueio(UUID bloqueioId) {
        return buscarBloqueio(bloqueioId);
    }

    @Transactional(readOnly = true)
    public List<Bloqueio> listarBloqueios(String sku, StatusBloqueio status) {
        UUID produtoId = buscarProduto(sku).getId();
        return status == null
                ? bloqueioRepository.findByProdutoIdOrderByDataCriacaoAsc(produtoId)
                : bloqueioRepository.findByProdutoIdAndStatusOrderByDataCriacaoAsc(produtoId, status);
    }

    // ---------- auxiliares ----------

    /** Busca pelo SKU na forma canônica (maiúsculas), usando o índice da constraint única. */
    private Produto buscarProduto(String sku) {
        String normalizado = Produto.normalizarSku(sku);
        return produtoRepository.findBySku(normalizado)
                .orElseThrow(() -> new RecursoNaoEncontradoException(
                        "Produto com sku %s não existe.".formatted(normalizado)));
    }

    private Reserva buscarReserva(UUID id) {
        return reservaRepository.findById(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Reserva", id));
    }

    private Bloqueio buscarBloqueio(UUID id) {
        return bloqueioRepository.findById(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Bloqueio", id));
    }

    /** A violação veio da constraint única do SKU (o nome aparece na mensagem do PostgreSQL e do H2)? */
    private static boolean violouSkuUnico(DataIntegrityViolationException e) {
        Throwable causa = e.getMostSpecificCause();
        return causa.getMessage() != null && causa.getMessage().toLowerCase(Locale.ROOT).contains("uk_produto_sku");
    }

    /**
     * Exige ao menos um item, todos com SKU, e cada produto uma única vez. A comparação usa o SKU
     * normalizado: "tec-01" e "TEC-01" no mesmo pedido são o mesmo produto repetido.
     */
    private static List<ItemReserva> normalizarItens(List<ItemReserva> itens) {
        if (itens == null || itens.isEmpty()) {
            throw new RegraNegocioException("O pedido deve ter ao menos um item");
        }
        Set<String> vistos = new HashSet<>();
        List<ItemReserva> normalizados = new ArrayList<>();
        for (ItemReserva item : itens) {
            String sku = Produto.normalizarSku(item.sku());
            if (sku == null || sku.isEmpty()) {
                throw new RegraNegocioException("Todo item do pedido deve informar o sku do produto");
            }
            if (!vistos.add(sku)) {
                throw new RegraNegocioException(("O produto %s aparece mais de uma vez no pedido; "
                        + "informe a quantidade total em um único item").formatted(sku));
            }
            normalizados.add(new ItemReserva(sku, item.quantidade()));
        }
        return normalizados;
    }

    private LocalDateTime agora() {
        return LocalDateTime.now(clock);
    }
}
