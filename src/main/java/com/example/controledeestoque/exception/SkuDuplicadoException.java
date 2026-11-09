package com.example.controledeestoque.exception;

/**
 * Cadastro de um SKU que já existe. Responde 409: o produto já está cadastrado e novas unidades
 * devem entrar pela entrada de estoque desse SKU, não por um novo cadastro.
 */
public class SkuDuplicadoException extends RuntimeException {

    public SkuDuplicadoException(String sku) {
        super("O SKU %s já está cadastrado. Para acrescentar unidades, use a entrada de estoque: POST /api/produtos/%s/entradas."
                .formatted(sku, sku));
    }
}
