package com.example.controledeestoque.domain;

import com.example.controledeestoque.exception.RegraNegocioException;

/**
 * Normalização e validação dos campos de texto do domínio (sku, nome, marca, pedidoId, motivo).
 * <p>
 * As entidades chamam estes métodos <b>antes</b> de alterar qualquer saldo, então um texto
 * inválido nunca deixa o produto em estado intermediário. A API repete as mesmas regras com
 * Bean Validation (resposta 400 por campo) e o banco reforça as de preenchimento com CHECK constraints.
 */
public final class Textos {

    public static final String MENSAGEM_CARACTERE_DE_CONTROLE =
            "não pode conter caracteres de controle, como quebra de linha ou tabulação";

    private Textos() {
    }

    /**
     * O texto, sem os espaços das pontas, tem algum caractere de controle (quebra de linha, tabulação,
     * caractere nulo...)? Eles não fazem sentido em nomes e identificadores: quebram a exibição, permitem
     * forjar linhas de log e o PostgreSQL recusa o caractere nulo. Verificação linear, sem regex.
     */
    public static boolean contemCaractereDeControle(String valor) {
        return valor != null && valor.strip().chars().anyMatch(Character::isISOControl);
    }

    /** Obrigatório: rejeita nulo ou em branco, remove espaços nas pontas e limita o tamanho. */
    static String obrigatorio(String valor, String campo, int tamanhoMaximo) {
        if (valor == null || valor.isBlank()) {
            throw new RegraNegocioException("O campo %s é obrigatório".formatted(campo));
        }
        return validar(valor.strip(), campo, tamanhoMaximo);
    }

    /** Opcional: nulo ou em branco viram {@code null}; caso contrário, segue as regras do obrigatório. */
    static String opcional(String valor, String campo, int tamanhoMaximo) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return validar(valor.strip(), campo, tamanhoMaximo);
    }

    private static String validar(String valor, String campo, int tamanhoMaximo) {
        if (valor.length() > tamanhoMaximo) {
            throw new RegraNegocioException(
                    "O campo %s deve ter no máximo %d caracteres".formatted(campo, tamanhoMaximo));
        }
        if (contemCaractereDeControle(valor)) {
            throw new RegraNegocioException("O campo %s %s".formatted(campo, MENSAGEM_CARACTERE_DE_CONTROLE));
        }
        return valor;
    }
}
