package com.example.controledeestoque.domain;

import com.example.controledeestoque.exception.EstoqueInsuficienteException;
import com.example.controledeestoque.exception.RegraNegocioException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Saldo de estoque de um produto.
 * <p>
 * Invariante garantida por todas as operações (e reforçada por CHECK constraint no banco):
 * {@code quantidadeTotal = quantidadeDisponivel + quantidadeReservada + quantidadeBloqueada},
 * com todas as quantidades maiores ou iguais a zero.
 * <p>
 * Não há setters: o estado só muda por meio das operações de negócio, que validam
 * as pré-condições <b>antes</b> de alterar qualquer campo — uma operação inválida
 * nunca deixa o objeto em estado intermediário.
 * <p>
 * {@link #version} implementa lock otimista: duas transações concorrentes que alterem
 * o mesmo produto não conseguem ambas gravar; a segunda recebe conflito de versão.
 * <p>
 * {@link #nome} é a identificação legível do produto: obrigatório, sem espaços nas pontas e
 * <b>não único</b> (produtos distintos podem compartilhar o nome). A identidade continua sendo o {@link #id}.
 */
@Entity
@Table(name = "produto")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Produto {

    public static final int TAMANHO_MAXIMO_NOME = 150;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = TAMANHO_MAXIMO_NOME)
    private String nome;

    @Column(nullable = false)
    private Integer quantidadeTotal;

    @Column(nullable = false)
    private Integer quantidadeDisponivel;

    @Column(nullable = false)
    private Integer quantidadeReservada;

    @Column(nullable = false)
    private Integer quantidadeBloqueada;

    @Version
    private Long version;

    public static Produto novo(String nome, int quantidadeInicial) {
        String nomeNormalizado = Textos.obrigatorio(nome, "nome", TAMANHO_MAXIMO_NOME);
        if (quantidadeInicial < 0) {
            throw new RegraNegocioException("A quantidade inicial não pode ser negativa");
        }
        Produto produto = new Produto();
        produto.nome = nomeNormalizado;
        produto.quantidadeTotal = quantidadeInicial;
        produto.quantidadeDisponivel = quantidadeInicial;
        produto.quantidadeReservada = 0;
        produto.quantidadeBloqueada = 0;
        return produto;
    }

    /** Entrada: novas unidades chegam ao estoque já disponíveis. */
    public void entrada(int quantidade) {
        exigirPositiva(quantidade);
        // total >= disponível: verificar o total garante que nenhum dos dois ultrapassa o limite de Integer.
        if (quantidade > Integer.MAX_VALUE - quantidadeTotal) {
            throw new RegraNegocioException(
                    "A entrada excederia o limite de %d unidades por produto".formatted(Integer.MAX_VALUE));
        }
        quantidadeTotal += quantidade;
        quantidadeDisponivel += quantidade;
    }

    /** Saída avulsa: retira unidades do saldo disponível. */
    public void saida(int quantidade) {
        exigirDisponivel(quantidade);
        quantidadeTotal -= quantidade;
        quantidadeDisponivel -= quantidade;
    }

    void reservar(int quantidade) {
        exigirDisponivel(quantidade);
        quantidadeDisponivel -= quantidade;
        quantidadeReservada += quantidade;
    }

    void liberarReserva(int quantidade) {
        exigirPositiva(quantidade);
        exigirSaldo(quantidade, quantidadeReservada, "reservada");
        quantidadeReservada -= quantidade;
        quantidadeDisponivel += quantidade;
    }

    /** Efetivação: as unidades reservadas deixam o estoque (baixa do total). */
    void efetivarReserva(int quantidade) {
        exigirPositiva(quantidade);
        exigirSaldo(quantidade, quantidadeReservada, "reservada");
        quantidadeReservada -= quantidade;
        quantidadeTotal -= quantidade;
    }

    void bloquear(int quantidade) {
        exigirDisponivel(quantidade);
        quantidadeDisponivel -= quantidade;
        quantidadeBloqueada += quantidade;
    }

    void liberarBloqueio(int quantidade) {
        exigirPositiva(quantidade);
        exigirSaldo(quantidade, quantidadeBloqueada, "bloqueada");
        quantidadeBloqueada -= quantidade;
        quantidadeDisponivel += quantidade;
    }

    private void exigirDisponivel(int quantidade) {
        exigirPositiva(quantidade);
        if (quantidade > quantidadeDisponivel) {
            throw new EstoqueInsuficienteException(quantidade, quantidadeDisponivel);
        }
    }

    private static void exigirPositiva(int quantidade) {
        if (quantidade <= 0) {
            throw new RegraNegocioException("A quantidade deve ser maior que zero");
        }
    }

    /**
     * Proteção contra corrupção de dados: em operação normal nunca falha, pois a
     * quantidade de uma reserva/bloqueio ATIVO sempre está contida no saldo correspondente.
     */
    private static void exigirSaldo(int quantidade, int saldo, String tipo) {
        if (quantidade > saldo) {
            throw new IllegalStateException(
                    "Inconsistência: quantidade %s (%d) menor que a solicitada (%d)".formatted(tipo, saldo, quantidade));
        }
    }
}
