package com.example.controledeestoque.exception;

import java.util.UUID;

/**
 * Cadastro de um SKU que já existe. Responde 409: o produto já está cadastrado e novas unidades
 * devem entrar pela entrada de estoque, não por um novo cadastro.
 */
public class SkuDuplicadoException extends RuntimeException {

    private final UUID produtoExistente;

    /**
     * @param produtoExistente id do produto que já tem o SKU; pode ser nulo quando a duplicidade foi
     *                         detectada pela constraint do banco em cadastros simultâneos
     */
    public SkuDuplicadoException(String sku, UUID produtoExistente) {
        super(produtoExistente == null
                ? "O SKU %s já está cadastrado. Para acrescentar unidades, use a entrada de estoque do produto."
                        .formatted(sku)
                : "O SKU %s já está cadastrado no produto %s. Para acrescentar unidades, use a entrada de estoque desse produto."
                        .formatted(sku, produtoExistente));
        this.produtoExistente = produtoExistente;
    }

    public UUID getProdutoExistente() {
        return produtoExistente;
    }
}
