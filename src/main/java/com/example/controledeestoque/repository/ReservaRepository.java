package com.example.controledeestoque.repository;

import com.example.controledeestoque.domain.Reserva;
import com.example.controledeestoque.domain.StatusReserva;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Todas as consultas já trazem o produto junto ({@code @EntityGraph}): a resposta da API mostra o SKU
 * do produto e é montada fora da transação ({@code open-in-view=false}), onde o lazy loading falharia.
 * <p>
 * A ordem é sempre determinística: por data de criação e, no empate (os itens de um mesmo pedido são
 * criados no mesmo instante), pelo SKU e pelo id.
 */
public interface ReservaRepository extends JpaRepository<Reserva, UUID> {

    @Override
    @EntityGraph(attributePaths = "produto")
    Optional<Reserva> findById(UUID id);

    @EntityGraph(attributePaths = "produto")
    List<Reserva> findByProdutoIdOrderByDataCriacaoAscIdAsc(UUID produtoId);

    @EntityGraph(attributePaths = "produto")
    List<Reserva> findByProdutoIdAndStatusOrderByDataCriacaoAscIdAsc(UUID produtoId, StatusReserva status);

    /** Reservas de um pedido (usa o índice ix_reserva_pedido). */
    @EntityGraph(attributePaths = "produto")
    List<Reserva> findByPedidoIdOrderByDataCriacaoAscProdutoSkuAscIdAsc(String pedidoId);

    @EntityGraph(attributePaths = "produto")
    List<Reserva> findByPedidoIdAndStatusOrderByDataCriacaoAscProdutoSkuAscIdAsc(String pedidoId, StatusReserva status);
}
