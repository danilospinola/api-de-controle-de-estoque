package com.example.controledeestoque.repository;

import com.example.controledeestoque.domain.Bloqueio;
import com.example.controledeestoque.domain.StatusBloqueio;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BloqueioRepository extends JpaRepository<Bloqueio, UUID> {

    List<Bloqueio> findByProdutoIdOrderByDataCriacaoAsc(UUID produtoId);

    List<Bloqueio> findByProdutoIdAndStatusOrderByDataCriacaoAsc(UUID produtoId, StatusBloqueio status);
}
