package com.gra.paradise.botattendance.discord.commands;

import com.gra.paradise.botattendance.model.Schedule;
import com.gra.paradise.botattendance.service.ScheduleService;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.spec.EmbedCreateSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ListActiveSchedulesCommand implements Command {

    private final ScheduleService scheduleService;

    @Override
    public String getName() {
        return "escalas-ativas";
    }

    @Override
    public Mono<Void> handle(ChatInputInteractionEvent event) {
        log.info("Comando escalas-ativas recebido de {}", event.getInteraction().getUser().getUsername());

        return event.deferReply()
                .doOnSuccess(v -> log.info("Resposta deferida com sucesso"))
                .doOnError(e -> log.error("Erro ao deferir resposta: {}", e.getMessage()))
                .then(Mono.fromCallable(() -> {
                    log.info("Buscando escalas ativas no banco de dados");
                    return scheduleService.getActiveSchedulesWithDetails();
                }))
                .flatMap(schedules -> {
                    log.info("Encontradas {} escalas ativas", schedules.size());

                    if (schedules.isEmpty()) {
                        log.info("Nenhuma escala ativa encontrada");
                        return event.createFollowup("Não há escalas ativas no momento.");
                    } else {
                        EmbedCreateSpec.Builder embedBuilder = EmbedCreateSpec.builder()
                                .title("Escalas de Voo Ativas")
                                .description("Total de escalas ativas: " + schedules.size())
                                .timestamp(Instant.now());

                        for (Schedule schedule : schedules) {
                            StringBuilder description = new StringBuilder();
                            description.append("**Aeronave:** ").append(schedule.getAircraftType().getDisplayName()).append("\n");
                            description.append("**Criado por:** ").append(schedule.getCreatedByUsername()).append("\n");
                            description.append("**Tripulantes:** ").append(schedule.getCrewMembersCount());

                            embedBuilder.addField("Escala #" + schedule.getId() + ": " + schedule.getTitle(),
                                    description.toString(), false);
                        }

                        log.info("Enviando embed com lista de escalas");
                        return event.createFollowup()
                                .withEmbeds(embedBuilder.build());
                    }
                })
                .doOnError(e -> log.error("Erro ao listar escalas: {}", e.getMessage()))
                .onErrorResume(e ->
                        // Remova o .then() daqui para manter o Mono<Message>
                        event.createFollowup("Ocorreu um erro ao listar as escalas: " + e.getMessage())
                                .withEphemeral(true)
                )
                .then(); // Use o .then() aqui no final para converter para Mono<Void>
    }
}