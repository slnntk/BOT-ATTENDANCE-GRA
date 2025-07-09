package com.gra.paradise.botattendance.discord;

import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.DeferrableInteractionEvent;
import discord4j.core.event.domain.interaction.InteractionCreateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DiagnosticHandler {

    public DiagnosticHandler(GatewayDiscordClient client) {
        // Este handler vai registrar TODAS as interações que o bot recebe
        client.on(InteractionCreateEvent.class)
                .subscribe(event -> {
                    log.info("=========== INTERAÇÃO RECEBIDA ===========");
                    log.info("Tipo: {}", event.getClass().getSimpleName());
                    log.info("De: {}", event.getInteraction().getUser().getUsername());
                    log.info("==========================================");
                });
    }

    public void logInteraction(DeferrableInteractionEvent event) {
        log.debug("Interação recebida: {} de usuário: {}",
                event.getInteraction().getType().name(),
                event.getInteraction().getUser().getUsername());
    }
}