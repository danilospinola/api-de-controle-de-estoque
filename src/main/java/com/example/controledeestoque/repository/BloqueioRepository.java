package com.example.controledeestoque.repository;

import com.example.controledeestoque.domain.Bloqueio;
import com.example.controledeestoque.domain.StatusBloqueio;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Consultas já trazem o produto junto, pelo mesmo motivo de {@link ReservaRepository}. */
public interface BloqueioRepository extends JpaRepository<Bloqueio, UUID> {

    @Override
    @EntityGraph(attributePaths = "produto")
    Optional<Bloqueio> findById(UUID id);

    @EntityGraph(attributePaths = "produto")
    List<Bloqueio> findByProdutoIdOrderByDataCriacaoAsc(UUID produtoId);

    @EntityGraph(attributePaths = "produto")
    List<Bloqueio> findByProdutoIdAndStatusOrderByDataCriacaoAsc(UUID produtoId, StatusBloqueio status);
}
