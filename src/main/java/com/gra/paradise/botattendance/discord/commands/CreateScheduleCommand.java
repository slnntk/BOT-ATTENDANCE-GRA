package com.gra.paradise.botattendance.discord.commands;

import com.gra.paradise.botattendance.model.*;
import com.gra.paradise.botattendance.service.ScheduleService;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.Button;
import discord4j.core.object.entity.User;
import discord4j.core.spec.EmbedCreateSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreateScheduleCommand implements Command {

    private final ScheduleService scheduleService;

    @Override
    public String getName() {
        return "criar-escala";
    }

    @Override
    public Mono<Void> handle(ChatInputInteractionEvent event) {
        log.info("Comando criar-escala recebido de {}", event.getInteraction().getUser().getUsername());

        String title = event.getOption("titulo")
                .flatMap(option -> option.getValue())
                .map(value -> value.asString())
                .orElse("Escala de Voo");
        log.debug("Título da escala: {}", title);

        String aircraftTypeStr = event.getOption("aeronave")
                .flatMap(option -> option.getValue())
                .map(value -> value.asString())
                .orElse("EC135");
        log.debug("Aeronave selecionada: {}", aircraftTypeStr);

        String missionTypeStr = event.getOption("missao")
                .flatMap(option -> option.getValue())
                .map(value -> value.asString())
                .orElse("PATROL");
        log.debug("Tipo de missão selecionada: {}", missionTypeStr);

        AircraftType aircraftType;
        MissionType missionType;

        try {
            aircraftType = AircraftType.valueOf(aircraftTypeStr);
            missionType = MissionType.valueOf(missionTypeStr);
            log.debug("Valores enum convertidos com sucesso");
        } catch (IllegalArgumentException e) {
            log.error("Erro ao converter valores enum: {}", e.getMessage());
            return event.reply("Erro ao processar os tipos de aeronave ou missão. Verifique os valores selecionados.")
                    .withEphemeral(true)
                    .then();
        }

        User user = event.getInteraction().getUser();
        String userId = user.getId().asString();
        String username = user.getUsername();

        return event.deferReply()
                .doOnSuccess(v -> log.info("Resposta deferida com sucesso"))
                .doOnError(e -> log.error("Erro ao deferir resposta: {}", e.getMessage()))
                .then(Mono.justOrEmpty(event.getInteraction().getMember())
                        .flatMap(member -> {
                            // Obtém o apelido do servidor ou usa o nome de usuário se não tiver
                            String nickname = member.getNickname().orElse(username);
                            log.info("Criando escala para usuário: {} (ID: {}) com apelido: {}",
                                    username, userId, nickname);

                            return Mono.fromCallable(() ->
                                    scheduleService.createSchedule(
                                            title,
                                            aircraftType,
                                            missionType,
                                            userId,
                                            nickname  // Use o apelido em vez do nome de usuário
                                    )
                            );
                        })
                        .switchIfEmpty(Mono.fromCallable(() -> {
                            // Fallback se não conseguir obter o Member
                            log.info("Criando escala para usuário sem apelido disponível: {} (ID: {})",
                                    username, userId);

                            return scheduleService.createSchedule(
                                    title,
                                    aircraftType,
                                    missionType,
                                    userId,
                                    username
                            );
                        })))
                .doOnError(e -> log.error("Erro ao criar escala: {}", e.getMessage()))
                .flatMap(schedule -> {
                    log.debug("Gerando embed para escala #{}", schedule.getId());
                    EmbedCreateSpec embed = createScheduleEmbed(schedule);

                    // Criar botões para ações
                    log.debug("Criando botões interativos");
                    Button joinButton = Button.success("join:" + schedule.getId(), "Embarcar");
                    Button leaveButton = Button.danger("leave:" + schedule.getId(), "Desembarcar");
                    Button closeButton = Button.secondary("close:" + schedule.getId(), "Encerrar Escala");

                    log.info("Enviando resposta com embed e botões");
                    return event.createFollowup()
                            .withEmbeds(embed)
                            .withComponents(ActionRow.of(joinButton, leaveButton, closeButton))
                            .doOnSuccess(message -> {
                                log.info("Mensagem enviada com sucesso, ID: {}", message.getId().asString());
                                // Salvar ID da mensagem para atualizações futuras
                                log.debug("Salvando informações da mensagem no banco de dados");
                                scheduleService.updateMessageInfo(
                                        schedule.getId(),
                                        message.getId().asString(),
                                        message.getChannelId().asString()
                                );
                            })
                            .doOnError(e -> log.error("Erro ao enviar mensagem: {}", e.getMessage()))
                            .then();
                })
                .doOnError(e -> log.error("Erro não tratado no comando criar-escala: {}", e.getMessage()))
                .onErrorResume(e -> {
                    return event.createFollowup("Ocorreu um erro ao criar a escala: " + e.getMessage())
                            .withEphemeral(true)
                            .then();
                });
    }

    private EmbedCreateSpec createScheduleEmbed(Schedule schedule) {
        log.debug("Montando embed para escala: {}", schedule.getTitle());
        StringBuilder crewList = new StringBuilder();
        if (schedule.getCrewMembers().isEmpty()) {
            crewList.append("Nenhum tripulante embarcado");
        } else {
            schedule.getCrewMembers().forEach(user ->
                    crewList.append("• ").append(user.getNickname()).append("\n")
            );
        }

        return EmbedCreateSpec.builder()
                .title(schedule.getTitle())
                .description("Escala de voo #" + schedule.getId())
                .addField("Aeronave", schedule.getAircraftType().getDisplayName(), true)
                .addField("Tipo de Missão", schedule.getMissionType().getDisplayName(), true)
                .addField("Criado por", schedule.getCreatedByUsername(), true)
                .addField("Status", schedule.isActive() ? "Ativo" : "Encerrado", true)
                .addField("Tripulação", crewList.toString(), false)
                .timestamp(Instant.now())
                .color(schedule.isActive() ?
                        getMissionColor(schedule.getMissionType()) :
                        discord4j.rest.util.Color.DARK_GRAY)
                .build();
    }

    private discord4j.rest.util.Color getMissionColor(MissionType missionType) {
        return switch (missionType) {
            case PATROL -> discord4j.rest.util.Color.BLUE;
            case ACTION -> discord4j.rest.util.Color.RED;
            default -> discord4j.rest.util.Color.GREEN;
        };
    }
}