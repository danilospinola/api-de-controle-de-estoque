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
 * Bloqueio de unidades de um produto, tornando-as indisponíveis para uso.
 * Ciclo de vida: ATIVO → LIBERADO.
 */
@Entity
@Table(name = "bloqueio")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bloqueio {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "produto_id", nullable = false)
    private Produto produto;

    /** Motivo do bloqueio (opcional), ex.: "avaria", "inspeção de qualidade". */
    @Column(length = 255)
    private String motivo;

    /** Identificador da solicitação/pedido no sistema de origem (referência externa, sem FK física). */
    @Column(nullable = false, length = 100)
    private String pedidoId;

    @Column(nullable = false)
    private Integer quantidade;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatusBloqueio status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime dataCriacao;

    /** Tamanho máximo do motivo (coluna motivo). */
    public static final int TAMANHO_MAXIMO_MOTIVO = 255;

    /** Cria o bloqueio já debitando o saldo disponível do produto. Valida tudo antes de alterar o saldo. */
    public static Bloqueio criar(Produto produto, String pedidoId, String motivo, int quantidade, LocalDateTime agora) {
        String pedido = Textos.obrigatorio(pedidoId, "pedidoId", Reserva.TAMANHO_MAXIMO_PEDIDO);
        String motivoNormalizado = Textos.opcional(motivo, "motivo", TAMANHO_MAXIMO_MOTIVO);
        produto.bloquear(quantidade);
        Bloqueio bloqueio = new Bloqueio();
        bloqueio.produto = produto;
        bloqueio.pedidoId = pedido;
        bloqueio.motivo = motivoNormalizado;
        bloqueio.quantidade = quantidade;
        bloqueio.status = StatusBloqueio.ATIVO;
        bloqueio.dataCriacao = agora;
        return bloqueio;
    }

    /** Encerra o bloqueio, devolvendo as unidades ao disponível. */
    public void liberar() {
        if (status != StatusBloqueio.ATIVO) {
            throw new RegraNegocioException(
                    "Bloqueio %s não pode ser liberado: status atual é %s".formatted(id, status));
        }
        produto.liberarBloqueio(quantidade);
        status = StatusBloqueio.LIBERADO;
    }
}
