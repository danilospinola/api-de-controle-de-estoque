package com.example.controledeestoque.service;

import com.example.controledeestoque.domain.Bloqueio;
import com.example.controledeestoque.domain.Produto;
import com.example.controledeestoque.domain.Reserva;
import com.example.controledeestoque.domain.StatusBloqueio;
import com.example.controledeestoque.domain.StatusReserva;
import com.example.controledeestoque.exception.RecursoNaoEncontradoException;
import com.example.controledeestoque.repository.BloqueioRepository;
import com.example.controledeestoque.repository.ProdutoRepository;
import com.example.controledeestoque.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Casos de uso do estoque. As regras ficam nas entidades de domínio; este serviço
 * orquestra carregamento, transação e persistência.
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

    @Transactional
    public Produto criarProduto(String nome, int quantidadeInicial) {
        return produtoRepository.save(Produto.novo(nome, quantidadeInicial));
    }

    @Transactional(readOnly = true)
    public Produto consultarProduto(UUID produtoId) {
        return buscarProduto(produtoId);
    }

    public Produto entrada(UUID produtoId, int quantidade) {
        return executor.executar(() -> {
            Produto produto = buscarProduto(produtoId);
            produto.entrada(quantidade);
            return produtoRepository.saveAndFlush(produto);
        });
    }

    public Produto saida(UUID produtoId, int quantidade) {
        return executor.executar(() -> {
            Produto produto = buscarProduto(produtoId);
            produto.saida(quantidade);
            return produtoRepository.saveAndFlush(produto);
        });
    }

    // ---------- Reserva ----------

    public Reserva reservar(UUID produtoId, String pedidoId, int quantidade) {
        return executor.executar(() -> {
            Produto produto = buscarProduto(produtoId);
            Reserva reserva = Reserva.criar(produto, pedidoId, quantidade, agora());
            return reservaRepository.saveAndFlush(reserva);
        });
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
    public List<Reserva> listarReservas(UUID produtoId, StatusReserva status) {
        exigirProdutoExistente(produtoId);
        return status == null
                ? reservaRepository.findByProdutoIdOrderByDataCriacaoAsc(produtoId)
                : reservaRepository.findByProdutoIdAndStatusOrderByDataCriacaoAsc(produtoId, status);
    }

    // ---------- Bloqueio ----------

    public Bloqueio bloquear(UUID produtoId, String pedidoId, String motivo, int quantidade) {
        return executor.executar(() -> {
            Produto produto = buscarProduto(produtoId);
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
    public List<Bloqueio> listarBloqueios(UUID produtoId, StatusBloqueio status) {
        exigirProdutoExistente(produtoId);
        return status == null
                ? bloqueioRepository.findByProdutoIdOrderByDataCriacaoAsc(produtoId)
                : bloqueioRepository.findByProdutoIdAndStatusOrderByDataCriacaoAsc(produtoId, status);
    }

    // ---------- auxiliares ----------

    private Produto buscarProduto(UUID id) {
        return produtoRepository.findById(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Produto", id));
    }

    private Reserva buscarReserva(UUID id) {
        return reservaRepository.findById(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Reserva", id));
    }

    private Bloqueio buscarBloqueio(UUID id) {
        return bloqueioRepository.findById(id)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Bloqueio", id));
    }

    private void exigirProdutoExistente(UUID id) {
        if (!produtoRepository.existsById(id)) {
            throw new RecursoNaoEncontradoException("Produto", id);
        }
    }

    private LocalDateTime agora() {
        return LocalDateTime.now(clock);
    }
}
