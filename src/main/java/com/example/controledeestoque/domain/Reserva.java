package com.example.controledeestoque.domain;

import com.example.controledeestoque.exception.RegraNegocioException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Reserva de unidades de um produto para um pedido.
 * Ciclo de vida: ATIVA → LIBERADA (cancelamento) ou ATIVA → EFETIVADA (consumo).
 * Estados finais não podem ser alterados.
 */
@Entity
@Table(name = "reserva")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reserva {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "produto_id", nullable = false)
    private Produto produto;

    /** Identificador do pedido no sistema de origem (referência externa, sem FK física). */
    @Column(nullable = false, length = 100)
    private String pedidoId;

    @Column(nullable = false)
    private Integer quantidade;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatusReserva status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime dataCriacao;

    /** Tamanho máximo do identificador do pedido (coluna pedido_id), compartilhado com o bloqueio. */
    public static final int TAMANHO_MAXIMO_PEDIDO = 100;

    /** Cria a reserva já debitando o saldo disponível do produto. Valida tudo antes de alterar o saldo. */
    public static Reserva criar(Produto produto, String pedidoId, int quantidade, LocalDateTime agora) {
        String pedido = Textos.obrigatorio(pedidoId, "pedidoId", TAMANHO_MAXIMO_PEDIDO);
        produto.reservar(quantidade);
        Reserva reserva = new Reserva();
        reserva.produto = produto;
        reserva.pedidoId = pedido;
        reserva.quantidade = quantidade;
        reserva.status = StatusReserva.ATIVA;
        reserva.dataCriacao = agora;
        return reserva;
    }

    /** Cancela a reserva, devolvendo as unidades ao disponível. */
    public void liberar() {
        exigirAtiva("liberada");
        produto.liberarReserva(quantidade);
        status = StatusReserva.LIBERADA;
    }

    /** Consome a reserva: as unidades saem definitivamente do estoque. */
    public void efetivar() {
        exigirAtiva("efetivada");
        produto.efetivarReserva(quantidade);
        status = StatusReserva.EFETIVADA;
    }

    private void exigirAtiva(String acao) {
        if (status != StatusReserva.ATIVA) {
            throw new RegraNegocioException(
                    "Reserva %s não pode ser %s: status atual é %s".formatted(id, acao, status));
        }
    }
}
