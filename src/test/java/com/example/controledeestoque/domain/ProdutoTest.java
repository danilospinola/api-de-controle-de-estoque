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
        Produto produto = Produto.novo("SKU-1", NOME, null, 10);

        assertThat(produto.getNome()).isEqualTo(NOME);
        assertSaldo(produto, 10, 10, 0, 0);
    }

    @Test
    void naoPermiteQuantidadeInicialNegativa() {
        assertThatThrownBy(() -> Produto.novo("SKU-1", NOME, null, -1)).isInstanceOf(RegraNegocioException.class);
    }

    @Test
    void nomeEGuardadoSemEspacosNasPontas() {
        assertThat(Produto.novo("SKU-1", "  Teclado mecânico \t", null, 0).getNome()).isEqualTo("Teclado mecânico");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void naoPermiteNomeVazio(String nome) {
        assertThatThrownBy(() -> Produto.novo("SKU-1", nome, null, 10))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("nome");
    }

    @Test
    void nomeRespeitaOTamanhoMaximo() {
        String noLimite = "x".repeat(Produto.TAMANHO_MAXIMO_NOME);

        assertThat(Produto.novo("SKU-1", noLimite, null, 0).getNome()).hasSize(Produto.TAMANHO_MAXIMO_NOME);
        assertThatThrownBy(() -> Produto.novo("SKU-1", noLimite + "x", null, 0)).isInstanceOf(RegraNegocioException.class);
    }

    @Test
    void entradaAumentaTotalEDisponivel() {
        Produto produto = Produto.novo("SKU-1", NOME, null, 10);

        produto.entrada(5);

        assertSaldo(produto, 15, 15, 0, 0);
    }

    @Test
    void saidaReduzTotalEDisponivel() {
        Produto produto = Produto.novo("SKU-1", NOME, null, 10);

        produto.saida(4);

        assertSaldo(produto, 6, 6, 0, 0);
    }

    @Test
    void saidaPermiteZerarODisponivel() {
        Produto produto = Produto.novo("SKU-1", NOME, null, 10);

        produto.saida(10);

        assertSaldo(produto, 0, 0, 0, 0);
    }

    @Test
    @DisplayName("saída acima do disponível é rejeitada e não altera o saldo")
    void saidaAcimaDoDisponivelERejeitada() {
        Produto produto = Produto.novo("SKU-1", NOME, null, 10);

        assertThatThrownBy(() -> produto.saida(11)).isInstanceOf(EstoqueInsuficienteException.class);
        assertSaldo(produto, 10, 10, 0, 0);
    }

    @Test
    @DisplayName("saída não pode consumir unidades reservadas ou bloqueadas")
    void saidaNaoConsomeReservadoNemBloqueado() {
        Produto produto = Produto.novo("SKU-1", NOME, null, 10);
        Reserva.criar(produto, "PED-1", 4, AGORA);
        Bloqueio.criar(produto, "PED-2", "avaria", 3, AGORA);

        assertThatThrownBy(() -> produto.saida(4)).isInstanceOf(EstoqueInsuficienteException.class);
        assertSaldo(produto, 10, 3, 4, 3);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100})
    void quantidadesNaoPositivasSaoRejeitadasEmTodasAsOperacoes(int quantidade) {
        Produto produto = Produto.novo("SKU-1", NOME, null, 10);

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
        Produto produto = Produto.novo("SKU-1", NOME, null, Integer.MAX_VALUE);

        assertThatThrownBy(() -> produto.entrada(1))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("limite");
        assertSaldo(produto, Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0);
    }

    @Test
    void skuEGravadoEmMaiusculasSemEspacosEMarcaEOpcional() {
        Produto produto = Produto.novo("  tec-logi-k120 ", NOME, "  Logitech ", 0);

        assertThat(produto.getSku()).isEqualTo("TEC-LOGI-K120");
        assertThat(produto.getMarca()).isEqualTo("Logitech");
        assertThat(Produto.novo("SKU-2", NOME, "   ", 0).getMarca()).isNull();
        assertThat(Produto.novo("SKU-3", NOME, null, 0).getMarca()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "TEC 01", "TÉC-01", "-TEC", "TEC-", "TEC--01", "TEC_01", "TEC/01"})
    void skuForaDoFormatoERejeitado(String sku) {
        assertThatThrownBy(() -> Produto.novo(sku, NOME, null, 10))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("sku");
    }

    @Test
    void skuEMarcaRespeitamOTamanhoMaximo() {
        assertThat(Produto.novo("A".repeat(Produto.TAMANHO_MAXIMO_SKU), NOME, null, 0).getSku())
                .hasSize(Produto.TAMANHO_MAXIMO_SKU);
        assertThatThrownBy(() -> Produto.novo("A".repeat(Produto.TAMANHO_MAXIMO_SKU + 1), NOME, null, 0))
                .isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> Produto.novo("SKU-1", NOME, "m".repeat(Produto.TAMANHO_MAXIMO_MARCA + 1), 0))
                .isInstanceOf(RegraNegocioException.class);
    }

    @Test
    void normalizacaoDoSkuIgualaMaiusculasEMinusculas() {
        assertThat(Produto.normalizarSku(" tec-01 ")).isEqualTo(Produto.normalizarSku("TEC-01")).isEqualTo("TEC-01");
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
