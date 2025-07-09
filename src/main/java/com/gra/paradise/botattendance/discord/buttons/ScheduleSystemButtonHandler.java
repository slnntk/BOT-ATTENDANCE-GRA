package com.gra.paradise.botattendance.discord.buttons;

import com.gra.paradise.botattendance.model.AircraftType;
import com.gra.paradise.botattendance.model.MissionType;
import com.gra.paradise.botattendance.model.Schedule;
import com.gra.paradise.botattendance.service.MessageService;
import com.gra.paradise.botattendance.service.ScheduleService;
import discord4j.core.event.domain.interaction.ButtonInteractionEvent;
import discord4j.core.event.domain.interaction.SelectMenuInteractionEvent;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.Button;
import discord4j.core.object.component.SelectMenu;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.core.spec.MessageCreateSpec;
import discord4j.rest.util.Color;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleSystemButtonHandler {

    private final ScheduleService scheduleService;
    private final MessageService messageService;

    public Mono<Void> handleCreateScheduleButton(ButtonInteractionEvent event) {
        // Criar menu dropdown para selecionar aeronave
        List<SelectMenu.Option> aircraftOptions = new ArrayList<>();
        for (AircraftType aircraftType : AircraftType.values()) {
            aircraftOptions.add(SelectMenu.Option.of(
                    aircraftType.getDisplayName(),
                    aircraftType.name()
            ));
        }

        SelectMenu aircraftSelect = SelectMenu.of(
                "aircraft_select",
                aircraftOptions
        ).withPlaceholder("Selecione a aeronave");

        return event.reply()
                .withEphemeral(true)
                .withContent("Selecione a aeronave para a nova escala:")
                .withComponents(ActionRow.of(aircraftSelect));
    }

    public Mono<Void> handleAircraftSelection(SelectMenuInteractionEvent event) {
        String aircraftTypeStr = event.getValues().get(0);
        AircraftType aircraftType = AircraftType.valueOf(aircraftTypeStr);

        // Criar menu dropdown para selecionar tipo de missão
        List<SelectMenu.Option> missionOptions = new ArrayList<>();
        for (MissionType missionType : MissionType.values()) {
            missionOptions.add(SelectMenu.Option.of(
                    missionType.getDisplayName(),
                    missionType.name()
            ));
        }

        SelectMenu missionSelect = SelectMenu.of(
                "mission_select:" + aircraftTypeStr,
                missionOptions
        ).withPlaceholder("Selecione o tipo de missão");

        // Criar um embed informativo
        EmbedCreateSpec embed = EmbedCreateSpec.builder()
                .title("Seleção de Tipo de Missão")
                .description("Aeronave selecionada: " + aircraftType.getDisplayName())
                .addField("Próximo passo", "Selecione o tipo de missão para esta escala", false)
                .color(Color.BLUE)
                .timestamp(Instant.now())
                .build();

        return event.edit()
                .withEmbeds(embed)
                .withContent("Selecione o tipo de missão para a escala:")
                .withComponents(ActionRow.of(missionSelect));
    }

    public Mono<Void> handleMissionSelection(SelectMenuInteractionEvent event) {
        String customId = event.getCustomId();
        String[] parts = customId.split(":");
        String aircraftTypeStr = parts[1];
        String missionTypeStr = event.getValues().get(0);

        AircraftType aircraftType = AircraftType.valueOf(aircraftTypeStr);
        MissionType missionType = MissionType.valueOf(missionTypeStr);

        // Gerar o título automático baseado na contagem de GRAs ativas
        String title = scheduleService.generateGraTitle();

        Button confirmButton = Button.success(
                "confirm_schedule:" + aircraftTypeStr + ":" + missionTypeStr + ":" + title,
                "Confirmar"
        );
        Button cancelButton = Button.danger("cancel_schedule", "Cancelar");

        // Criar um embed com todas as informações
        EmbedCreateSpec embed = EmbedCreateSpec.builder()
                .title("Nova Escala de Voo")
                .addField("Título", title, false)
                .addField("Aeronave", aircraftType.getDisplayName(), false)
                .addField("Tipo de Missão", missionType.getDisplayName(), false)
                .color(Color.GREEN)
                .timestamp(Instant.now())
                .build();

        return event.edit()
                .withEmbeds(embed)
                .withContent("Confirme a criação da escala:")
                .withComponents(ActionRow.of(confirmButton, cancelButton));
    }

    public Mono<Void> handleConfirmSchedule(ButtonInteractionEvent event) {
        String[] parts = event.getCustomId().split(":");
        String aircraftTypeStr = parts[1];
        String missionTypeStr = parts[2];
        String title = parts.length > 3 ? parts[3] : scheduleService.generateGraTitle();

        AircraftType aircraftType = AircraftType.valueOf(aircraftTypeStr);
        MissionType missionType = MissionType.valueOf(missionTypeStr);

        discord4j.core.object.entity.User discordUser = event.getInteraction().getUser();
        String userId = discordUser.getId().asString();

        // Primeiro, adiar a resposta para poder eliminar a mensagem original
        return event.deferEdit()
                .then(event.deleteReply()) // Deletar a mensagem original de confirmação
                .then(Mono.justOrEmpty(event.getInteraction().getMember())
                        .flatMap(member -> {
                            String nickname = member.getNickname().orElse(discordUser.getUsername());

                            return Mono.fromCallable(() -> {
                                Schedule schedule = scheduleService.createSchedule(
                                        title,
                                        aircraftType,
                                        missionType,
                                        userId,
                                        nickname
                                );
                                return schedule;
                            });
                        })
                        .switchIfEmpty(Mono.fromCallable(() -> {
                            return scheduleService.createSchedule(
                                    title,
                                    aircraftType,
                                    missionType,
                                    userId,
                                    discordUser.getUsername()
                            );
                        })))
                .flatMap(schedule -> {
                    // Criar a mensagem pública da escala
                    return createSchedulePublicMessage(event, schedule)
                            // Usar mensagem temporária que desaparece após 10 segundos
                            .then(messageService.sendTemporaryFollowupMessage(event, "✅ Escala criada com sucesso!"));
                })
                .then(Mono.fromRunnable(() -> {
                    // Atualizar a mensagem do sistema em um bloco separado
                    scheduleService.updateSystemMessage();
                }));
    }

    private Mono<Void> createSchedulePublicMessage(ButtonInteractionEvent event, Schedule schedule) {
        // Criando os botões para a escala
        String scheduleId = String.valueOf(schedule.getId());

        Button boardButton = Button.success("board_schedule:" + scheduleId, "Embarcar");
        Button leaveButton = Button.danger("leave_schedule:" + scheduleId, "Desembarcar");
        Button endButton = Button.secondary("end_schedule:" + scheduleId, "Encerrar Escala");

        // Criar o embed para a escala
        EmbedCreateSpec scheduleEmbed = EmbedCreateSpec.builder()
                .title("Escala: " + schedule.getTitle())
                .description("Escala de voo ativa")
                .addField("Aeronave", schedule.getAircraftType().getDisplayName(), true)
                .addField("Tipo de Missão", schedule.getMissionType().getDisplayName(), true)
                .addField("Escalante", schedule.getCreatedByUsername(), true)
                .addField("Status", "Aguardando tripulantes", false)
                .addField("Tripulantes", "Nenhum tripulante embarcado", false)
                .color(getMissionColor(schedule.getMissionType()))
                .timestamp(Instant.now())
                .build();

        // Enviar mensagem pública no canal
        return event.getInteraction().getChannel()
                .flatMap(channel -> channel.createMessage(MessageCreateSpec.builder()
                        .addEmbed(scheduleEmbed)
                        .addComponent(ActionRow.of(boardButton, leaveButton, endButton))
                        .build()))
                .flatMap(message ->
                        scheduleService.registerScheduleMessage(
                                scheduleId,
                                event.getInteraction().getChannelId().asString(),
                                message.getId().asString()
                        )
                );
    }

    private Color getMissionColor(MissionType missionType) {
        return switch (missionType) {
            case PATROL -> Color.BLUE;
            case ACTION -> Color.RED;
            default -> Color.BLUE;
        };
    }

    public Mono<Void> handleCancelSchedule(ButtonInteractionEvent event) {
        // Adiar a edição e depois deletar a mensagem original
        return event.deferEdit()
                .then(event.deleteReply())
                // Usar mensagem temporária que desaparece após 10 segundos
                .then(messageService.sendTemporaryFollowupMessage(event, "❌ Criação de escala cancelada."));
    }

    // Métodos para tratar os botões de embarque, desembarque e encerramento

    public Mono<Void> handleBoardSchedule(ButtonInteractionEvent event) {
        String[] parts = event.getCustomId().split(":");
        String scheduleId = parts[1];
        String userId = event.getInteraction().getUser().getId().asString();

        // Adiar a resposta primeiro para evitar timeout
        return event.deferReply()
                .withEphemeral(true)
                .then(Mono.justOrEmpty(event.getInteraction().getMember())
                        .flatMap(member -> {
                            String displayName = member.getNickname().orElse(event.getInteraction().getUser().getUsername());

                            return Mono.fromCallable(() -> scheduleService.addPassenger(scheduleId, userId, displayName))
                                    .flatMap(success -> {
                                        if (success) {
                                            return scheduleService.updateScheduleMessage(scheduleId)
                                                    .then(messageService.sendTemporaryFollowupMessage(event, "✅ Você embarcou na escala!"));
                                        } else {
                                            return messageService.sendTemporaryFollowupMessage(event, "❌ Não foi possível embarcar na escala.");
                                        }
                                    });
                        }));
    }

    public Mono<Void> handleLeaveSchedule(ButtonInteractionEvent event) {
        String[] parts = event.getCustomId().split(":");
        String scheduleId = parts[1];
        String userId = event.getInteraction().getUser().getId().asString();

        // Adiar a resposta primeiro para evitar timeout
        return event.deferReply()
                .withEphemeral(true)
                .then(Mono.justOrEmpty(event.getInteraction().getMember())
                        .flatMap(member -> {
                            String displayName = member.getNickname().orElse(event.getInteraction().getUser().getUsername());

                            return Mono.fromCallable(() -> scheduleService.removePassenger(scheduleId, userId))
                                    .flatMap(success -> {
                                        if (success) {
                                            return scheduleService.updateScheduleMessage(scheduleId)
                                                    .then(messageService.sendTemporaryFollowupMessage(event, "✅ Você desembarcou da escala!"));
                                        } else {
                                            return messageService.sendTemporaryFollowupMessage(event, "❌ Não foi possível desembarcar da escala.");
                                        }
                                    });
                        }));
    }

    public Mono<Void> handleEndSchedule(ButtonInteractionEvent event) {
        String[] parts = event.getCustomId().split(":");
        String scheduleId = parts[1];
        String userId = event.getInteraction().getUser().getId().asString();

        return event.deferReply()
                .withEphemeral(true)
                .then(Mono.fromCallable(() -> {
                    boolean canEnd = scheduleService.canEndSchedule(scheduleId, userId);
                    return canEnd;
                }))
                .flatMap(canEnd -> {
                    if (!canEnd) {
                        return messageService.sendTemporaryFollowupMessage(event,
                                "❌ Você não tem permissão para encerrar esta escala.");
                    }

                    return Mono.fromCallable(() -> {
                                try {
                                    boolean success = scheduleService.endSchedule(scheduleId);
                                    return success;
                                } catch (Exception e) {
                                    log.error("Erro ao encerrar escala", e);
                                    return false;
                                }
                            })
                            .flatMap(success -> {
                                if (!success) {
                                    return messageService.sendTemporaryFollowupMessage(event,
                                            "❌ Erro ao encerrar a escala.");
                                }

                                // Tratar cada etapa independentemente para evitar que falhas em uma parte afetem o resto
                                return scheduleService.removeScheduleMessage(scheduleId)
                                        .onErrorResume(e -> {
                                            log.error("Erro ao remover mensagem da escala: {}", e.getMessage());
                                            return Mono.empty();
                                        })
                                        .then(scheduleService.updateSystemMessage()
                                                .onErrorResume(e -> {
                                                    log.error("Erro ao atualizar mensagem do sistema: {}", e.getMessage());
                                                    return Mono.empty();
                                                }))
                                        .then(messageService.sendTemporaryFollowupMessage(event,
                                                "✅ A escala foi encerrada com sucesso!"));
                            });
                })
                .onErrorResume(error -> {
                    log.error("Erro geral: {}", error.getMessage(), error);
                    return messageService.sendTemporaryFollowupMessage(event,
                            "❌ Ocorreu um erro ao processar sua solicitação.");
                });
    }
}