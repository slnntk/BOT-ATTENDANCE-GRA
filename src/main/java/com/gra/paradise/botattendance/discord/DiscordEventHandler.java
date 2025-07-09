package com.gra.paradise.botattendance.discord;

import com.gra.paradise.botattendance.discord.buttons.ButtonDispatcher;
import com.gra.paradise.botattendance.discord.commands.Command;
import com.gra.paradise.botattendance.model.AircraftType;
import com.gra.paradise.botattendance.model.MissionType;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.ButtonInteractionEvent;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.event.domain.interaction.SelectMenuInteractionEvent;
import discord4j.discordjson.json.ApplicationCommandOptionChoiceData;
import discord4j.discordjson.json.ApplicationCommandOptionData;
import discord4j.discordjson.json.ApplicationCommandRequest;
import discord4j.rest.RestClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DiscordEventHandler {

    private final ApplicationContext applicationContext;
    private final GatewayDiscordClient gatewayDiscordClient;
    private final ButtonDispatcher buttonDispatcher;
    private final RestClient restClient;
    private final DiagnosticHandler diagnosticHandler;

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        // Registrar comandos
        registerCommands();

        // Configurar handlers para eventos
        gatewayDiscordClient.on(ChatInputInteractionEvent.class, this::handleSlashCommand)
                .subscribe();

        gatewayDiscordClient.on(ButtonInteractionEvent.class)
                .doOnNext(e -> diagnosticHandler.logInteraction(e))
                .flatMap(buttonDispatcher::handleButtonEvent)
                .onErrorResume(e -> {
                    log.error("Erro ao processar evento de botão", e);
                    return Mono.empty();
                })
                .subscribe();

        gatewayDiscordClient.on(SelectMenuInteractionEvent.class)
                .doOnNext(e -> diagnosticHandler.logInteraction(e))
                .flatMap(buttonDispatcher::handleSelectMenuEvent)
                .onErrorResume(e -> {
                    log.error("Erro ao processar evento de menu de seleção", e);
                    return Mono.empty();
                })
                .subscribe();

        log.info("Bot Discord iniciado com sucesso!");
    }

    private void registerCommands() {
        final long applicationId = gatewayDiscordClient.getRestClient().getApplicationId().block();
        List<ApplicationCommandRequest> commands = new ArrayList<>();

        // Comando para criar escala
        commands.add(ApplicationCommandRequest.builder()
                .name("criar-escala")
                .description("Cria uma nova escala de voo")
                .addOption(ApplicationCommandOptionData.builder()
                        .name("titulo")
                        .description("Título da escala")
                        .type(3)
                        .required(true)
                        .build())
                .addOption(ApplicationCommandOptionData.builder()
                        .name("aeronave")
                        .description("Tipo de aeronave")
                        .type(3)
                        .required(true)
                        .addAllChoices(createAircraftChoices())
                        .build())
                .addOption(ApplicationCommandOptionData.builder()
                        .name("missao")
                        .description("Tipo de missão")
                        .type(3)
                        .required(true)
                        .addAllChoices(createMissionTypeChoices())
                        .build())
                .build());

        // Comando para listar escalas ativas
        commands.add(ApplicationCommandRequest.builder()
                .name("escalas-ativas")
                .description("Lista todas as escalas de voo ativas")
                .build());

        // Comando para configurar o sistema de escalas fixo
        commands.add(ApplicationCommandRequest.builder()
                .name("setup-escala")
                .description("Configura o sistema de escalas de voo no canal atual")
                .build());

        // Comando para configurar canal de logs - CORRIGIDO
        commands.add(ApplicationCommandRequest.builder()
                .name("setup-log-channel")
                .description("Configura o canal atual como um canal de logs de escala")
                .addOption(ApplicationCommandOptionData.builder()
                        .name("tipo")
                        .description("Tipo de missão para este canal de logs")
                        .type(3) // STRING type
                        .required(false)
                        .addAllChoices(List.of(
                                ApplicationCommandOptionChoiceData.builder()
                                        .name("Patrulhamento")
                                        .value("PATROL")
                                        .build(),
                                ApplicationCommandOptionChoiceData.builder()
                                        .name("Ação")
                                        .value("ACTION")
                                        .build()
                        ))
                        .build())
                .build());

        // Registra comandos globalmente
        restClient.getApplicationService()
                .bulkOverwriteGlobalApplicationCommand(applicationId, commands)
                .doOnNext(cmd -> log.info("Comando registrado: {}", cmd.name()))
                .subscribe();
    }

    private List<ApplicationCommandOptionChoiceData> createAircraftChoices() {
        return List.of(
                ApplicationCommandOptionChoiceData.builder()
                        .name("EC135")
                        .value("EC135")
                        .build(),
                ApplicationCommandOptionChoiceData.builder()
                        .name("Maverick")
                        .value("MAVERICK")
                        .build(),
                ApplicationCommandOptionChoiceData.builder()
                        .name("Valkyre")
                        .value("VALKYRE")
                        .build()
        );
    }

    private List<ApplicationCommandOptionChoiceData> createMissionTypeChoices() {
        return List.of(
                ApplicationCommandOptionChoiceData.builder()
                        .name("Patrulhamento")
                        .value("PATROL")
                        .build(),
                ApplicationCommandOptionChoiceData.builder()
                        .name("Ação")
                        .value("ACTION")
                        .build()
        );
    }

    private Mono<Void> handleSlashCommand(ChatInputInteractionEvent event) {
        String commandName = event.getCommandName();
        log.debug("Comando recebido: {}", commandName);

        // Buscar comando no contexto Spring
        Map<String, Command> commands = applicationContext.getBeansOfType(Command.class);
        for (Command command : commands.values()) {
            if (command.getName().equals(commandName)) {
                return command.handle(event)
                        .onErrorResume(e -> {
                            log.error("Erro ao executar comando {}: {}", commandName, e.getMessage(), e);
                            return event.createFollowup("Ocorreu um erro ao executar o comando: " + e.getMessage())
                                    .withEphemeral(true)
                                    .then();
                        });
            }
        }

        log.warn("Comando não encontrado: {}", commandName);
        return event.reply()
                .withContent("Comando não implementado: " + commandName)
                .withEphemeral(true);
    }

    @EventListener
    public void verifyCommands(ApplicationReadyEvent event) {
        log.info("Verificando comandos disponíveis:");
        Map<String, Command> commands = applicationContext.getBeansOfType(Command.class);
        commands.forEach((beanName, command) -> {
            log.info("Comando disponível: {} (Bean: {})", command.getName(), beanName);
        });
    }
}