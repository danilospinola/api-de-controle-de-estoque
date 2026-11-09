package com.example.controledeestoque.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Contrato OpenAPI com servidor relativo ("/"): o Swagger UI chama a API na mesma origem em que foi
 * aberto. Sem isso, atrás do CloudFront o springdoc anunciaria o endereço interno da EC2 (http://...),
 * que o navegador bloquearia (conteúdo misto em página HTTPS) e o security group recusaria.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Controle de Estoque")
                        .version("v1")
                        .description("Saldos de estoque com entradas, saídas, reservas e bloqueios."))
                .servers(List.of(new Server().url("/")));
    }
}
