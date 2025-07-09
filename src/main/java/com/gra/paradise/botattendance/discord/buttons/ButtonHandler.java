package com.gra.paradise.botattendance.discord.buttons;

import com.gra.paradise.botattendance.model.Schedule;
import com.gra.paradise.botattendance.model.ScheduleLog;
import com.gra.paradise.botattendance.model.User;
import com.gra.paradise.botattendance.service.ScheduleService;
import discord4j.core.event.domain.interaction.ButtonInteractionEvent;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.Button;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.core.spec.InteractionCallbackSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ButtonHandler {

    private final ScheduleService scheduleService;

    public Mono<Void> handleButtonClick(ButtonInteractionEvent event) {
        String customId = event.getCustomId();
        log.debug("Button clicked: {}", customId);

        if (customId.startsWith("join:")) {
            return handleJoin(event, customId);
        } else if (customId.startsWith("leave:")) {
            return handleLeave(event, customId);
        } else if (customId.startsWith("close:")) {
            return handleClose(event, customId);
        }

        return Mono.empty();
    }

    private Mono<Void> handleJoin(ButtonInteractionEvent event, String customId) {
        Long scheduleId = Long.parseLong(customId.substring(5));
        discord4j.core.object.entity.User discordUser = event.getInteraction().getUser();
        String userId = discordUser.getId().asString();
        String username = discordUser.getUsername();

        return event.deferReply(InteractionCallbackSpec.builder()
                        .ephemeral(true)
                        .build())
                .then(Mono.justOrEmpty(event.getInteraction().getMember())
                        .flatMap(member -> {
                            // Obtém o apelido do servidor ou usa o nome de usuário se não tiver
                            String nickname = member.getNickname().orElse(username);
                            log.info("Usuário embarcando: {} com apelido: {}", username, nickname);

                            return Mono.fromCallable(() -> {
                                Schedule updatedSchedule = scheduleService.addCrewMemberAndInitialize(
                                        scheduleId,
                                        userId,
                                        username,
                                        nickname  // Usa o apelido do servidor
                                );
                                return updatedSchedule;
                            });
                        })
                        .switchIfEmpty(Mono.fromCallable(() -> {
                            // Fallback se não conseguir obter o Member
                            log.info("Usando username padrão para usuário: {}", username);
                            return scheduleService.addCrewMemberAndInitialize(
                                    scheduleId,
                                    userId,
                                    username,
                                    username  // Sem apelido, usa username
                            );
                        }))
                        .onErrorResume(e -> {
                            log.error("Erro ao adicionar membro: {}", e.getMessage());
                            return Mono.error(e);
                        }))
                .flatMap(updatedSchedule -> {
                    EmbedCreateSpec embed = createScheduleEmbed(updatedSchedule);

                    // Criar botões para ações
                    Button joinButton = Button.success("join:" + updatedSchedule.getId(), "Embarcar")
                            .disabled(!updatedSchedule.isActive());
                    Button leaveButton = Button.danger("leave:" + updatedSchedule.getId(), "Desembarcar")
                            .disabled(!updatedSchedule.isActive());
                    Button closeButton = Button.secondary("close:" + updatedSchedule.getId(), "Encerrar Escala")
                            .disabled(!updatedSchedule.isActive());

                    // Criar a resposta ao usuário e atualizar a mensagem
                    return event.createFollowup("Você embarcou na aeronave com sucesso!")
                            .withEphemeral(true)
                            .then(
                                    Mono.justOrEmpty(event.getMessage())
                                            .flatMap(message -> message.edit()
                                                    .withEmbeds(embed)
                                                    .withComponents(ActionRow.of(joinButton, leaveButton, closeButton)))
                                            .then()
                            );
                })
                .onErrorResume(e -> {
                    log.error("Erro ao processar botão join", e);
                    return event.createFollowup("Erro: " + e.getMessage()).withEphemeral(true).then();
                });
    }

    private Mono<Void> handleLeave(ButtonInteractionEvent event, String customId) {
        Long scheduleId = Long.parseLong(customId.substring(6));
        discord4j.core.object.entity.User discordUser = event.getInteraction().getUser();
        String userId = discordUser.getId().asString();
        String username = discordUser.getUsername();

        return event.deferReply(InteractionCallbackSpec.builder()
                        .ephemeral(true)
                        .build())
                .then(Mono.justOrEmpty(event.getInteraction().getMember())
                        .flatMap(member -> {
                            // Obtém o apelido do servidor ou usa o nome de usuário se não tiver
                            String nickname = member.getNickname().orElse(username);
                            log.info("Usuário desembarcando: {} com apelido: {}", username, nickname);

                            return Mono.fromCallable(() -> {
                                Schedule updatedSchedule = scheduleService.removeCrewMemberAndInitialize(
                                        scheduleId,
                                        userId,
                                        nickname  // Usar o apelido em vez do username
                                );
                                return updatedSchedule;
                            });
                        })
                        .switchIfEmpty(Mono.fromCallable(() -> {
                            // Fallback se não conseguir obter o Member
                            log.info("Usando username padrão para usuário desembarcando: {}", username);
                            return scheduleService.removeCrewMemberAndInitialize(
                                    scheduleId,
                                    userId,
                                    username  // Sem apelido, usa username
                            );
                        })))
                .flatMap(updatedSchedule -> {
                    EmbedCreateSpec embed = createScheduleEmbed(updatedSchedule);

                    Button joinButton = Button.success("join:" + updatedSchedule.getId(), "Embarcar")
                            .disabled(!updatedSchedule.isActive());
                    Button leaveButton = Button.danger("leave:" + updatedSchedule.getId(), "Desembarcar")
                            .disabled(!updatedSchedule.isActive());
                    Button closeButton = Button.secondary("close:" + updatedSchedule.getId(), "Encerrar Escala")
                            .disabled(!updatedSchedule.isActive());

                    return event.createFollowup("Você desembarcou da aeronave com sucesso!")
                            .withEphemeral(true)
                            .then(
                                    Mono.justOrEmpty(event.getMessage())
                                            .flatMap(message -> message.edit()
                                                    .withEmbeds(embed)
                                                    .withComponents(ActionRow.of(joinButton, leaveButton, closeButton)))
                                            .then()
                            );
                })
                .onErrorResume(e -> {
                    log.error("Erro ao processar botão leave", e);
                    return event.createFollowup("Erro: " + e.getMessage()).withEphemeral(true).then();
                });
    }

    private Mono<Void> handleClose(ButtonInteractionEvent event, String customId) {
        Long scheduleId = Long.parseLong(customId.substring(6));
        discord4j.core.object.entity.User discordUser = event.getInteraction().getUser();
        String userId = discordUser.getId().asString();
        String username = discordUser.getUsername();

        return event.deferReply(InteractionCallbackSpec.builder()
                        .ephemeral(true)
                        .build())
                .then(Mono.justOrEmpty(event.getInteraction().getMember())
                        .flatMap(member -> {
                            // Obtém o apelido do servidor ou usa o nome de usuário se não tiver
                            String nickname = member.getNickname().orElse(username);
                            log.info("Usuário encerrando escala: {} com apelido: {}", username, nickname);

                            return Mono.fromCallable(() ->
                                    scheduleService.closeScheduleAndInitialize(
                                            scheduleId,
                                            userId,
                                            nickname  // Usa o apelido do servidor
                                    )
                            );
                        })
                        .switchIfEmpty(Mono.fromCallable(() -> {
                            // Fallback se não conseguir obter o Member
                            log.info("Usando username padrão para usuário: {}", username);
                            return scheduleService.closeScheduleAndInitialize(
                                    scheduleId,
                                    userId,
                                    username
                            );
                        })))
                .flatMap(updatedSchedule -> {
                    EmbedCreateSpec embed = createScheduleEmbed(updatedSchedule);

                    Button joinButton = Button.success("join:" + updatedSchedule.getId(), "Embarcar")
                            .disabled(true);
                    Button leaveButton = Button.danger("leave:" + updatedSchedule.getId(), "Desembarcar")
                            .disabled(true);
                    Button closeButton = Button.secondary("close:" + updatedSchedule.getId(), "Encerrar Escala")
                            .disabled(true);

                    return event.createFollowup("Escala encerrada com sucesso!")
                            .withEphemeral(true)
                            .then(
                                    Mono.justOrEmpty(event.getMessage())
                                            .flatMap(message -> message.edit()
                                                    .withEmbeds(embed)
                                                    .withComponents(ActionRow.of(joinButton, leaveButton, closeButton)))
                                            .then()
                            );
                })
                .onErrorResume(e -> {
                    log.error("Erro ao processar botão close", e);
                    return event.createFollowup("Erro: " + e.getMessage()).withEphemeral(true).then();
                });
    }

    private EmbedCreateSpec createScheduleEmbed(Schedule schedule) {
        // Usamos o método getCrewMembersCount() que é seguro
        // ao invés de acessar diretamente a coleção
        StringBuilder crewList = new StringBuilder();
        if (schedule.getCrewMembersCount() == 0) {
            crewList.append("Nenhum tripulante embarcado");
        } else {
            // Usar o nickname que já deve estar salvo corretamente
            schedule.getInitializedCrewMembers().forEach(user ->
                    crewList.append("• ").append(user.getNickname()).append("\n")
            );
        }

        StringBuilder logList = new StringBuilder();
        List<ScheduleLog> logs = scheduleService.getScheduleLogs(schedule.getId());
        logs.stream().limit(5).forEach(log ->
                logList.append(log.getTimestamp().toLocalTime()).append(" - ")
                        .append(log.getUsername()).append(": ")
                        .append(log.getDetails()).append("\n")
        );

        if (logs.isEmpty()) {
            logList.append("Nenhuma atividade registrada");
        }

        return EmbedCreateSpec.builder()
                .title(schedule.getTitle())
                .description("Escala de voo #" + schedule.getId())
                .addField("Aeronave", schedule.getAircraftType().getDisplayName(), true)
                .addField("Criado por", schedule.getCreatedByUsername(), true)
                .addField("Status", schedule.isActive() ? "Ativo" : "Encerrado", true)
                .addField("Tripulação", crewList.toString(), false)
                .addField("Últimas atividades", logList.toString(), false)
                .timestamp(Instant.now())
                .color(schedule.isActive() ? discord4j.rest.util.Color.GREEN : discord4j.rest.util.Color.RED)
                .build();
    }
}