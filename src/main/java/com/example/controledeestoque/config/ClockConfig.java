package com.example.controledeestoque.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** Relógio injetável: datas de criação em UTC e controláveis nos testes. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
