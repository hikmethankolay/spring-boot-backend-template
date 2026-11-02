package com.hikmethankolay.template.common;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Every kind of validation failure gets the same problem: type, detail and an {@code errors} list. */
class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc = mockMvc();

    @Test
    void classLevelErrorsAreListedWithTheFieldErrors() throws Exception {
        mockMvc.perform(post("/ranges").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"","from":5,"to":1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:validation-failed"))
                .andExpect(jsonPath("$.detail").value("Request validation failed."))
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("name", "")))
                .andExpect(jsonPath("$.errors[?(@.field == '')].message").value("from must not be after to"));
    }

    @Test
    void invalidParametersGetTheSameProblemAsInvalidBodies() throws Exception {
        mockMvc.perform(get("/items").param("page_size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:validation-failed"))
                .andExpect(jsonPath("$.detail").value("Request validation failed."))
                .andExpect(jsonPath("$.errors[0].field").value("page_size"))
                .andExpect(jsonPath("$.errors[0].message").value("must be greater than or equal to 1"));
    }

    @Test
    void aBodyCheckedByMethodValidationListsItsErrorsToo() throws Exception {
        // A constrained parameter makes Spring validate the whole method, the @Valid body included.
        mockMvc.perform(post("/items")
                        .param("page_size", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"name":"a","from":5,"to":1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem:validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value(""))
                .andExpect(jsonPath("$.errors[0].message").value("from must not be after to"));
    }

    private static MockMvc mockMvc() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(new Probe())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @RestController
    static class Probe {

        @PostMapping("/ranges")
        void range(@Valid @RequestBody Range range) {
            // Only validated.
        }

        @GetMapping("/items")
        void items(@RequestParam("page_size") @Min(1) int pageSize) {
            // Only validated.
        }

        @PostMapping("/items")
        void addItem(@RequestParam("page_size") @Min(1) int pageSize, @Valid @RequestBody Range range) {
            // Only validated.
        }
    }

    @Ordered
    record Range(@NotBlank String name, int from, int to) {}

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = OrderedValidator.class)
    @interface Ordered {

        String message() default "from must not be after to";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static class OrderedValidator implements ConstraintValidator<Ordered, Range> {

        @Override
        public boolean isValid(Range range, ConstraintValidatorContext context) {
            return range == null || range.from() <= range.to();
        }
    }
}
