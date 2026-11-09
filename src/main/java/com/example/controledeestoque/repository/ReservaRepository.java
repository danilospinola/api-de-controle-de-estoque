package com.example.controledeestoque.repository;

import com.example.controledeestoque.domain.Reserva;
import com.example.controledeestoque.domain.StatusReserva;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReservaRepository extends JpaRepository<Reserva, UUID> {

    List<Reserva> findByProdutoIdOrderByDataCriacaoAsc(UUID produtoId);

    List<Reserva> findByProdutoIdAndStatusOrderByDataCriacaoAsc(UUID produtoId, StatusReserva status);

    /** Reservas de um pedido (usa o índice ix_reserva_pedido). */
    List<Reserva> findByPedidoIdOrderByDataCriacaoAsc(String pedidoId);

    List<Reserva> findByPedidoIdAndStatusOrderByDataCriacaoAsc(String pedidoId, StatusReserva status);
}
