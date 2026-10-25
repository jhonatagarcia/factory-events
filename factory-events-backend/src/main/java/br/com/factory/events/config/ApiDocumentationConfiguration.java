package br.com.factory.events.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApiDocumentationConfiguration {
    @Bean
    public OpenAPI eventsOpenApi() {
        return new OpenAPI().info(new Info().title("Eventos da fábrica")
                .version("1.0.0")
                .description("Recebimento de eventos externos e cadastro manual. QUALITY gera auditoria na mesma transação. "
                        + "Reenvios externos retornam 409. Reenvios manuais com o mesmo requestId e conteúdo retornam o evento original. "
                        + "Erros seguem ProblemDetail e incluem code e correlationId. Datas exigem offset."));
    }
}
