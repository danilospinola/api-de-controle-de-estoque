package com.example.controledeestoque.service;

import com.example.controledeestoque.exception.ConflitoConcorrenciaException;
import com.example.controledeestoque.exception.EstoqueInsuficienteException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutorTransacionalComRetryTest {

    private final ExecutorTransacionalComRetry executor =
            new ExecutorTransacionalComRetry(new TransactionManagerFalso(), 3, 0);

    @Test
    void repeteQuandoHaConflitoDeVersaoEConcluiQuandoDeixaDeHaver() {
        AtomicInteger chamadas = new AtomicInteger();

        String resultado = executor.executar(() -> {
            if (chamadas.incrementAndGet() < 3) {
                throw new OptimisticLockingFailureException("versão alterada");
            }
            return "ok";
        });

        assertThat(resultado).isEqualTo("ok");
        assertThat(chamadas).hasValue(3);
    }

    @Test
    void desisteAposOMaximoDeTentativas() {
        AtomicInteger chamadas = new AtomicInteger();

        assertThatThrownBy(() -> executor.executar(() -> {
            chamadas.incrementAndGet();
            throw new OptimisticLockingFailureException("versão alterada");
        })).isInstanceOf(ConflitoConcorrenciaException.class);

        assertThat(chamadas).hasValue(3);
    }

    @Test
    void naoRepeteErrosDeRegraDeNegocio() {
        AtomicInteger chamadas = new AtomicInteger();

        assertThatThrownBy(() -> executor.executar(() -> {
            chamadas.incrementAndGet();
            throw new EstoqueInsuficienteException(5, 1);
        })).isInstanceOf(EstoqueInsuficienteException.class);

        assertThat(chamadas).hasValue(1);
    }

    /** Gerenciador de transação mínimo: o foco do teste é a política de retry. */
    private static class TransactionManagerFalso implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
