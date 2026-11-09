package com.example.controledeestoque.service;

import com.example.controledeestoque.exception.ConflitoConcorrenciaException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Executa uma operação de escrita em transação própria, repetindo-a quando ocorre
 * conflito de versão (lock otimista no {@code Produto}).
 * <p>
 * Cada tentativa abre uma <b>nova</b> transação: o produto é relido com o saldo mais
 * recente e as regras de negócio são reavaliadas. Assim, uma reserva que perdeu a
 * disputa pode ter sucesso na nova tentativa ou falhar corretamente por estoque
 * insuficiente, mas nunca grava sobre um saldo desatualizado.
 */
@Slf4j
@Component
public class ExecutorTransacionalComRetry {

    private final TransactionTemplate transactionTemplate;
    private final int maxTentativas;
    private final long esperaBaseMs;

    public ExecutorTransacionalComRetry(PlatformTransactionManager transactionManager,
                                        @Value("${estoque.concorrencia.max-tentativas:5}") int maxTentativas,
                                        @Value("${estoque.concorrencia.espera-base-ms:20}") long esperaBaseMs) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxTentativas = maxTentativas;
        this.esperaBaseMs = esperaBaseMs;
    }

    public <T> T executar(Supplier<T> operacao) {
        for (int tentativa = 1; ; tentativa++) {
            try {
                return transactionTemplate.execute(status -> operacao.get());
            } catch (OptimisticLockingFailureException e) {
                if (tentativa >= maxTentativas) {
                    log.warn("Conflito de concorrência não resolvido após {} tentativas", tentativa);
                    throw new ConflitoConcorrenciaException(
                            "O estoque foi alterado concorrentemente. Tente novamente.", e);
                }
                log.debug("Conflito de versão na tentativa {}/{}; repetindo", tentativa, maxTentativas);
                aguardar(tentativa);
            }
        }
    }

    private void aguardar(int tentativa) {
        // Backoff linear com jitter para que as transações concorrentes não colidam de novo em sincronia.
        long espera = esperaBaseMs * tentativa + ThreadLocalRandom.current().nextLong(esperaBaseMs + 1);
        try {
            Thread.sleep(espera);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConflitoConcorrenciaException("Operação interrompida durante nova tentativa", e);
        }
    }
}
