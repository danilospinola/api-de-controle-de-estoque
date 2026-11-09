package com.example.controledeestoque.domain;

import com.example.controledeestoque.exception.EstoqueInsuficienteException;
import com.example.controledeestoque.exception.RegraNegocioException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

import static com.example.controledeestoque.domain.ProdutoTest.assertSaldo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ciclo de vida de reservas e bloqueios e seu efeito no saldo do produto. */
class ReservaBloqueioTest {

    private static final LocalDateTime AGORA = LocalDateTime.of(2026, 11, 7, 10, 0);

    @Nested
    class Reservas {

        @Test
        void reservarMoveDoDisponivelParaReservado() {
            Produto produto = Produto.novo("Produto teste", 10);

            Reserva reserva = Reserva.criar(produto, "PED-1", 3, AGORA);

            assertThat(reserva.getStatus()).isEqualTo(StatusReserva.ATIVA);
            assertThat(reserva.getPedidoId()).isEqualTo("PED-1");
            assertThat(reserva.getDataCriacao()).isEqualTo(AGORA);
            assertSaldo(produto, 10, 7, 3, 0);
        }

        @Test
        void reservaAcimaDoDisponivelERejeitadaSemAlterarSaldo() {
            Produto produto = Produto.novo("Produto teste", 10);
            Bloqueio.criar(produto, "PED-0", null, 8, AGORA);

            assertThatThrownBy(() -> Reserva.criar(produto, "PED-1", 3, AGORA))
                    .isInstanceOf(EstoqueInsuficienteException.class);
            assertSaldo(produto, 10, 2, 0, 8);
        }

        @Test
        void variasReservasSimultaneasDePedidosDiferentes() {
            Produto produto = Produto.novo("Produto teste", 10);

            Reserva.criar(produto, "PED-1", 3, AGORA);
            Reserva.criar(produto, "PED-2", 4, AGORA);
            Reserva.criar(produto, "PED-1", 1, AGORA);

            assertSaldo(produto, 10, 2, 8, 0);
        }

        @Test
        void liberarDevolveAoDisponivel() {
            Produto produto = Produto.novo("Produto teste", 10);
            Reserva reserva = Reserva.criar(produto, "PED-1", 3, AGORA);

            reserva.liberar();

            assertThat(reserva.getStatus()).isEqualTo(StatusReserva.LIBERADA);
            assertSaldo(produto, 10, 10, 0, 0);
        }

        @Test
        void efetivarBaixaDoTotal() {
            Produto produto = Produto.novo("Produto teste", 10);
            Reserva reserva = Reserva.criar(produto, "PED-1", 3, AGORA);

            reserva.efetivar();

            assertThat(reserva.getStatus()).isEqualTo(StatusReserva.EFETIVADA);
            assertSaldo(produto, 7, 7, 0, 0);
        }

        @Test
        void reservaLiberadaNaoPodeSerLiberadaNemEfetivadaNovamente() {
            Produto produto = Produto.novo("Produto teste", 10);
            Reserva reserva = Reserva.criar(produto, "PED-1", 3, AGORA);
            reserva.liberar();

            assertThatThrownBy(reserva::liberar).isInstanceOf(RegraNegocioException.class);
            assertThatThrownBy(reserva::efetivar).isInstanceOf(RegraNegocioException.class);
            assertSaldo(produto, 10, 10, 0, 0);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        void pedidoIdObrigatorioERejeitadoSemAlterarSaldo(String pedidoId) {
            Produto produto = Produto.novo("Produto teste", 10);

            assertThatThrownBy(() -> Reserva.criar(produto, pedidoId, 3, AGORA))
                    .isInstanceOf(RegraNegocioException.class)
                    .hasMessageContaining("pedidoId");
            assertSaldo(produto, 10, 10, 0, 0);
        }

        @Test
        void pedidoIdEGravadoSemEspacosERespeitaOTamanhoMaximo() {
            Produto produto = Produto.novo("Produto teste", 10);

            assertThat(Reserva.criar(produto, "  PED-1 ", 1, AGORA).getPedidoId()).isEqualTo("PED-1");
            String longo = "P".repeat(Reserva.TAMANHO_MAXIMO_PEDIDO + 1);
            assertThatThrownBy(() -> Reserva.criar(produto, longo, 1, AGORA)).isInstanceOf(RegraNegocioException.class);
            assertSaldo(produto, 10, 9, 1, 0);
        }

        @Test
        void reservaEfetivadaNaoPodeSerLiberada() {
            Produto produto = Produto.novo("Produto teste", 10);
            Reserva reserva = Reserva.criar(produto, "PED-1", 3, AGORA);
            reserva.efetivar();

            assertThatThrownBy(reserva::liberar).isInstanceOf(RegraNegocioException.class);
            assertSaldo(produto, 7, 7, 0, 0);
        }
    }

    @Nested
    class Bloqueios {

        @Test
        void bloquearMoveDoDisponivelParaBloqueado() {
            Produto produto = Produto.novo("Produto teste", 10);

            Bloqueio bloqueio = Bloqueio.criar(produto, "PED-1", "avaria", 4, AGORA);

            assertThat(bloqueio.getStatus()).isEqualTo(StatusBloqueio.ATIVO);
            assertThat(bloqueio.getMotivo()).isEqualTo("avaria");
            assertSaldo(produto, 10, 6, 0, 4);
        }

        @Test
        void bloqueioAcimaDoDisponivelERejeitadoSemAlterarSaldo() {
            Produto produto = Produto.novo("Produto teste", 10);
            Reserva.criar(produto, "PED-0", 7, AGORA);

            assertThatThrownBy(() -> Bloqueio.criar(produto, "PED-1", null, 4, AGORA))
                    .isInstanceOf(EstoqueInsuficienteException.class);
            assertSaldo(produto, 10, 3, 7, 0);
        }

        @Test
        void liberarDevolveAoDisponivel() {
            Produto produto = Produto.novo("Produto teste", 10);
            Bloqueio bloqueio = Bloqueio.criar(produto, "PED-1", null, 4, AGORA);

            bloqueio.liberar();

            assertThat(bloqueio.getStatus()).isEqualTo(StatusBloqueio.LIBERADO);
            assertSaldo(produto, 10, 10, 0, 0);
        }

        @Test
        void pedidoIdObrigatorioEMotivoOpcionalNormalizado() {
            Produto produto = Produto.novo("Produto teste", 10);

            assertThatThrownBy(() -> Bloqueio.criar(produto, " ", "avaria", 2, AGORA))
                    .isInstanceOf(RegraNegocioException.class);
            assertSaldo(produto, 10, 10, 0, 0);

            Bloqueio semMotivo = Bloqueio.criar(produto, "PED-1", "   ", 2, AGORA);
            Bloqueio comMotivo = Bloqueio.criar(produto, " PED-2 ", "  avaria ", 1, AGORA);
            assertThat(semMotivo.getMotivo()).isNull();
            assertThat(comMotivo.getPedidoId()).isEqualTo("PED-2");
            assertThat(comMotivo.getMotivo()).isEqualTo("avaria");

            String motivoLongo = "m".repeat(Bloqueio.TAMANHO_MAXIMO_MOTIVO + 1);
            assertThatThrownBy(() -> Bloqueio.criar(produto, "PED-3", motivoLongo, 1, AGORA))
                    .isInstanceOf(RegraNegocioException.class);
            assertSaldo(produto, 10, 7, 0, 3);
        }

        @Test
        void bloqueioLiberadoNaoPodeSerLiberadoNovamente() {
            Produto produto = Produto.novo("Produto teste", 10);
            Bloqueio bloqueio = Bloqueio.criar(produto, "PED-1", null, 4, AGORA);
            bloqueio.liberar();

            assertThatThrownBy(bloqueio::liberar).isInstanceOf(RegraNegocioException.class);
            assertSaldo(produto, 10, 10, 0, 0);
        }
    }
}
