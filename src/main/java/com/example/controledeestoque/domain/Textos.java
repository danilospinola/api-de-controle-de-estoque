package com.example.controledeestoque.domain;

import com.example.controledeestoque.exception.RegraNegocioException;

/**
 * Normalização e validação dos campos de texto do domínio (nome, pedidoId, motivo).
 * <p>
 * As entidades chamam estes métodos <b>antes</b> de alterar qualquer saldo, então um texto
 * inválido nunca deixa o produto em estado intermediário. A API repete as mesmas regras com
 * Bean Validation (resposta 400 por campo) e o banco as reforça com CHECK constraints.
 */
final class Textos {

    private Textos() {
    }

    /** Obrigatório: rejeita nulo ou em branco, remove espaços nas pontas e limita o tamanho. */
    static String obrigatorio(String valor, String campo, int tamanhoMaximo) {
        if (valor == null || valor.isBlank()) {
            throw new RegraNegocioException("O campo %s é obrigatório".formatted(campo));
        }
        return limitar(valor.strip(), campo, tamanhoMaximo);
    }

    /** Opcional: nulo ou em branco viram {@code null}; caso contrário, segue as regras do obrigatório. */
    static String opcional(String valor, String campo, int tamanhoMaximo) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return limitar(valor.strip(), campo, tamanhoMaximo);
    }

    private static String limitar(String valor, String campo, int tamanhoMaximo) {
        if (valor.length() > tamanhoMaximo) {
            throw new RegraNegocioException(
                    "O campo %s deve ter no máximo %d caracteres".formatted(campo, tamanhoMaximo));
        }
        return valor;
    }
}
