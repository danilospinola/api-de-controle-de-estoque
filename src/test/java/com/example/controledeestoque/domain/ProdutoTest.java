package com.example.controledeestoque.domain;

import com.example.controledeestoque.exception.EstoqueInsuficienteException;
import com.example.controledeestoque.exception.RegraNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Testes unitários das regras de saldo do produto (sem Spring, sem banco). */
class ProdutoTest {

    private static final LocalDateTime AGORA = LocalDateTime.of(2026, 11, 7, 10, 0);
    private static final String NOME = "Teclado mecânico";

    @Test
    void novoProdutoComecaComTudoDisponivel() {
        Produto produto = Produto.novo(NOME, 10);

        assertThat(produto.getNome()).isEqualTo(NOME);
        assertSaldo(produto, 10, 10, 0, 0);
    }

    @Test
    void naoPermiteQuantidadeInicialNegativa() {
        assertThatThrownBy(() -> Produto.novo(NOME, -1)).isInstanceOf(RegraNegocioException.class);
    }

    @Test
    void nomeEGuardadoSemEspacosNasPontas() {
        assertThat(Produto.novo("  Teclado mecânico \t", 0).getNome()).isEqualTo("Teclado mecânico");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void naoPermiteNomeVazio(String nome) {
        assertThatThrownBy(() -> Produto.novo(nome, 10))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("nome");
    }

    @Test
    void nomeRespeitaOTamanhoMaximo() {
        String noLimite = "x".repeat(Produto.TAMANHO_MAXIMO_NOME);

        assertThat(Produto.novo(noLimite, 0).getNome()).hasSize(Produto.TAMANHO_MAXIMO_NOME);
        assertThatThrownBy(() -> Produto.novo(noLimite + "x", 0)).isInstanceOf(RegraNegocioException.class);
    }

    @Test
    void entradaAumentaTotalEDisponivel() {
        Produto produto = Produto.novo(NOME, 10);

        produto.entrada(5);

        assertSaldo(produto, 15, 15, 0, 0);
    }

    @Test
    void saidaReduzTotalEDisponivel() {
        Produto produto = Produto.novo(NOME, 10);

        produto.saida(4);

        assertSaldo(produto, 6, 6, 0, 0);
    }

    @Test
    void saidaPermiteZerarODisponivel() {
        Produto produto = Produto.novo(NOME, 10);

        produto.saida(10);

        assertSaldo(produto, 0, 0, 0, 0);
    }

    @Test
    @DisplayName("saída acima do disponível é rejeitada e não altera o saldo")
    void saidaAcimaDoDisponivelERejeitada() {
        Produto produto = Produto.novo(NOME, 10);

        assertThatThrownBy(() -> produto.saida(11)).isInstanceOf(EstoqueInsuficienteException.class);
        assertSaldo(produto, 10, 10, 0, 0);
    }

    @Test
    @DisplayName("saída não pode consumir unidades reservadas ou bloqueadas")
    void saidaNaoConsomeReservadoNemBloqueado() {
        Produto produto = Produto.novo(NOME, 10);
        Reserva.criar(produto, "PED-1", 4, AGORA);
        Bloqueio.criar(produto, "PED-2", "avaria", 3, AGORA);

        assertThatThrownBy(() -> produto.saida(4)).isInstanceOf(EstoqueInsuficienteException.class);
        assertSaldo(produto, 10, 3, 4, 3);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100})
    void quantidadesNaoPositivasSaoRejeitadasEmTodasAsOperacoes(int quantidade) {
        Produto produto = Produto.novo(NOME, 10);

        assertThatThrownBy(() -> produto.entrada(quantidade)).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> produto.saida(quantidade)).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> Reserva.criar(produto, "PED-1", quantidade, AGORA))
                .isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> Bloqueio.criar(produto, "PED-1", null, quantidade, AGORA))
                .isInstanceOf(RegraNegocioException.class);
        assertSaldo(produto, 10, 10, 0, 0);
    }

    @Test
    void entradaQueEstourariaOLimiteDeIntegerERejeitada() {
        Produto produto = Produto.novo(NOME, Integer.MAX_VALUE);

        assertThatThrownBy(() -> produto.entrada(1))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("limite");
        assertSaldo(produto, Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0);
    }

    static void assertSaldo(Produto produto, int total, int disponivel, int reservada, int bloqueada) {
        assertThat(produto.getQuantidadeTotal()).as("total").isEqualTo(total);
        assertThat(produto.getQuantidadeDisponivel()).as("disponível").isEqualTo(disponivel);
        assertThat(produto.getQuantidadeReservada()).as("reservada").isEqualTo(reservada);
        assertThat(produto.getQuantidadeBloqueada()).as("bloqueada").isEqualTo(bloqueada);
        assertThat(produto.getQuantidadeDisponivel() + produto.getQuantidadeReservada() + produto.getQuantidadeBloqueada())
                .as("invariante total = disponível + reservada + bloqueada")
                .isEqualTo(produto.getQuantidadeTotal());
    }
}
