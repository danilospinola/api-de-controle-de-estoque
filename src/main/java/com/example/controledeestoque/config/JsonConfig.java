package com.example.controledeestoque.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Complementa o contrato JSON estrito do {@code application.yaml} (campos desconhecidos, decimais e
 * chaves duplicadas recusados) com o que não tem propriedade própria no Spring Boot.
 */
@Configuration
public class JsonConfig {

    /** Texto em campo inteiro é recusado: {@code "quantidade": "10"} responde 400 em vez de virar 10. */
    @Bean
    JsonMapperBuilderCustomizer inteirosSemConversaoDeTexto() {
        return builder -> builder.withCoercionConfig(LogicalType.Integer,
                config -> config.setCoercion(CoercionInputShape.String, CoercionAction.Fail));
    }
}
