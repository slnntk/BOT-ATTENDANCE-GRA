package com.gra.paradise.botattendance.discord.commands;

import com.gra.paradise.botattendance.model.MissionType;
import com.gra.paradise.botattendance.service.ScheduleService;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandInteractionOptionValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class SetupLogChannelsCommand implements Command {

    private final ScheduleService scheduleService;

    @Override
    public String getName() {
        return "setup-log-channel";
    }

    @Override
    public Mono<Void> handle(ChatInputInteractionEvent event) {
        log.info("Comando setup-log-channel recebido de {}", event.getInteraction().getUser().getUsername());

        // Obter o canal atual
        String channelId = event.getInteraction().getChannelId().asString();

        // Log todos os parâmetros para debug
        event.getOptions().forEach(opt -> {
            log.info("Opção recebida: {} = {}", opt.getName(),
                    opt.getValue().map(ApplicationCommandInteractionOptionValue::asString).orElse("sem valor"));
        });

        // Extrair tipo da missão
        Optional<String> missionTypeStr = event.getOption("tipo")
                .flatMap(ApplicationCommandInteractionOption::getValue)
                .map(ApplicationCommandInteractionOptionValue::asString);

        log.info("Parâmetro tipo recebido: {}", missionTypeStr.orElse("não especificado"));

        // Definir o tipo de missão (null = todos os tipos)
        MissionType missionType = null;
        String logTypeMessage = "todas as missões";

        if (missionTypeStr.isPresent()) {
            try {
                missionType = MissionType.valueOf(missionTypeStr.get().toUpperCase());
                logTypeMessage = "missões do tipo " + missionType.getDisplayName();
                log.info("Tipo de missão configurado: {}", missionType);
            } catch (IllegalArgumentException e) {
                log.error("Tipo de missão inválido: {}", missionTypeStr.get());
                return event.reply()
                        .withEphemeral(true)
                        .withContent("❌ Tipo de missão inválido. Valores permitidos: PATROL, ACTION");
            }
        }

        // Variáveis finais para uso dentro do lambda
        final MissionType finalMissionType = missionType;
        final String finalLogTypeMessage = logTypeMessage;

        // Configurar o canal e responder ao usuário
        return event.reply()
                .withEphemeral(true)
                .withContent("✅ Este canal foi configurado como canal de logs para " + finalLogTypeMessage + ".")
                .then(scheduleService.configureLogChannel(channelId, finalMissionType))
                .onErrorResume(e -> {
                    log.error("Erro ao configurar canal de logs: {}", e.getMessage(), e);
                    return event.createFollowup("❌ Erro ao configurar o canal: " + e.getMessage())
                            .withEphemeral(true)
                            .then();
                });
    }
}