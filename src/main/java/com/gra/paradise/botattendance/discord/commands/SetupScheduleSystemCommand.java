package com.gra.paradise.botattendance.discord.commands;

import com.gra.paradise.botattendance.service.ScheduleService;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.Button;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.rest.util.Color;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class SetupScheduleSystemCommand implements Command {

    private final ScheduleService scheduleService;

    @Override
    public String getName() {
        return "setup-escala";
    }

    @Override
    public Mono<Void> handle(ChatInputInteractionEvent event) {
        log.info("Comando setup-escala recebido de {}", event.getInteraction().getUser().getUsername());

        String channelId = event.getInteraction().getChannelId().asString();

        // Responder imediatamente
        return event.reply()
                .withEphemeral(true)
                .withContent("⏳ Configurando sistema de escalas neste canal...")
                .doOnSuccess(success -> {
                    // Configurar o sistema em background após responder
                    CompletableFuture.runAsync(() -> {
                        try {
                            // Criar embed e botão
                            EmbedCreateSpec embed = createSystemEmbed();
                            Button createButton = Button.primary("create_schedule", "Criar Escala");

                            // Enviar mensagem no canal
                            event.getInteraction().getChannel()
                                    .flatMap(channel -> channel.createMessage()
                                            .withEmbeds(embed)
                                            .withComponents(ActionRow.of(createButton)))
                                    .doOnNext(message -> {
                                        String messageId = message.getId().asString();
                                        log.debug("Mensagem de sistema criada no canal: {}", messageId);
                                        scheduleService.registerSystemMessage(channelId, messageId).subscribe();
                                    })
                                    .subscribe();

                            // Enviar mensagem de confirmação para o usuário
                            event.createFollowup("✅ Sistema de escalas configurado com sucesso!")
                                    .withEphemeral(true)
                                    .subscribe();
                        } catch (Exception e) {
                            log.error("Erro ao configurar sistema de escalas: {}", e.getMessage(), e);
                            event.createFollowup("❌ Erro ao configurar o sistema de escalas: " + e.getMessage())
                                    .withEphemeral(true)
                                    .subscribe();
                        }
                    });
                });
    }

    private EmbedCreateSpec createSystemEmbed() {
        return EmbedCreateSpec.builder()
                .title("Sistema de Escalas de Voo")
                .description("Clique no botão abaixo para criar uma nova escala de voo.")
                .addField("Instruções", "1. Clique em 'Criar Escala'\n2. Selecione a aeronave\n3. Selecione o tipo de missão\n4. Confirme", false)
                .addField("Escalas Ativas", "Nenhuma escala ativa no momento", false)
                .timestamp(Instant.now())
                .color(Color.BLUE)
                .build();
    }
}