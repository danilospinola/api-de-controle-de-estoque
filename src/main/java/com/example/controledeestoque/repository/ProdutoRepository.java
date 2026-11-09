package com.example.controledeestoque.repository;

import com.example.controledeestoque.domain.Produto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProdutoRepository extends JpaRepository<Produto, UUID> {

    /** Busca pela chave de negócio, já na forma canônica (maiúsculas). Usa o índice da constraint única. */
    Optional<Produto> findBySku(String sku);

    /** Vários produtos numa única consulta (reserva de pedido), também pelo índice da constraint única. */
    List<Produto> findBySkuIn(Collection<String> skus);
}
