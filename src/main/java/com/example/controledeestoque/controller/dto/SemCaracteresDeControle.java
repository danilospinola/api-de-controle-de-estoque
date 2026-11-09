package com.example.controledeestoque.controller.dto;

import com.example.controledeestoque.domain.Textos;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * O texto não pode ter caracteres de controle (quebra de linha, tabulação, caractere nulo...) além dos
 * espaços das pontas, que o domínio remove. Mesma regra de {@link Textos#contemCaractereDeControle}, aqui
 * para responder 400 com o campo. Validador próprio em vez de {@code @Pattern}: uma regex que aceite
 * espaços nas pontas fica ambígua e, num corpo grande, pode levar tempo exponencial (ReDoS).
 */
@Documented
@Constraint(validatedBy = SemCaracteresDeControle.Validador.class)
@Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
public @interface SemCaracteresDeControle {

    String message() default Textos.MENSAGEM_CARACTERE_DE_CONTROLE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validador implements ConstraintValidator<SemCaracteresDeControle, String> {

        @Override
        public boolean isValid(String valor, ConstraintValidatorContext contexto) {
            return !Textos.contemCaractereDeControle(valor);
        }
    }
}
